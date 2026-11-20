package dev.agentnet.role;

import java.util.List;
import java.util.Map;

import dev.agentnet.bus.AgentBus;
import dev.agentnet.bus.Message;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/**
 * A miner bringing what it mined for the base straight to the builder: one distributor can't carry
 * ~750 cobblestone and ~50 logs in time, but the miners that dig them can each bring their own load.
 * Only while the crew's base is going up and a builder is working on it.
 */
final class BaseDelivery {
    /** Gives up when it hasn't got closer for 90 s, or after 8 minutes in all (a deep pit is a long climb). */
    private static final int TIMEOUT_TICKS = 20 * 480, STALL_TICKS = 20 * 90;

    private AgentHandle to;
    private int timer, bestAt;
    private double bestDist;
    private int dropped;
    /** Close but stuck: dropping the load where it stands for the builder to fetch. */
    private boolean dropNear;

    boolean active() { return to != null || courier != null; }

    /** Ground drop: the distributor fetching it and where it lies (null when carrying it itself). */
    private AgentHandle courier;
    private BlockPos groundAt;
    private int groundItems;

    /** What the base is built from (and what its chests, furnaces, doors and torches are made of). */
    static boolean forBase(ItemStack s) {
        if (s.isEmpty()) return false;
        String id = Gear.id(s);
        return id.equals("minecraft:cobblestone") || id.equals("minecraft:cobbled_deepslate")
                || id.endsWith("_log") && !id.contains("stripped") || id.endsWith("_planks")
                || id.equals("minecraft:coal") || id.equals("minecraft:charcoal")
                || id.equals("minecraft:chest") || id.equals("minecraft:furnace") || id.equals("minecraft:torch")
                || id.equals("minecraft:oak_door") || id.equals("minecraft:crafting_table");
    }

    /** The builder putting up the crew's base, nearest first; null if the base is done or nobody is building it. */
    static AgentHandle builder(AgentHandle a) {
        Bases.Base b = Bases.crew(a.server(), a.dimension());
        if (b == null || b.built()) return null;
        AgentHandle best = null;
        double bestD = 300 * 300;
        for (AgentHandle h : AgentBus.agents.get()) {
            if (!h.isOnline() || h.role() != Role.BUILDER || !AgentHandle.sameDim(h.dimension(), a.dimension())) continue;
            double d = AgentBus.distSq(a, h);
            if (d < bestD) { bestD = d; best = h; }
        }
        return best;
    }

    boolean start(AgentHandle a) { return start(a, true); }

    /** The base's drop-off point: on the ground 3 blocks in front of its door. Null if there's no base going up. */
    static BlockPos dropOff(AgentHandle a) {
        Bases.Base b = Bases.crew(a.server(), a.dimension());
        if (b == null) return null;
        BlockPos spot = b.door().offset(0, 0, -3);
        var w = a.openWorld();
        if (w != null) {
            for (int y = spot.getY() + 2; y > spot.getY() - 8; y--) {
                var below = w.get(spot.getX(), y - 1, spot.getZ());
                var at = w.get(spot.getX(), y, spot.getZ());
                if (below == null || at == null) break; // not loaded from here: the door's height will do for now
                if (dev.agentnet.pathing.Rules.canStandOn(below) && dev.agentnet.pathing.Rules.canWalkThrough(at)) return new BlockPos(spot.getX(), y, spot.getZ());
            }
        }
        return spot;
    }

    /** Start a delivery if there's a builder to bring it to and something worth bringing (8+ items).
     *  allowGround: may leave it on the ground for a distributor (miners; a distributor carries it itself). */

    /** More than six solid blocks over its head (up to 64): deep in a mine, where nobody else can easily get to it. */
    static boolean deepUnder(AgentHandle a) {
        if (!(a.openWorld() instanceof dev.agentnet.pathing.BlockSource w)) return false;
        int x = (int) Math.floor(a.x()), y = (int) Math.floor(a.y() + 0.01), z = (int) Math.floor(a.z()), solid = 0;
        for (int dy = 2; dy <= 64; dy++) {
            var s = w.get(x, y + dy, z);
            if (s == null) break;
            if (Tunnel.solid(s) && !s.is(net.minecraft.tags.BlockTags.LEAVES) && !s.is(net.minecraft.tags.BlockTags.LOGS) && ++solid > 6) return true;
        }
        return false;
    }
    boolean start(AgentHandle a, boolean allowGround) {
        AgentHandle b = builder(a);
        if (b == null) return false;
        int n = 0;
        for (ItemStack s : a.inventory()) if (forBase(s)) n += s.getCount();
        if (n < 8) return false;
        timer = 0;
        // Far from the builder (deep in a pit, across the map): leave it on the ground for a distributor that
        // can get here in time, and keep mining. Otherwise carry it over itself.
        // Only really far (48+ blocks): a miner in the quarry by the base carries its load the few blocks itself
        // (it pillars out of its pit), since a drop on the ground loses most of it.
        // Never deep underground: a load left at y 11-16 in its own mine kept two distributors tunnelling toward it for 4
        // minutes each, load after load (run 1120zf). Down there it carries it back itself, up the tunnel it came down.
        boolean far = allowGround && AgentBus.distSq(a, b) > 48 * 48 && !deepUnder(a);
        if (far) {
            BlockPos here = BlockPos.containing(a.x(), a.y() + 0.2, a.z());
            AgentHandle c = GroundDrops.courier(a, here);
            int canSpare = 0; // what it would really drop: it keeps its own kit
            for (String id : new java.util.HashSet<>(a.inventory().stream().filter(BaseDelivery::forBase).map(Gear::id).toList()))
                canSpare += Math.max(0, Spare.spare(a, id));
            if (c != null && canSpare >= 8) {
                courier = c;
                groundAt = here;
                groundItems = canSpare;
                n = canSpare;
                // Claimed before the first stack falls: nobody else (not even this miner) picks it up.
                GroundDrops.add(new GroundDrops.Drop(a.name(), c.name(), here, System.currentTimeMillis() + GroundDrops.LIFETIME_MS));
                a.log("leaving " + n + " base materials here for " + c.name() + " to pick up (the builder is far)");
                return true;
            }
        }
        to = b;
        dropped = 0;
        bestDist = Double.MAX_VALUE;
        bestAt = 0;
        a.log("bringing " + n + " base materials to " + b.name());
        return true;
    }

    /** True while delivering (the caller skips its own work). */
    boolean tick(AgentHandle a) {
        if (courier != null) { Links.mark(a.name(), courier.name(), Links.Kind.WAITING, "left a load on the ground for it"); return dropHere(a); }
        if (to == null) return false;
        if (timer % 20 == 0) Links.mark(a.name(), to.name(), Links.Kind.BRINGING, dropNear ? "can't get the last bit: leaving the load" : "bringing base materials");
        timer++;
        // The drop-off point: one fixed spot on the ground in front of the base's door. Everyone brings loads
        // there and the builder collects from it; nobody chases the builder around the walls.
        BlockPos spot = dropOff(a);
        double tx = spot != null ? spot.getX() + 0.5 : to.x(), ty = spot != null ? spot.getY() : to.y(), tz = spot != null ? spot.getZ() + 0.5 : to.z();
        double d = (a.x() - tx) * (a.x() - tx) + (a.y() - ty) * (a.y() - ty) + (a.z() - tz) * (a.z() - tz);
        if (d < bestDist - 4) { bestDist = d; bestAt = timer; } // getting closer (digging out of a pit counts)
        // Close (within 10 blocks across) but no way the last bit, for 20 s: leave it right here and tell the
        // builder where.
        double hx = a.x() - tx, hz = a.z() - tz;
        // Only near the drop-off's own level (within 3 up or down): a load dropped at the bottom of the quarry pit is
        // one the builder can't get to either (it spent 14 minutes trying, and built nothing).
        if (!dropNear && hx * hx + hz * hz <= 10 * 10 && Math.abs(a.y() - ty) <= 3 && timer - bestAt > 20 * 20) {
            dropNear = true;
            GroundDrops.add(new GroundDrops.Drop(a.name(), to.name(), BlockPos.containing(a.x(), a.y() + 0.2, a.z()), System.currentTimeMillis() + GroundDrops.LIFETIME_MS)); // the builder's: nobody else takes it
            a.log("can't get the last few blocks to " + to.name() + ": leaving the load here for it");
        }
        if (!dropNear && (!to.isOnline() || timer > TIMEOUT_TICKS || timer - bestAt > STALL_TICKS)) { end(a, "couldn't reach " + to.name() + ": keeping the load"); return false; }
        if (d > 3 * 3 && !dropNear) {
            if (!a.isNavigating() && timer % 20 == 1) a.navigate((int) Math.floor(tx), (int) Math.floor(ty), (int) Math.floor(tz), 2);
            return true;
        }
        if (a.isNavigating()) a.stopNavigation();
        if (timer % 3 != 0) return true;
        List<ItemStack> inv = a.inventory();
        // Keep a little for its own tools: a stone pickaxe and a crafting table.
        int keepCobble = 3, keepLogs = 2;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (!forBase(s)) continue;
            String id = Gear.id(s);
            boolean cobble = id.equals("minecraft:cobblestone") || id.equals("minecraft:cobbled_deepslate");
            boolean log = id.endsWith("_log");
            if (cobble && keepCobble > 0) { keepCobble -= s.getCount(); if (keepCobble >= 0) continue; }
            if (log && keepLogs > 0) { keepLogs -= s.getCount(); if (keepLogs >= 0) continue; }
            a.lookAt(a.x(), a.y() - 1, a.z()); // at its feet: the pile stays in one place
            dropped += s.getCount();
            a.dropStack(i);
            return true;
        }
        // Where it lies, so a builder that isn't right here walks over for it.
        AgentBus.send(Message.of(a.name(), to.name(), Message.Type.ITEM_DELIVERED, "base materials",
                Map.of("x", String.valueOf((int) Math.floor(a.x())), "y", String.valueOf((int) Math.floor(a.y())), "z", String.valueOf((int) Math.floor(a.z())))));
        dropNear = false;
        end(a, "left " + dropped + " base materials with " + to.name());
        return false;
    }

    /** Drop the base materials at its feet, one stack every few ticks, then tell the courier where. */
    private boolean dropHere(AgentHandle a) {
        ++timer;
        if (a.isNavigating()) a.stopNavigation();
        List<ItemStack> inv = a.inventory();
        // 30 s at most: drops that didn't go through (or a load that kept coming back) held ten miners still for hours.
        if (timer < 20 * 30) for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (!forBase(s)) continue;
            // Only what it can spare: it keeps its own kit (crafting table, 11 cobblestone for a stone pickaxe,
            // some logs and planks, coal for torches), or it ends up mining stone with its fist.
            int spare = Spare.spare(a, Gear.id(s));
            if (spare <= 0) continue;
            a.lookAt(a.x(), a.y() - 1, a.z()); // straight down: it lands at its feet, in its own tunnel
            if (s.getCount() <= spare) a.dropStack(i); else a.dropOne(i);
            return true;
        }
        AgentBus.send(Message.of(a.name(), courier.name(), Message.Type.GROUND_PICKUP, "my load is on the ground here",
                Map.of("x", String.valueOf(groundAt.getX()), "y", String.valueOf(groundAt.getY()), "z", String.valueOf(groundAt.getZ()), "items", groundItems + " base materials")));
        a.log("left " + groundItems + " base materials on the ground for " + courier.name());
        courier = null;
        groundAt = null;
        return false;
    }

    private void end(AgentHandle a, String why) {
        a.log(why);
        to = null;
    }
}
