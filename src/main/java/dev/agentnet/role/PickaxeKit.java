package dev.agentnet.role;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.world.item.ItemStack;

/**
 * A pickaxe for everyone (the user, 2026-11-19: "every single role should get a pickaxe, even base guys, literally
 * everyone"). Whatever its job, an agent with no pickaxe in its pack makes one before it goes on: a stone one if it
 * carries three cobblestone, else a wooden one; with no wood for it, it chops three logs first. Without one it cannot dig
 * itself out of anything, and half of the "stuck" agents of run 1119 had none.
 *
 * <p>Called from every agent's tick, before its role. Not in the Nether or the End (no trees there), not while it
 * fights. If there is no wood to be had in three minutes, it lets the role work for five and tries again.
 */
public final class PickaxeKit {
    private static final class Kit {
        final MakerModule crafter = new MakerModule();
        final MinerModule miner = new MinerModule();
        { miner.keepsLoad = true; } // a helper: it keeps what it chops and never gives the agent a task of its own
        boolean mining;
        int ticks, mineStart, pausedUntil, lastTry = -1000, jobAt;
        boolean said;
    }

    private static final Map<String, Kit> KITS = new ConcurrentHashMap<>();

    private PickaxeKit() {}

    /** One tick. True while it is busy getting its pickaxe (the role waits). */
    public static boolean tick(AgentHandle a) {
        Kit k = KITS.computeIfAbsent(a.name().toLowerCase(), n -> new Kit());
        k.ticks++;
        int best = Gear.bestTool(a.inventory(), "pickaxe");
        if (best >= 0) {
            // A wooden one wears out in a few dozen blocks, and then there is none and no way to get cobblestone for a better
            // one (run 1119o: a helper's broke within a minute and it stood "no pickaxe" for good). So: three stone with
            // the wooden one, straight away, and a stone pickaxe from them.
            if (Gear.id(a.inventory().get(best)).equals("minecraft:wooden_pickaxe") && a.dimension() != null && a.dimension().endsWith("overworld") && !a.airborne()) {
                if (k.crafter.busy()) { k.crafter.tick(a); return true; }
                int cob = 0;
                for (ItemStack s : a.inventory()) { String id = Gear.id(s); if (id.equals("minecraft:cobblestone") || id.equals("minecraft:cobbled_deepslate")) cob += s.getCount(); }
                int planks = 0; boolean tbl = false;
                for (ItemStack s : a.inventory()) { String id = Gear.id(s); if (id.endsWith("_planks")) planks += s.getCount(); else if ((id.endsWith("_log") || id.endsWith("_stem")) && !id.contains("stripped")) planks += 4 * s.getCount(); else if (id.equals("minecraft:crafting_table")) tbl = true; }
                // No table and no wood for one: not now (it tried every 3 s, 127 times, "needs a crafting table", run 1119p).
                if (cob >= 3 && !tbl && planks < 4) return false;
                if (cob >= 3) {
                    if (k.mining) { k.miner.onTask(a, "idle"); k.mining = false; }
                    if (k.ticks - k.lastTry >= 600) { k.lastTry = k.ticks; if (k.crafter.queueTool(a, "minecraft:stone_pickaxe")) a.log("§7three cobblestone: a stone pickaxe before the wooden one wears out"); }
                    return k.crafter.busy();
                }
                if (k.ticks >= k.pausedUntil) {
                    if (k.mining && k.miner.idle() && k.miner.status().startsWith("done") && k.ticks - k.jobAt > 100) k.mining = false;
                    if (!k.mining) { k.mining = true; k.jobAt = k.ticks; if (k.mineStart == 0 || k.ticks - k.mineStart > 20 * 600) k.mineStart = k.ticks; k.miner.onTask(a, "mine 3 stone"); }
                    if (k.ticks - k.mineStart > 20 * 120) { k.miner.onTask(a, "idle"); k.mining = false; k.pausedUntil = k.ticks + 20 * 300; return false; }
                    k.miner.tick(a);
                    return true;
                }
                return false;
            }
            if (k.mining) { k.miner.onTask(a, "idle"); k.mining = false; }
            if (k.said) { k.said = false; a.log("§ahas its pickaxe"); }
            if (k.crafter.busy()) { k.crafter.tick(a); return true; } // its table goes back into its pack
            return false;
        }
        if (a.dimension() == null || !a.dimension().endsWith("overworld") || a.airborne()) return false;
        if (k.crafter.busy()) { k.crafter.tick(a); return true; }
        if (k.ticks < k.pausedUntil) return false;
        int wood = 0, cobble = 0, sticks = 0;
        boolean table = false;
        for (ItemStack s : a.inventory()) {
            if (s.isEmpty()) continue;
            String id = Gear.id(s);
            if (id.endsWith("_planks")) wood += s.getCount();
            else if ((id.endsWith("_log") || id.endsWith("_stem")) && !id.contains("stripped")) wood += 4 * s.getCount();
            else if (id.equals("minecraft:cobblestone") || id.equals("minecraft:cobbled_deepslate")) cobble += s.getCount();
            else if (id.equals("minecraft:crafting_table")) table = true;
            else if (id.equals("minecraft:stick")) sticks += s.getCount();
        }
        // A wooden pickaxe is 3 planks and 2 sticks (2 planks make 4); a table to make it on, 4 more.
        int need = (cobble >= 3 ? 0 : 3) + (sticks >= 2 ? 0 : 2);
        if (!table) need += 4;
        if (wood >= need) {
            if (k.mining) { k.miner.onTask(a, "idle"); k.mining = false; }
            if (k.ticks - k.lastTry >= 60) {
                k.lastTry = k.ticks;
                String pick = cobble >= 3 ? "minecraft:stone_pickaxe" : "minecraft:wooden_pickaxe";
                if (k.crafter.queueTool(a, pick) && !k.said) { k.said = true; a.log("§7no pickaxe: making a " + pick.substring(10).replace('_', ' ') + " before anything else"); }
            }
            return k.crafter.busy();
        }
        // Its three logs are in and the wood is still short (the miner made itself an axe out of them): three more.
        if (k.mining && k.miner.idle() && k.miner.status().startsWith("done") && k.ticks - k.jobAt > 100) k.mining = false;
        if (!k.mining) {
            k.jobAt = k.ticks;
            if (k.mineStart == 0 || k.ticks - k.mineStart > 20 * 600) k.mineStart = k.ticks;
            k.mining = true;
            k.miner.onTask(a, "mine 3 logs");
            if (!k.said) { k.said = true; a.log("§7no pickaxe and no wood for one: chopping three logs first"); }
        }
        if (k.ticks - k.mineStart > 20 * 180) {
            k.miner.onTask(a, "idle"); k.mining = false; k.said = false;
            k.pausedUntil = k.ticks + 20 * 300;
            a.log("§eno wood for a pickaxe in three minutes: on with its job, and again in five");
            return false;
        }
        k.miner.tick(a);
        return true;
    }
}
