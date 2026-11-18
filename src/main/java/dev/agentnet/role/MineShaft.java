package dev.agentnet.role;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.agentnet.pathing.BlockSource;
import dev.agentnet.pathing.Rules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The crew's one way down: a spiral staircase beside the base that every miner uses, instead of each miner
 * digging its own staircase to every ore. It winds around a 4x4 ring (12 steps a turn), one block lower per
 * step, 3 blocks of head room, from the ground beside the base down to diamond depth. Whoever needs it deeper
 * than it goes digs the next steps (one miner at a time; the others wait at its bottom).
 */
public final class MineShaft {
    /** The ring, in walking order: each cell is next to the one before it. */
    private static final int[][] RING = {{0, 0}, {1, 0}, {2, 0}, {3, 0}, {3, 1}, {3, 2}, {3, 3}, {2, 3}, {1, 3}, {0, 3}, {0, 2}, {0, 1}};
    static final int BOTTOM_Y = -54;

    record Shaft(int x, int z, int top) {
        /** Step k: where the feet stand on it. */
        BlockPos step(int k) { int[] c = RING[Math.floorMod(k, RING.length)]; return new BlockPos(x + c[0], top - k, z + c[1]); }
        int steps() { return top - BOTTOM_Y + 1; }
        /** The step whose feet are at this height (clamped to the shaft). */
        int stepAt(int y) { return Math.max(0, Math.min(steps() - 1, top - y)); }
    }

    private static final Map<String, Shaft> SHAFTS = new ConcurrentHashMap<>();
    private static volatile String digger;
    private static volatile long diggerAt;
    /** The shaft hit water or lava here: no deeper (miners tunnel on from its bottom as before). */
    private static final Map<String, Integer> BLOCKED = new ConcurrentHashMap<>();

    private MineShaft() {}

    private static Path file() { return dev.agentnet.AgentFiles.dir().resolve("shafts.txt"); }

    private static String key(Bases.Base b) { return b.server() + "|" + b.dimension() + "|" + b.origin().toShortString(); }

    /** Leaves and logs: a tree, not the ground. */
    private static boolean treeTop(BlockState s) {
        return s.is(net.minecraft.tags.BlockTags.LEAVES) || s.is(net.minecraft.tags.BlockTags.LOGS);
    }

    /** The shaft of the crew's base here, planned (and saved) the first time; null with no base or no world. */
    static Shaft of(AgentHandle a) {
        if (a.dimension() == null || !a.dimension().endsWith("overworld")) return null;
        Bases.Base b = Bases.crew(a.server(), a.dimension());
        if (b == null || b.hub() == null) return null;
        String k = key(b);
        Shaft s = SHAFTS.get(k);
        if (s == null) { load(); s = SHAFTS.get(k); }
        BlockSource w = a.openWorld();
        if (s != null) {
            // Planned on a treetop (leaves counted as ground): the miners tried for minutes to reach a staircase up in a
            // dark oak canopy. Planned again from the real ground.
            BlockState under = w == null ? null : w.get(s.x(), s.top() - 1, s.z());
            // Or its first step hangs in the air: planned on a mound at spawn (y=70, the base floor at 63) that the miners then
            // dug away; three miners fell into the pit under it and waited there for twenty minutes, the staircase out of reach.
            if (under == null || !treeTop(under) && !Rules.canWalkThrough(under)) return s;
            dev.agentnet.AgentNet.LOG.info("[Shaft] the staircase at {} {} {} starts on {}: planning it again from the ground", s.x(), s.top(), s.z(), treeTop(under) ? "a treetop" : "nothing (the ground under it is gone)");
            SHAFTS.remove(k);
        }
        if (w == null) return null;
        // West of the building, level with the middle of its side, clear of the walls.
        int x = b.origin().getX() - 8, z = b.origin().getZ() + 8;
        int top = Integer.MIN_VALUE;
        for (int y = b.hub().getY() + 12; y > b.hub().getY() - 12; y--) {
            BlockState s0 = w.get(x, y - 1, z);
            if (s0 == null) return null;
            if (Rules.canStandOn(s0) && !treeTop(s0)) { top = y; break; } // the ground, not the top of a tree
        }
        if (top == Integer.MIN_VALUE) top = b.hub().getY();
        s = new Shaft(x, z, top);
        SHAFTS.put(k, s);
        save();
        dev.agentnet.AgentNet.LOG.info("[Shaft] the crew's staircase mine starts at {} {} {}", x, top, z);
        return s;
    }

    private static boolean loaded;

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (!Files.exists(file())) return;
            for (String line : Files.readAllLines(file())) {
                String[] p = line.split("\\|");
                if (p.length != 6 || line.startsWith("#")) continue;
                SHAFTS.put(p[0] + "|" + p[1] + "|" + p[2], new Shaft(Integer.parseInt(p[3]), Integer.parseInt(p[5]), Integer.parseInt(p[4])));
            }
        } catch (Exception e) { dev.agentnet.AgentNet.LOG.warn("couldn't read shafts.txt", e); }
    }

    private static synchronized void save() {
        try {
            StringBuilder sb = new StringBuilder("# server|dimension|base origin|x|top y|z\n");
            for (var e : SHAFTS.entrySet()) sb.append(e.getKey()).append('|').append(e.getValue().x()).append('|').append(e.getValue().top()).append('|').append(e.getValue().z()).append('\n');
            Files.writeString(file(), sb.toString());
        } catch (Exception e) { dev.agentnet.AgentNet.LOG.warn("couldn't write shafts.txt", e); }
    }

    /** Step k is dug out: its 3 blocks of room are open. */
    static boolean dug(BlockSource w, Shaft s, int k) {
        BlockPos p = s.step(k);
        for (int dy = 0; dy <= 2; dy++) {
            BlockState st = w.get(p.getX(), p.getY() + dy, p.getZ());
            if (st == null || !Rules.canWalkThrough(st)) return false;
        }
        return true;
    }

    /** How many steps from the top are dug out (the first one that isn't). */
    static int depth(BlockSource w, Shaft s) {
        int k = 0;
        while (k < s.steps() && dug(w, s, k)) k++;
        return k;
    }

    /** On a staircase ring's steps: no block may be placed there, it would block the way down. The 2x2 pillar in the middle
     * is not a step: blocks go there (an agent fallen into a pit dug in it pillars its way out). */
    public static boolean inShaft(String server, String dimension, int x, int z) {
        load();
        String pre = server + "|" + dimension + "|";
        for (var e : SHAFTS.entrySet()) {
            if (!e.getKey().startsWith(pre)) continue;
            Shaft s = e.getValue();
            boolean core = x >= s.x() + 1 && x <= s.x() + 2 && z >= s.z() + 1 && z <= s.z() + 2; // the middle pillar: no step there
            if (!core && x >= s.x() && x <= s.x() + 3 && z >= s.z() && z <= s.z() + 3) return true;
        }
        return false;
    }

    static int blockedAt(Shaft s) { return BLOCKED.getOrDefault(s.toString(), Integer.MAX_VALUE); }
    static void block(Shaft s, int k) { BLOCKED.merge(s.toString(), k, Math::min); }

    /** Only one miner digs it at a time: true if this one may (it is the digger, or nobody has dug for 30 s). */
    static boolean mayDig(AgentHandle a) {
        long now = System.currentTimeMillis();
        // The turn passes on when the digger hasn't dug a block for 30 s (it wandered off, got stuck far away): a
        // digger that just kept asking held four miners at the bottom for half an hour.
        if (digger == null || now - diggerAt > 30_000) { digger = a.name(); diggerAt = now; return true; }
        return digger.equals(a.name());
    }

    /** A step block dug: the digger is making progress (keeps its turn). */
    static void dugOne(AgentHandle a) { if (a.name().equals(digger)) diggerAt = System.currentTimeMillis(); }

    static String digger() { return digger; }
}
