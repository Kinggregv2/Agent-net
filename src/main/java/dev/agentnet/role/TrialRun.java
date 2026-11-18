package dev.agentnet.role;

import java.util.ArrayList;
import java.util.List;

import dev.agentnet.pathing.BlockView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Clearing a trial chamber before looting it:
 * <ol>
 *   <li>an ominous bottle, if it carries one, is drunk first (Bad Omen turns into Trial Omen in
 *       the chamber: the spawners go ominous, their keys open the ominous vaults);</li>
 *   <li>each trial spawner in turn: walk up until it wakes, fight its waves (melee, best sword,
 *       full swings; a golden apple or food when hurt; back off at very low health);</li>
 *   <li>when a spawner pays out, pick up what it throws (the keys among it), next spawner.</li>
 * </ol>
 * Then the looter goes through the vaults (with those keys) and the chests, barrels and pots.
 */
final class TrialRun {
    private final List<BlockPos> spawners = new ArrayList<>();
    private int index, ticks, cooldown, eatTicks, waitTicks;
    private boolean drank, active;
    private int enemy = -1, lastChased = -1, chaseStart;
    private final java.util.Map<Integer, Long> unreachable = new java.util.HashMap<>();
    String status = "";

    boolean active() { return active; }

    void start(AgentHandle a, BlockView view, BlockPos near) {
        spawners.clear();
        spawners.addAll(view.findAll(near.getX(), near.getZ(), 48, s -> s.getBlock() == Blocks.TRIAL_SPAWNER, 32));
        spawners.removeIf(p -> Math.abs(p.getY() - near.getY()) > 24);
        spawners.sort(java.util.Comparator.comparingDouble(p -> p.distToCenterSqr(a.x(), a.y(), a.z())));
        index = 0;
        ticks = 0;
        drank = false;
        active = !spawners.isEmpty();
        if (active) a.log("trial chambers: " + spawners.size() + " spawner(s) to beat before looting");
    }

    /** True while busy with the chamber. */
    boolean tick(AgentHandle a) {
        if (!active) return false;
        ticks++;
        if (cooldown > 0) cooldown--;
        if (eatTicks > 0) { eatTicks--; return true; }
        if (a.isDigging()) return true;
        float hp = a.health(), max = Math.max(1f, a.maxHealth());
        // 0. Armor it carries goes on first (right-click equips a piece into its empty slot).
        if (ticks % 6 == 0) {
            String[] pieces = {"_helmet", "_chestplate", "_leggings", "_boots"};
            List<ItemStack> inv = a.inventory(), worn = a.armor();
            for (int slot = 0; slot < 4; slot++) {
                if (!worn.get(slot).isEmpty()) continue;
                for (int i = 0; i < inv.size(); i++) {
                    if (!Gear.id(inv.get(i)).endsWith(pieces[slot])) continue;
                    hold(a, i);
                    a.useItem();
                    return true;
                }
            }
        }
        // 1. The ominous bottle, once.
        if (!drank) {
            drank = true;
            int b = slot(a, "minecraft:ominous_bottle");
            if (b >= 0) {
                hold(a, b);
                a.useItem();
                eatTicks = 40;
                a.log("drank an ominous bottle: the spawners will go ominous");
                return true;
            }
        }
        // 2. Hurt: a golden apple (or any food), before anything else.
        if (!Float.isNaN(hp) && hp / max < 0.5f) {
            int g = slot(a, "minecraft:enchanted_golden_apple");
            if (g < 0) g = slot(a, "minecraft:golden_apple");
            if (g < 0) for (int i = 0; i < a.inventory().size(); i++) if (Gear.isFood(a.inventory().get(i))) { g = i; break; }
            if (g >= 0 && (a.food() < 20 || Gear.id(a.inventory().get(g)).contains("golden_apple"))) {
                hold(a, g);
                a.useItem();
                eatTicks = 36;
                return true;
            }
        }
        // 3. Fight whatever the spawners sent.
        Seen target = null;
        long nowMs = System.currentTimeMillis();
        unreachable.values().removeIf(t -> nowMs - t > 15_000);
        for (Seen s : a.entitiesNear(16)) if (!s.player() && (s.hostile() || s.name().equals("breeze")) && !unreachable.containsKey(s.id())) { target = s; break; }
        if (target != null) { fight(a, target, hp, max); return true; }
        enemy = -1;
        // 4. The spawners, one after another.
        if (index >= spawners.size()) { active = false; a.log("trial chambers cleared: looting"); return false; }
        BlockPos sp = spawners.get(index);
        BlockState st = a.openWorld().get(sp.getX(), sp.getY(), sp.getZ());
        String state = st == null ? "" : st.toString();
        if (st == null || st.getBlock() != Blocks.TRIAL_SPAWNER || state.contains("cooldown") || state.contains("ejecting_reward") || state.contains("waiting_for_reward")) {
            // Done (or paying out): collect what it throws, then the next one.
            status = "collecting a spawner's reward";
            if (waitTicks++ < 20 * 6) {
                if (!a.isNavigating()) for (Vec3 v : a.itemsNear(8)) {
                    if (GroundDrops.reservedFor(a.name(), v)) continue; // another distributor's pickup
                    if (v.distanceToSqr(Vec3.atCenterOf(sp)) < 36 && v.distanceToSqr(a.x(), a.y(), a.z()) > 0.8) {
                        a.navigate((int) Math.floor(v.x), (int) Math.floor(v.y + 0.2), (int) Math.floor(v.z), 0);
                        break;
                    }
                }
                return true;
            }
            waitTicks = 0;
            index++;
            return true;
        }
        status = "waking trial spawner " + (index + 1) + "/" + spawners.size() + " (" + (state.contains("state=active") ? "fighting its waves" : "walking up") + ")";
        if (sp.distToCenterSqr(a.x(), a.y(), a.z()) > 6 * 6) {
            if (!a.isNavigating() && ticks % 40 == 1) a.navigate(sp.getX(), sp.getY(), sp.getZ(), 4);
        } else if (a.isNavigating()) a.stopNavigation();
        if (ticks > 20 * 60 * 6) { a.log("trial spawner at " + sp.toShortString() + " took too long: moving on"); index++; ticks = 0; }
        return true;
    }

    private void fight(AgentHandle a, Seen t, float hp, float max) {
        if (enemy != t.id()) { enemy = t.id(); dev.agentnet.AgentNet.LOG.info("[{}] fighting a {} from the trial spawner", a.name(), t.name()); }
        status = String.format("fighting a %s (%.0f blocks, %.0f HP)", t.name(), t.distance(), hp);
        // Very low and nothing to eat: step back from it for a moment.
        if (!Float.isNaN(hp) && hp / max < 0.25f) {
            double dx = a.x() - t.pos().x, dz = a.z() - t.pos().z, d = Math.max(0.1, Math.hypot(dx, dz));
            if (!a.isNavigating()) a.navigate((int) Math.floor(a.x() + dx / d * 8), (int) Math.floor(a.y()), (int) Math.floor(a.z() + dz / d * 8), 2);
            return;
        }
        if (t.distance() > 2.8) {
            // No way to it (down a ledge, behind bars): leave it for a while, fight what we can reach.
            if (!a.isNavigating() && a.navStatus().startsWith("failed") && ticks - chaseStart > 10) { unreachable.put(t.id(), System.currentTimeMillis()); enemy = -1; return; }
            if (enemy != lastChased) { lastChased = enemy; chaseStart = ticks; }
            if (!a.isNavigating() || ticks % 10 == 0) a.navigate((int) Math.floor(t.pos().x), (int) Math.floor(t.pos().y), (int) Math.floor(t.pos().z), 1);
            return;
        }
        if (a.isNavigating()) a.stopNavigation();
        if (cooldown > 0) return;
        int w = Gear.bestTool(a.inventory(), "sword");
        if (w < 0) w = Gear.bestTool(a.inventory(), "axe");
        if (w >= 0) hold(a, w);
        a.lookAt(t.pos().x, t.pos().y + 0.9, t.pos().z);
        a.attack(t.id());
        cooldown = 13;
    }

    private static int slot(AgentHandle a, String id) {
        List<ItemStack> inv = a.inventory();
        for (int i = 0; i < inv.size(); i++) if (Gear.id(inv.get(i)).equals(id)) return i;
        return -1;
    }

    private static void hold(AgentHandle a, int i) {
        if (i >= 9) { a.moveToHotbar(i, 7); i = 7; }
        a.selectHotbar(i);
    }
}
