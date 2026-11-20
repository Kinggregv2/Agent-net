package dev.agentnet.role;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.agentnet.bus.Message;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Warrior. No fighting AI yet; what it does today:
 * <ul>
 *   <li>Puts on armor and a shield it receives.</li>
 *   <li>Asks a distributor for what it's missing from the war kit, for golden apples when
 *       it has fewer than 32, for XP bottles when it has none, and for an armor piece that
 *       broke.</li>
 *   <li>Retreat mode (nothing happens yet, it's a flag for the future AI): under 32 golden
 *       apples, an armor piece under 20% durability, or a broken shield. A shield that's
 *       only disabled by an axe doesn't count.</li>
 * </ul>
 */
final class WarriorModule implements RoleModule {
    private static final long REQUEST_EVERY_MS = 60_000;
    private static final int GAPPLE_MIN = 32;
    private static final double ARMOR_LOW = 0.2;
    private static final String[] ARMOR = {"helmet", "chestplate", "leggings", "boots"};

    final Deque<Message> sightings = new ArrayDeque<>();
    /** Player the warrior was told to kill, or null for "anyone on the hitlist". */
    String target;

    private int ticks;
    private boolean retreating;
    private String retreatWhy = "";
    private final Map<String, Long> requested = new HashMap<>();
    /** Armor/shield it had: a piece that vanishes broke. */
    private final boolean[] hadArmor = new boolean[4];
    private boolean hadShield, shieldBroke;
    private int equipCooldown;
    private final Pickup pickup = new Pickup();

    @Override
    public void onTask(AgentHandle a, String task) {
        target = task.startsWith("kill ") ? task.substring(5) : null;
    }

    @Override
    public void onMessage(AgentHandle a, Message m) {
        if (m.type() == Message.Type.ITEM_DELIVERED) pickup.start();
        if (m.type() == Message.Type.ENEMY_SPOTTED) {
            sightings.add(m);
            a.log("enemy " + m.get("player") + " reported by " + m.from());
        }
        if (m.type() == Message.Type.HELP) {
            try {
                int id = Integer.parseInt(m.get("id"));
                Vec3 at = new Vec3(Double.parseDouble(m.get("x")) + 0.5, Double.parseDouble(m.get("y")), Double.parseDouble(m.get("z")) + 0.5);
                boolean known = rescues.containsKey(id);
                rescues.put(id, new Rescue(m.from(), at, System.currentTimeMillis()));
                if (!known) a.log("§cgoing to help " + m.from() + " (" + m.get("name") + ")");
            } catch (RuntimeException ignored) {}
        }
    }

    // ---- protecting the others

    /** Mobs this close to one of our agents get fought even if they ignore the warrior. */
    private static final double GUARD = 10, GUARD_SCAN = 48;
    private static final long RESCUE_MS = 60_000;
    private record Rescue(String who, Vec3 at, long since) {}
    /** Attacker entity id -> who called for help, from where. */
    private final java.util.Map<Integer, Rescue> rescues = new java.util.concurrent.ConcurrentHashMap<>();
    private int rescueNav;

    /** Close to one of our other (non-warrior) agents? */
    private static boolean nearOurs(AgentHandle a, Seen s) {
        for (AgentHandle o : dev.agentnet.bus.AgentBus.agents.get()) {
            if (o == a || o.role() == Role.WARRIOR) continue;
            double dx = o.x() - s.pos().x, dy = o.y() - s.pos().y, dz = o.z() - s.pos().z;
            if (dx * dx + dy * dy + dz * dz <= GUARD * GUARD) return true;
        }
        return false;
    }

    /** No fight here: walk to whoever called for help most recently, if it's out of sight. */
    private boolean goRescue(AgentHandle a) {
        long now = System.currentTimeMillis();
        rescues.values().removeIf(r -> now - r.since() > RESCUE_MS);
        Rescue r = null;
        for (Rescue x : rescues.values()) if (r == null || x.since() > r.since()) r = x;
        if (r == null) return false; // allies come first, even when low on supplies
        double dx = a.x() - r.at().x, dz = a.z() - r.at().z;
        if (dx * dx + dz * dz < 8 * 8) { rescues.values().remove(r); return false; } // there, nothing to fight: done
        if (--rescueNav <= 0) {
            rescueNav = 40;
            a.navigate((int) Math.floor(r.at().x), (int) Math.floor(r.at().y), (int) Math.floor(r.at().z), 4);
        }
        return true;
    }

    @Override
    public void tick(AgentHandle a) {
        ticks++;
        // Every second counts in a fight: the world and pathfinder stay loaded, never freed between trips.
        a.keepWorld(true); // every tick: a reconnect resets it, and a fighter without the world walks and flies blind
        if (!Float.isNaN(a.health())) {
            if (lastHp >= 0 && a.health() < lastHp - 0.01f) dev.agentnet.AgentNet.LOG.info("[{}] hurt: {} -> {} HP", a.name(), Math.round(lastHp * 10) / 10f, Math.round(a.health() * 10) / 10f);
            lastHp = a.health();
        }
        // No water or boat clutch while it glides or is about to land a mace smash (that hit cancels the fall).
        if (!engine.blocksClutch(a) && clutch(a)) return;
        if (engine.busy()) { fight(a); return; } // mid-move: nothing else touches the hands
        if (equipCooldown > 0) { equipCooldown--; return; }
        if (pickup.tick(a)) return;
        if (ticks % 10 == 0 && equip(a)) { equipCooldown = 5; return; }
        if (enemy == null && ticks % 10 == 5 && arrange(a)) return;
        if (ticks % 20 == 0) { checkGear(a); requestSupplies(a); }
        // Combat tests only (-Dagentnet.sparring): what it sees while it has nobody to fight.
        if (enemy == null && ticks % 100 == 0 && System.getProperty("agentnet.sparring") != null) {
            StringBuilder sb = new StringBuilder();
            for (Seen s : a.entitiesNear(200)) if (s.player()) sb.append(String.format(" %s@%.0f%s", s.name(), s.distance(), a.isEnemy(s.name()) ? "(enemy)" : ""));
            dev.agentnet.AgentNet.LOG.info("[{}] no enemy; players seen:{} | retreating={} at {} {} {}", a.name(), sb.length() == 0 ? " none" : sb, retreating, (int) a.x(), (int) a.y(), (int) a.z());
        }
        fight(a);
    }

    // ------------------------------------------------------------------ fighting: the new engine (Fight.java)
    // The old fight code (melee, shield, bow, creepers, boat trap, wind charge, elytra mace, cart) was scrapped on
    // 2026-11-18 at the user's request (git tag old-fight-engine) and is rebuilt move by move, each tested alone.

    /** Players on the hitlist (or the kill target) are chased this far; hostile mobs this far. */
    private static final double PLAYER_RANGE = 32, MOB_RANGE = 12;
    /** Even in retreat mode, anything this close gets fought off. */
    private static final double SELF_DEFENSE = 6;
    private static final double CREEPER_SHOOT = 14;
    /** "Monsters" that only fight back when hit, often as a group: leave them alone (angry endermen excepted). */
    private static final java.util.Set<String> NEUTRAL = java.util.Set.of("zombified_piglin", "enderman", "piglin", "spider_jockey", "piglin_brute");

    private Seen enemy;
    private int navTicks, hits;
    private net.minecraft.world.phys.Vec3 chasedTo;
    private boolean creeperRun;
    private volatile float lastHp = -1;
    private final Fight engine = new Fight();
    private String tactic = "", lastTactic = "";

    private void fight(AgentHandle a) {
        if (ticks % 5 == 0 || enemy != null) pickEnemy(a);
        // In the middle of an elytra mace: flown out against its target, looked up far out (up in the air it is beyond the
        // normal fighting range).
        if (engine.busy()) {
            Seen t = null;
            for (Seen s : a.entitiesNear(160)) if (s.id() == engine.targetId()) { t = s; break; }
            if (t == null) t = enemy;
            if (t != null) { engine.tick(a, t); tactic = engine.tactic; if (!tactic.equals(lastTactic)) { dev.agentnet.AgentNet.LOG.info("[{}] tactic: {}", a.name(), tactic); lastTactic = tactic; } return; }
        }
        // Nothing to fight and nobody calling for help: wait inside the base, not out in the open.
        // A hitlist player reported by anyone in the crew (a farmer out in the fields, the one following it): after it,
        // by elytra when it has one (navigate flies the long trips). "to add" 1.
        if (enemy == null) {
            var sight = Sightings.latest(a.dimension());
            if (sight != null && Math.sqrt(sight.pos().distanceToSqr(a.x(), a.y(), a.z())) > PLAYER_RANGE * 0.75) {
                engine.idle(a); tactic = "";
                if (!a.isNavigating() && ticks % 40 == 0) {
                    a.log(String.format("§c[hitlist] going after %s (seen by %s, %.0f blocks off)", sight.enemy(), sight.by(), Math.sqrt(sight.pos().distanceToSqr(a.x(), a.y(), a.z()))));
                    a.navigate((int) Math.floor(sight.pos().x), (int) Math.floor(sight.pos().y), (int) Math.floor(sight.pos().z), 8);
                }
                return;
            }
        }
        if (enemy == null) { engine.idle(a); tactic = ""; if (!goRescue(a) && rescues.isEmpty()) HomeBase.goHome(a, ticks); return; }
        engine.tick(a, enemy);
        tactic = engine.tactic;
        if (!tactic.equals(lastTactic)) { dev.agentnet.AgentNet.LOG.info("[{}] tactic: {}", a.name(), tactic); lastTactic = tactic; }
    }

    // ---- hotbar: a fixed layout so swaps are a single slot change

    /** 0 sword, 1 axe, 2 Density mace, 3 Breach mace, 4 Lunge spear, 5 bow, 6 wind charges, 7 water bucket, 8 boat. */
    private static final int HB_SWORD = 0, HB_AXE = 1, HB_DENSITY = 2, HB_BREACH = 3, HB_SPEAR = 4, HB_BOW = 5, HB_WIND = 6, HB_WATER = 7, HB_BOAT = 8;

    /** Score of an item for a hotbar job (0 = doesn't fit). */
    private static int fit(ItemStack s, int slot) {
        if (s.isEmpty()) return 0;
        var e = net.minecraft.world.item.enchantment.Enchantments.class;
        String id = Gear.id(s);
        return switch (slot) {
            case HB_SWORD -> "sword".equals(Gear.toolType(s)) ? 1 + tier(id) : 0;
            case HB_AXE -> "axe".equals(Gear.toolType(s)) ? 1 + tier(id) : 0;
            case HB_DENSITY -> s.getItem() == Items.MACE && Gear.level(s, net.minecraft.world.item.enchantment.Enchantments.BREACH) == 0 ? 1 + Gear.level(s, net.minecraft.world.item.enchantment.Enchantments.DENSITY) : 0;
            case HB_BREACH -> s.getItem() == Items.MACE ? Gear.level(s, net.minecraft.world.item.enchantment.Enchantments.BREACH) : 0;
            case HB_SPEAR -> id.endsWith("_spear") ? Gear.level(s, net.minecraft.world.item.enchantment.Enchantments.LUNGE) : 0;
            case HB_BOW -> s.getItem() == Items.BOW ? 1 : 0;
            case HB_WIND -> s.getItem() == Items.WIND_CHARGE ? 1 : 0;
            case HB_WATER -> s.getItem() == Items.WATER_BUCKET ? 2 : s.getItem() == Items.BUCKET ? 1 : 0;
            case HB_BOAT -> id.endsWith("_boat") || id.endsWith("_raft") ? 1 : 0;
            default -> 0;
        };
    }

    private static int tier(String id) {
        return id.contains("netherite") ? 6 : id.contains("diamond") ? 5 : id.contains("iron") ? 4 : id.contains("copper") ? 3 : id.contains("stone") ? 2 : 1;
    }

    /** One step of putting the gear into its hotbar slots. True if it moved something. */
    private boolean arrange(AgentHandle a) {
        List<ItemStack> inv = a.inventory();
        for (int slot = 0; slot <= 8; slot++) {
            int have = fit(inv.get(slot), slot), best = -1, bestFit = have;
            for (int i = 0; i < inv.size() && i < 36; i++) {
                if (i == slot || i < 9 && fit(inv.get(i), i) > 0 && i < slot) continue; // already placed in an earlier job
                int f = fit(inv.get(i), slot);
                if (f > bestFit) { bestFit = f; best = i; }
            }
            if (best >= 0) { a.moveToHotbar(best, slot); return true; }
        }
        return false;
    }

    // ---- clutch: no fall damage after a launch or a knockback off a ledge

    /** Where the clutch water/boat went (null = none). */
    private int[] clutchAt;
    private boolean clutchBoat, boatClicked;
    private int clutchTicks;

    /** True while busy clutching or cleaning up after one. */
    private boolean clutch(AgentHandle a) {
        if (clutchAt != null) return clutchAfter(a);
        if (!a.airborne() || a.verticalSpeed() >= 0) return false;
        if (a.flying() && a.gliding()) return false; // on an elytra trip: gliding, not falling
        // Mace smash coming: the hit itself cancels the fall, and a clutch would waste it.
        var w = a.openWorld();
        if (w == null) return false;
        int x = (int) Math.floor(a.x()), z = (int) Math.floor(a.z());
        int gy = Integer.MIN_VALUE;
        for (int y = (int) Math.floor(a.y()); y > (int) Math.floor(a.y()) - 48; y--) {
            var s = w.get(x, y, z);
            if (s == null) return false;
            if (!s.getFluidState().isEmpty()) return false; // falling into water/lava already
            if (!Tunnel.passable(s)) { gy = y; break; }
        }
        if (gy == Integer.MIN_VALUE) return false;
        double fall = a.airPeakY() - (gy + 1), above = a.y() - (gy + 1), vy = -a.verticalSpeed();
        if (fall <= 3.5) return false;               // no damage from this one
        if (above > 2.8) return false;               // not in reach yet (4.5 from the eyes)
        boolean nether = a.dimension() != null && a.dimension().endsWith("the_nether");
        int water = nether ? -1 : findItem(a, Items.WATER_BUCKET);
        int boat = boatSlot(a);
        if (water < 0 && boat < 0) return false;
        // Boat: like a player, click the ground at the last moment (the tick before touching down).
        // Water goes down as soon as the ground is in reach.
        // (Boat too: placed as soon as the ground is in reach, then right-clicked every tick until we sit in it.)
        a.look(a.yaw(), 90f);
        clutchAt = new int[]{x, gy + 1, z};
        clutchTicks = 0;
        if (water >= 0) {
            hold(a, water);
            // Leaves, slabs, stairs, fences...: a bucket would waterlog them and leave the top solid.
            // Crouching makes it fill the space on top instead.
            var ground = w.get(x, gy, z);
            boolean loggable = ground != null && (ground.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED) || ground.getBlock() instanceof net.minecraft.world.level.block.LiquidBlockContainer);
            if (loggable) a.useItemSneaking(); else a.useItem();
            a.expectWater(x, gy + 1, z);
            clutchBoat = false;
            a.log("§bwater clutch (" + Math.round(fall) + " block fall" + (loggable ? ", crouching over " + Gear.id(new ItemStack(ground.getBlock().asItem())).replace("minecraft:", "") : "") + ")");
        } else {
            // A boat needs room: its 1.375 x 0.5625 box must touch no block (and not us), or the
            // server refuses it. Find the nearest ground spot that fits and click that.
            double[] spot = boatSpot(w, a);
            if (spot == null) { clutchAt = null; return false; }
            hold(a, boat);
            a.lookAt(spot[0], spot[1], spot[2]);
            a.useItem();
            a.expectBoat(spot[0], spot[1], spot[2]);
            clutchBoat = true;
            boatClicked = false;
            a.log("§bboat clutch (" + Math.round(fall) + " block fall)");
        }
        return true;
    }

    private boolean clutchAfter(AgentHandle a) {
        clutchTicks++;
        // Second click, once: the first put the boat down; this one, as soon as it's there, gets in (riding cancels the fall).
        if (clutchBoat && !boatClicked && !a.riding() && a.recentBoat() >= 0) { a.interact(a.recentBoat()); boatClicked = true; dev.agentnet.AgentNet.LOG.info("[{}] clicked the boat to get in", a.name()); return true; }
        if (clutchBoat && a.riding()) { if (clutchTicks > 6) a.dismount(); return true; }
        if (a.airborne() && clutchTicks < 60) return true;
        if (clutchBoat) {
            // On the boat: break it (a few hits) and pick it back up.
            if (clutchTicks < 3) return true;
            int b = a.recentBoat();
            if (b >= 0 && clutchTicks < 24) {
                if (clutchTicks % 5 == 0) { holdBest(a, "axe"); a.lookAt(clutchAt[0] + 0.5, clutchAt[1], clutchAt[2] + 0.5); a.attack(b); }
                return true;
            }
            a.clearClutch();
            clutchAt = null;
            pickup.start();
            return false;
        }
        // Landed (in the water): scoop it back up.
        if (clutchTicks < 3) return true;
        int bucket = findItem(a, Items.BUCKET);
        if (bucket >= 0) {
            hold(a, bucket);
            a.lookAt(clutchAt[0] + 0.5, clutchAt[1] + 0.2, clutchAt[2] + 0.5);
            a.useItem();
            a.log("picked the clutch water back up");
        }
        a.clearClutch();
        clutchAt = null;
        return false;
    }

    /** Nearest ground point (within reach, 2 blocks around) where a placed boat would fit; null if none. */
    private static double[] boatSpot(dev.agentnet.pathing.BlockSource w, AgentHandle a) {
        double ex = a.x(), ey = a.y() + 1.62, ez = a.z();
        double[] best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            int cx = (int) Math.floor(a.x()) + dx, cz = (int) Math.floor(a.z()) + dz;
            int gy = Integer.MIN_VALUE;
            for (int y = (int) Math.floor(a.y()); y > (int) Math.floor(a.y()) - 6; y--) {
                var s = w.get(cx, y, cz);
                if (s == null) break;
                if (!s.getFluidState().isEmpty()) break;
                if (!Tunnel.passable(s)) { gy = y; break; }
            }
            if (gy == Integer.MIN_VALUE || !Tunnel.solid(w.get(cx, gy, cz))) continue;
            double px = cx + 0.5, py = gy + 1, pz = cz + 0.5;
            // The boat box [p-0.6875, p+0.6875] x [py, py+0.5625]: every cell it overlaps must be open.
            boolean fits = true;
            for (int bx = (int) Math.floor(px - 0.6875); bx <= (int) Math.floor(px + 0.6875) && fits; bx++)
                for (int bz = (int) Math.floor(pz - 0.6875); bz <= (int) Math.floor(pz + 0.6875) && fits; bz++) {
                    var s = w.get(bx, gy + 1, bz);
                    if (s == null || !Tunnel.passable(s)) fits = false;
                }
            if (!fits) continue;
            // Not inside us (the server counts the player too).
            if (a.y() < py + 0.5625 + 0.05 && Math.abs(a.x() - px) < 0.6875 + 0.3 && Math.abs(a.z() - pz) < 0.6875 + 0.3) continue;
            double d = Math.sqrt((px - ex) * (px - ex) + (py - ey) * (py - ey) + (pz - ez) * (pz - ez));
            if (d > 4.3) continue;                     // block reach is 4.5 from the eyes
            double score = Math.abs(dx) + Math.abs(dz) + d * 0.1;
            if (score < bestD) { bestD = score; best = new double[]{px, py, pz}; }
        }
        return best;
    }

    private static int boatSlot(AgentHandle a) {
        List<ItemStack> inv = a.inventory();
        for (int i = 0; i < inv.size(); i++) { String id = Gear.id(inv.get(i)); if (id.endsWith("_boat") || id.endsWith("_raft")) return i; }
        return -1;
    }

    private static void holdBest(AgentHandle a, String type) {
        int i = Gear.bestTool(a.inventory(), type);
        if (i >= 0) hold(a, i);
    }

    private static int findItem(AgentHandle a, net.minecraft.world.item.Item item) {
        List<ItemStack> inv = a.inventory();
        for (int i = 0; i < inv.size() && i < 36; i++) if (inv.get(i).getItem() == item) return i;
        return -1;
    }

    // ---- choosing, moving

    private void pickEnemy(AgentHandle a) {
        Seen best = null;
        long now = System.currentTimeMillis();
        for (Seen s : a.entitiesNear(GUARD_SCAN)) {
            boolean wanted;
            Rescue r = rescues.get(s.id());
            boolean called = r != null && now - r.since() <= RESCUE_MS;
            if (s.player()) wanted = s.distance() <= PLAYER_RANGE && (target != null && s.name().equalsIgnoreCase(target) || a.isEnemy(s.name())
                    || s.name().equalsIgnoreCase(Training.partner)) // train mode: you're fair game too
                    || called && a.isEnemy(s.name());
            else if (s.name().equals("enderman")) wanted = s.angry() && (s.distance() <= MOB_RANGE || called);
            else {
                boolean mob = s.hostile() && !NEUTRAL.contains(s.name());
                wanted = mob && s.distance() <= (s.name().equals("creeper") ? CREEPER_SHOOT + 2 : retreating ? SELF_DEFENSE : MOB_RANGE);
                // Guard duty: a mob going for one of ours, or one somebody called us about.
                if (mob && !wanted) wanted = called || nearOurs(a, s);
            }
            // Retreating (low on supplies): only fight what's right on top of it, don't go hunting
            // (an explicit kill order or the training partner still gets fought).
            boolean ordered = target != null && s.name().equalsIgnoreCase(target) || s.name().equalsIgnoreCase(Training.partner);
            if (wanted && s.player() && retreating && !ordered && s.distance() > SELF_DEFENSE) wanted = false;
            if (!wanted) continue;
            // A hissing creeper first (it's about to blow), then players, then the nearest mob.
            if (best == null || rank(s) > rank(best) || rank(s) == rank(best) && s.distance() < best.distance()) best = s;
        }
        if (best != null && (enemy == null || best.id() != enemy.id())) {
            a.log("§cfighting " + best.name() + String.format(" (%.0f blocks away)", best.distance()));
            hits = 0;
            creeperRun = false;
            chasedTo = null;
        }
        if (best == null && enemy != null && a.isNavigating()) a.stopNavigation();
        enemy = best;
    }

    private static int rank(Seen s) {
        if (s.name().equals("creeper") && s.swelling()) return 3;
        if (s.player()) return 2;
        return 1;
    }

    private void chase(AgentHandle a, int radius) {
        if (--navTicks <= 0 || !a.isNavigating() && chasedTo == null
                || chasedTo != null && chasedTo.distanceTo(enemy.pos()) > 2) {
            navTicks = 10;
            chasedTo = enemy.pos();
            a.navigate((int) Math.floor(enemy.pos().x), (int) Math.floor(enemy.pos().y), (int) Math.floor(enemy.pos().z), radius);
        }
    }

    /** Wear the best armor it has (right-click equips it, or swaps it with the worn piece), shield to the offhand. */
    private boolean equip(AgentHandle a) {
        List<ItemStack> inv = a.inventory();
        List<ItemStack> worn = a.armor();
        for (int slot = 0; slot < 4; slot++) {
            ItemStack on = worn.get(slot);
            if (on.getItem() == net.minecraft.world.item.Items.ELYTRA) continue; // the elytra-mace swaps that one itself
            if (!on.isEmpty() && Gear.level(on, net.minecraft.world.item.enchantment.Enchantments.BINDING_CURSE) > 0) continue; // can't come off
            int i = Gear.bestArmor(inv, ARMOR[slot]);
            if (i < 0) continue;
            if (!on.isEmpty() && Gear.armorScore(inv.get(i)) <= Gear.armorScore(on) + 1) continue; // what it wears is as good
            if (!on.isEmpty()) a.log("swapping its " + on.getHoverName().getString() + " for " + inv.get(i).getHoverName().getString());
            hold(a, i);
            a.useItem();
            return true;
        }
        // A totem belongs in the off hand, before a shield: in the inventory it saves nobody (the_guy7's mace took
        // Mace1 from 18.8 HP in one smash with its totem still in a slot). After one pops, the next goes in.
        if (a.offhand().getItem() != Items.TOTEM_OF_UNDYING) {
            int t = -1;
            for (int i = 0; i < inv.size(); i++) if (inv.get(i).getItem() == Items.TOTEM_OF_UNDYING) { t = i; break; }
            if (t >= 0) { a.log("totem into its off hand"); hold(a, t); a.swapOffhand(); return true; }
        }
        if (a.offhand().isEmpty()) {
            int s = Gear.bestTool(inv, "shield");
            if (s >= 0) { hold(a, s); a.swapOffhand(); return true; }
        }
        return false;
    }

    private static void hold(AgentHandle a, int index) {
        int hb = index < 9 ? index : 8;
        if (index >= 9) a.moveToHotbar(index, hb);
        a.selectHotbar(hb);
    }

    private void checkGear(AgentHandle a) {
        List<ItemStack> worn = a.armor();
        StringBuilder why = new StringBuilder();
        int gapples = War.have(a, "minecraft:golden_apple") + War.have(a, "minecraft:enchanted_golden_apple");
        // (Being out of golden apples is no reason to stop fighting: it asks for more and fights on.)
        for (int slot = 0; slot < 4; slot++) {
            ItemStack s = worn.get(slot);
            if (s.isEmpty()) {
                if (hadArmor[slot]) {
                    hadArmor[slot] = false;
                    a.log("its " + ARMOR[slot] + " broke");
                    request(a, "armor:" + ARMOR[slot] + ":0", 1, true);
                }
                continue;
            }
            hadArmor[slot] = true;
            if (Gear.durability(s) < ARMOR_LOW) why.append(ARMOR[slot]).append(" at ").append(Math.round(Gear.durability(s) * 100)).append("%; ");
        }
        // A broken shield disappears; a disabled one is still in the hand (just on cooldown).
        boolean shieldNow = a.offhand().getItem() == Items.SHIELD || War.have(a, "minecraft:shield") > 0;
        if (hadShield && !shieldNow) { shieldBroke = true; a.log("its shield broke"); request(a, "minecraft:shield", 1, true); }
        if (shieldNow) shieldBroke = false;
        hadShield = shieldNow;
        if (shieldBroke) why.append("broken shield; ");

        boolean now = why.length() > 0;
        if (now != retreating) {
            a.log(now ? "§eretreat mode: " + why.toString().replaceAll("; $", "") : "§aback in action, gear is fine");
        }
        retreating = now;
        retreatWhy = why.toString();
    }

    private void requestSupplies(AgentHandle a) {
        int gapples = War.have(a, "minecraft:golden_apple") + War.have(a, "minecraft:enchanted_golden_apple");
        if (gapples < GAPPLE_MIN) request(a, "minecraft:golden_apple", 64 - gapples, false);
        // XP bottles only repair Mending gear: ask only when a worn Mending piece is worn down.
        if (War.have(a, "minecraft:experience_bottle") == 0 && mendingNeedsXp(a)) request(a, "minecraft:experience_bottle", 64, false);
        if (boatSlot(a) < 0) request(a, "minecraft:oak_boat", 1, false); // clutches in the Nether, enderman traps
        // Weapons: below diamond, ask for a better sword and axe every 5 minutes. The distributor makes
        // the best one the materials around allow (and drops the request if nothing beats what we have).
        long nowMs = System.currentTimeMillis();
        if (nowMs - weaponAskedAt > 5 * 60_000) {
            weaponAskedAt = nowMs;
            int sword = bestTier(a, "sword"), axe = bestTier(a, "axe");
            if (sword < 4) request(a, "weapon:sword:" + sword, 1, true);
            if (axe < 4) request(a, "weapon:axe:" + axe, 1, true);
            // Armor: chestplate first (most protection), any piece missing or below diamond.
            for (int slot : new int[]{1, 2, 0, 3}) {
                int t = armorTier(a, slot);
                if (t < 4) request(a, "armor:" + ARMOR[slot] + ":" + t, 1, true);
            }
        }
        if (findItem(a, Items.WATER_BUCKET) < 0 && findItem(a, Items.BUCKET) < 0) request(a, "minecraft:water_bucket", 1, false);
        if (!War.active()) return;
        for (War.KitItem k : War.kit()) {
            if (k.item().contains("golden_apple") || k.item().equals("minecraft:experience_bottle")) continue;
            int have = War.have(a, k.item());
            if (have == 0 || k.count() > 1 && have < k.count() / 2) request(a, k.item(), k.count() - have, false);
        }
    }

    private long weaponAskedAt;

    /** Best armor for this slot (0 head .. 3 feet), worn or carried: 0 none, 1 leather, 2 gold/chain/copper, 3 iron, 4 diamond, 5 netherite. */
    private static int armorTier(AgentHandle a, int slot) {
        int t = 0;
        List<ItemStack> all = new java.util.ArrayList<>(a.inventory());
        all.add(a.armor().get(slot));
        for (ItemStack s : all) {
            String id = Gear.id(s);
            if (s.isEmpty() || !id.endsWith("_" + ARMOR[slot])) continue;
            t = Math.max(t, id.contains("netherite") ? 5 : id.contains("diamond") ? 4 : id.contains("iron") ? 3 : id.contains("leather") ? 1 : 2);
        }
        return t;
    }

    /** Best tier of this tool type carried: 0 none, 1 wood/gold, 2 stone/copper, 3 iron, 4 diamond, 5 netherite. */
    private static int bestTier(AgentHandle a, String type) {
        int t = 0;
        for (ItemStack s : a.inventory()) {
            if (!type.equals(Gear.toolType(s))) continue;
            String id = Gear.id(s);
            t = Math.max(t, id.contains("netherite") ? 5 : id.contains("diamond") ? 4 : id.contains("iron") ? 3 : id.contains("stone") || id.contains("copper") ? 2 : 1);
        }
        return t;
    }

    /** A worn armor piece under half durability that has Mending (XP would repair it). */
    private static boolean mendingNeedsXp(AgentHandle a) {
        for (ItemStack s : a.armor()) {
            if (s.isEmpty() || Gear.durability(s) >= 0.5) continue;
            if (Gear.level(s, net.minecraft.world.item.enchantment.Enchantments.MENDING) > 0) return true;
        }
        return false;
    }

    /** Ask the nearest distributor, at most once a minute per item (now = skip the wait). */
    private void request(AgentHandle a, String item, int count, boolean now) {
        long t = System.currentTimeMillis();
        Long last = requested.get(item);
        if (!now && last != null && t - last < REQUEST_EVERY_MS) return;
        requested.put(item, t);
        BuilderModule.requestMaterial(a, item, count);
    }

    @Override
    public String status() {
        String s = (retreating ? "RETREAT: " + retreatWhy.replaceAll("; $", "") : "ready") + (lastHp >= 0 ? " HP " + Math.round(lastHp) : "");
        return enemy == null ? s : s + ", " + (tactic.isEmpty() ? "fighting " + enemy.name() : tactic) + String.format(" (%.1f blocks, %d hits)", enemy.distance(), hits);
    }

    boolean retreating() { return retreating; }

    /** Has someone to fight right now. */
    @Override public boolean fighting() { return enemy != null; }
    String retreatWhy() { return retreatWhy; }
}
