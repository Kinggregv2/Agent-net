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
        if (!(elytra.active() && elytra.blocksClutch(a)) && clutch(a)) return;
        // The one thing that may touch the hands mid-move: the next totem into the off hand after one popped. Mace1
        // fell to its death in the middle of an elytra mace with two totems left in its inventory.
        if (elytra.active() && ticks % 4 == 0 && a.offhand().getItem() != Items.TOTEM_OF_UNDYING) {
            List<ItemStack> inv = a.inventory();
            for (int i = 0; i < inv.size(); i++) if (inv.get(i).getItem() == Items.TOTEM_OF_UNDYING) {
                int back = a.selectedSlot();
                hold(a, i); a.swapOffhand();
                if (back >= 0 && back < 9 && back != i) a.selectHotbar(back);
                break;
            }
        }
        if (elytra.active() || cart.active()) { fight(a); return; } // mid-move: nothing else touches the hands
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

    // ------------------------------------------------------------------ fighting

    /** Players on the hitlist (or the kill target) are chased this far; hostile mobs this far. */
    private static final double PLAYER_RANGE = 32, MOB_RANGE = 12;
    /** Server melee reach is 3 blocks from the eyes; stay a little inside it. */
    private static final double HIT_RANGE = 2.9;
    /** Even in retreat mode, anything this close gets fought off. */
    private static final double SELF_DEFENSE = 6;
    private static final float EAT_BELOW = 0.5f, FLEE_BELOW = 0.3f;
    /** Creepers: shoot them from here; closer than SAFE and it backs off first. */
    private static final double CREEPER_SHOOT = 14, CREEPER_SAFE = 6;
    /** A bow is fully drawn after 20 ticks. */
    private static final int BOW_DRAW = 21;
    /** A raised shield only protects after 5 ticks; keep it up at least this long. */
    private static final int SHIELD_MIN = 6;

    /** "Monsters" that only fight back when hit, often as a group: leave them alone (angry endermen excepted). */
    private static final java.util.Set<String> NEUTRAL = java.util.Set.of("zombified_piglin", "enderman", "piglin", "spider_jockey", "piglin_brute");

    private Seen enemy;
    private int cooldown, eatTicks, navTicks, hits;
    private net.minecraft.world.phys.Vec3 chasedTo;
    private int bowTicks = -1;       // drawing a bow: ticks so far (-1 = not drawing)
    private int shieldTicks = -1;    // holding the shield up: ticks so far (-1 = lowered)
    /** Jump for a crit when the next swing is this many ticks away (a jump peaks after ~6). */
    private static final int CRIT_JUMP_AT = 7;
    private int boatCooldown, smashCooldown, potionWait;
    private final ElytraMace elytra = new ElytraMace();
    private final CartPvp cart = new CartPvp();
    private Seen elytraTarget;
    private String tactic = "", lastTactic = "";

    /**
     * Every tick: pick the enemy (the kill target or anyone on the hitlist first, then hostile
     * mobs close by, and endermen only once they're angry; never whitelisted players or other
     * agents), then fight it the way that suits it:
     * <ul>
     *   <li><b>Creepers</b>: never in melee if it can help it. With a bow and arrows it keeps
     *       6-14 blocks away and shoots; without, it hits and steps back, and runs from a
     *       creeper that's started to hiss.</li>
     *   <li><b>Angry endermen</b>: with a boat, it puts the boat down between them; an enderman
     *       that walks into it is stuck (can't teleport out) and gets hit until it dies.</li>
     *   <li><b>Everyone else</b>: melee with the best weapon, raising the shield between hits
     *       and against anyone drawing a bow; switches to an axe to break a raised shield;
     *       with a mace it picks Breach against armored players and smashes from the air
     *       with Density after a wind charge launch. A fleeing enemy gets shot if it has a bow.</li>
     * </ul>
     * Also: eats a golden apple under half health, backs off under 30% with none left.
     */
    /**
     * The target of a move in progress, fresh this tick: looked up by id far out (high in the air it's
     * out of the normal fighting range, and a stale position would send the smash to where it was).
     */
    private static Seen track(AgentHandle a, Seen target, Seen picked) {
        if (target == null) return picked;
        for (Seen s : a.entitiesNear(160)) if (s.id() == target.id()) return s;
        return target;
    }

    private void fight(AgentHandle a) {
        if (cooldown > 0) cooldown--;
        if (boatCooldown > 0) boatCooldown--;
        if (smashCooldown > 0) smashCooldown--;
        if (lungeCooldown > 0) lungeCooldown--;
        if (elytra.cooldown > 0) elytra.cooldown--;
        if (eatTicks > 0) { eatTicks--; return; }
        if (ticks % 5 == 0 || enemy != null) pickEnemy(a);
        // In the middle of an elytra mace: it flies the move out (the last target, if it's out of sight a moment).
        if (elytra.active()) {
            elytraTarget = track(a, elytraTarget, enemy);
            tactic = elytra.tactic;
            if (elytraTarget != null && elytra.tick(a, elytraTarget)) { if (!tactic.equals(lastTactic)) { dev.agentnet.AgentNet.LOG.info("[{}] tactic: {}", a.name(), tactic); lastTactic = tactic; } return; }
        }
        if (cart.cooldown > 0) cart.cooldown--;
        if (cart.active()) {
            elytraTarget = track(a, elytraTarget, enemy);
            tactic = cart.tactic;
            if (elytraTarget != null && cart.tick(a, elytraTarget)) { if (!tactic.equals(lastTactic)) { dev.agentnet.AgentNet.LOG.info("[{}] tactic: {}", a.name(), tactic); lastTactic = tactic; } return; }
        }
        if (potionWait > 0) { potionWait--; return; } // drinking
        // Nothing to fight and nobody calling for help: wait inside the base (the storage room), not out in the open.
        if (enemy == null) { hits = 0; stopBow(a); lowerShield(a); if (!goRescue(a) && rescues.isEmpty()) HomeBase.goHome(a, ticks); return; }

        float hp = a.health(), max = Math.max(1f, a.maxHealth());
        // Potions: healing when hurt, buffs (strength, speed, resistance) as a fight starts.
        if (!a.airborne()) { int w = Potions.fightTick(a, enemy.distance(), false); if (w > 0) { potionWait = w; stopBow(a); lowerShield(a); return; } }
        if (!Float.isNaN(hp) && hp / max < EAT_BELOW && !a.airborne()) {
            stopBow(a);
            lowerShield(a);
            if (eatGapple(a)) return;
        }
        if (!Float.isNaN(hp) && hp / max < FLEE_BELOW && !a.airborne()) { stopBow(a); flee(a, 12); return; }

        if (!tactic.equals(lastTactic)) { dev.agentnet.AgentNet.LOG.info("[{}] tactic: {}", a.name(), tactic); lastTactic = tactic; }
        if (a.airborne()) { airborne(a); return; }
        if (enemy.name().equals("creeper")) { creeper(a); return; }
        if (enemy.name().equals("enderman") && enemy.vehicle() < 0 && boatTrap(a)) return;
        melee(a);
    }

    // ---- creepers

    /** Running from a creeper: keep going until it's at least this far, then turn and shoot. */
    private static final double CREEPER_CLEAR = 12;
    private static final int CREEPER_RUN = 16;
    private boolean creeperRun, shieldLogged;
    private volatile float lastHp = -1;

    private void creeper(AgentHandle a) {
        double d = enemy.distance();
        boolean bow = hasBowAndArrows(a);
        if (enemy.swelling() && d < 7 || d < CREEPER_SAFE && bow) creeperRun = true;
        if (creeperRun && d >= CREEPER_CLEAR) { creeperRun = false; if (a.isNavigating()) a.stopNavigation(); }
        if (creeperRun) {
            stopBow(a);
            tactic = enemy.swelling() ? "running far from a hissing creeper" : "getting distance from a creeper";
            // Caught right next to a hissing one: a raised shield blocks most of the blast.
            if (enemy.swelling() && d < 3.5) raiseShield(a); else lowerShield(a);
            flee(a, CREEPER_RUN);
            return;
        }
        lowerShield(a);
        if (bow) {
            if (d > CREEPER_SHOOT) { tactic = "closing in on a creeper"; chase(a, 10); return; }
            if (a.isNavigating()) a.stopNavigation();
            tactic = "shooting a creeper";
            shoot(a);
            return;
        }
        // No bow: one hit (knockback resets the fuse), then run far before it can follow up.
        tactic = "hitting a creeper, then running";
        if (d > HIT_RANGE) { chase(a, 2); return; }
        swing(a, false);
        creeperRun = true;
    }

    // ---- endermen

    /** Put a boat down between us and an angry enderman. True while busy with that. */
    private boolean boatTrap(AgentHandle a) {
        if (!enemy.angry() || boatCooldown > 0) return false;
        int boat = boatSlot(a);
        if (boat < 0) return false;
        double d = enemy.distance();
        if (d > 7 || d < 2) return false;
        var w = a.openWorld();
        if (w == null) return false;
        // The block on the line toward it, ~1.5 blocks from us, with ground under it.
        double dx = enemy.pos().x - a.x(), dz = enemy.pos().z - a.z(), len = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
        int bx = (int) Math.floor(a.x() + dx / len * 1.6), bz = (int) Math.floor(a.z() + dz / len * 1.6);
        int by = (int) Math.floor(a.y() + 0.2);
        var ground = w.get(bx, by - 1, bz);
        var at = w.get(bx, by, bz);
        if (ground == null || at == null || !Tunnel.solid(ground) || !Tunnel.passable(at)) return false;
        lowerShield(a);
        stopBow(a);
        if (a.isNavigating()) a.stopNavigation();
        hold(a, boat);
        a.lookAt(bx + 0.5, by, bz + 0.5);
        a.useItemOn(bx, by - 1, bz, net.minecraft.core.Direction.UP);
        boatCooldown = 20 * 8;
        tactic = "boat-trapping an enderman";
        a.log("put a boat down to trap " + enemy.name());
        return true;
    }

    private static int boatSlot(AgentHandle a) {
        List<ItemStack> inv = a.inventory();
        for (int i = 0; i < inv.size(); i++) {
            String id = Gear.id(inv.get(i));
            if (id.endsWith("_boat") || id.endsWith("_raft")) return i;
        }
        return -1;
    }

    // ---- melee, shields, axes, maces

    private void melee(AgentHandle a) {
        double d = enemy.distance();
        // Far and running: a few arrows on the way, if it has a bow.
        if (d > 8 && enemy.player() && hasBowAndArrows(a) && d < 24) { lowerShield(a); tactic = "shooting " + enemy.name(); shoot(a); chase(a, 2); return; }
        stopBow(a);
        // Players get the special moves (mobs are fought in melee): a TNT minecart set off with a burning arrow...
        // ...a cart someone of the crew left lying that the enemy is standing at now: just the shot.
        if (enemy.player() && cart.cooldown == 0) {
            net.minecraft.core.BlockPos lying = CartPvp.liveNear(a, enemy);
            if (lying != null) { stopBow(a); lowerShield(a); elytraTarget = enemy; cart.startShot(a, lying); return; }
        }
        if (enemy.player() && d <= 20 && cart.cooldown == 0 && CartPvp.ready(a)) { stopBow(a); lowerShield(a); elytraTarget = enemy; cart.start(a); return; }
        // Elytra mace from a distance: fly up, glide over, drop the elytra, smash from high up.
        float hpNow = a.health(), hpMax = Math.max(1f, a.maxHealth());
        if ((enemy.player() || CombatSettings.aerialOnMobs && d >= 8) && d <= 40 && elytra.cooldown == 0 && (Float.isNaN(hpNow) || hpNow / hpMax > 0.5f) && ElytraMace.ready(a) && ElytraMace.openAbove(a, enemy.pos())) {
            lowerShield(a); elytraTarget = enemy; elytra.start(a); return;
        }
        // Mace + wind charge: launch when a few blocks out, smash on the way down.
        if (enemy.player() && d > 2.5 && d < 7 && smashCooldown == 0 && maceSlot(a, false) >= 0 && windCharge(a) >= 0) { windLaunch(a); return; }
        // Close enough but a block is in the way (a wall, a corner, a floor): no swinging through it, go around.
        boolean walled = d <= HIT_RANGE && !Sight.canHit(a, enemy);
        if (d > HIT_RANGE || walled) {
            tactic = walled ? "getting around the wall to " + enemy.name() : "chasing " + enemy.name();
            if (!walled && lunge(a)) return;
            // Arrows coming: shield up while closing in.
            if (enemy.aiming()) raiseShield(a); else lowerShield(a);
            chase(a, walled ? 0 : 2);
            return;
        }
        if (cooldown > 0) {
            // Critical hit: jump just before the swing is ready; the hit lands on the way down (x1.5).
            if (cooldown <= CRIT_JUMP_AT && !enemy.blocking() && !a.airborne() && !a.riding()) { lowerShield(a); tactic = "jumping for a critical hit"; a.jump(); return; }
            // Between our hits: shield up against theirs.
            tactic = "blocking";
            raiseShield(a);
            return;
        }
        if (shieldTicks >= 0 && shieldTicks < SHIELD_MIN) { shieldTicks++; return; } // let the block finish
        lowerShield(a);
        // Their shield is up: an axe hit disables it for 5 seconds.
        boolean axe = enemy.blocking() && Gear.bestTool(a.inventory(), "axe") >= 0;
        tactic = axe ? "breaking " + enemy.name() + "'s shield" : "hitting " + enemy.name();
        swing(a, axe);
    }

    private void swing(AgentHandle a, boolean axe) {
        List<ItemStack> inv = a.inventory();
        int base = Gear.bestTool(inv, "sword");
        // Attribute swap: switch to the axe (shield-breaking) or the Breach mace (armor-piercing)
        // just for the hit and straight back, all in one tick. The server still has the sword's
        // attack speed and damage loaded, so it's a sword hit with the other item's effect,
        // and the next hit comes on the sword's fast cooldown.
        int swap = -1;
        String how = null;
        if (axe) { swap = Gear.bestTool(inv, "axe"); how = "axe swap"; }
        else if (enemy.player() && enemy.armorCount() >= 2) {
            int m = maceSlot(a, true);
            if (m >= 0 && Gear.level(inv.get(m), net.minecraft.world.item.enchantment.Enchantments.BREACH) > 0) { swap = m; how = "breach swap"; }
        }
        a.lookAt(enemy.pos().x, enemy.pos().y + 1.0, enemy.pos().z);
        if (base >= 0 && base < 9 && swap >= 0 && swap < 9 && swap != base) {
            hold(a, base);
            a.selectHotbar(swap);
            a.attack(enemy.id());
            a.selectHotbar(base);
            hits++;
            cooldown = attackTicks(inv.get(base));
            tactic = how + " on " + enemy.name();
            return;
        }
        if (axe) holdBest(a, "axe"); else holdWeapon(a, enemy);
        a.attack(enemy.id());
        hits++;
        cooldown = attackTicks(a.inventory().get(a.selectedSlot()));
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
        if (enemy != null && cooldown == 0 && enemy.distance() < HIT_RANGE + 2 && maceSlot(a, false) >= 0) return false;
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
        stopBow(a);
        lowerShield(a);
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

    private static int findItem(AgentHandle a, net.minecraft.world.item.Item item) {
        List<ItemStack> inv = a.inventory();
        for (int i = 0; i < inv.size() && i < 36; i++) if (inv.get(i).getItem() == item) return i;
        return -1;
    }

    // ---- lunge: a Lunge spear jab throws us forward; swap to it, jab, swap back to keep chasing

    private int lungeCooldown;

    /** Dash at a target that's getting away (on the ground, a few blocks out). True if it lunged. */
    private boolean lunge(AgentHandle a) {
        double d = enemy.distance();
        if (d < 6 || d > 14) return false;
        if (!lungeToward(a, enemy.pos().x - a.x(), enemy.pos().z - a.z())) return false;
        tactic = "lunge swap toward " + enemy.name();
        return true;
    }

    /** Swap to the Lunge spear, jab along (dx,dz), swap back. Used for chasing and for running away. */
    private boolean lungeToward(AgentHandle a, double dx, double dz) {
        if (lungeCooldown > 0 || a.airborne() || a.riding() || a.food() <= 6) return false; // each jab burns ~1 food per level
        List<ItemStack> inv = a.inventory();
        int spear = -1, lvl = 0;
        for (int i = 0; i < 9; i++) {
            int l = Gear.id(inv.get(i)).endsWith("_spear") ? Gear.level(inv.get(i), net.minecraft.world.item.enchantment.Enchantments.LUNGE) : 0;
            if (l > lvl) { lvl = l; spear = i; }
        }
        if (spear < 0) return false;
        int back = a.selectedSlot();
        a.look((float) Math.toDegrees(Math.atan2(-dx, dz)), 0f);   // the push goes where we look, flat
        a.selectHotbar(spear);
        a.stab();
        a.swingArm();
        a.selectHotbar(back);
        lungeCooldown = 20;
        return true;
    }

    private void raiseShield(AgentHandle a) {
        if (a.offhand().getItem() != net.minecraft.world.item.Items.SHIELD) return;
        if (shieldTicks < 0) { a.useOffhand(); shieldTicks = 0; if (!shieldLogged) { shieldLogged = true; dev.agentnet.AgentNet.LOG.info("[{}] shield up", a.name()); } }
        else shieldTicks++;
    }

    private void lowerShield(AgentHandle a) {
        if (shieldTicks >= 0) { a.releaseUse(); shieldTicks = -1; }
    }

    // ---- bow

    private boolean hasBowAndArrows(AgentHandle a) {
        List<ItemStack> inv = a.inventory();
        boolean bow = false, arrows = false;
        for (ItemStack s : inv) {
            if (s.getItem() == net.minecraft.world.item.Items.BOW) bow = true;
            if (s.getItem() instanceof net.minecraft.world.item.ArrowItem) arrows = true;
        }
        return bow && arrows;
    }

    /** Draw the bow, keep aiming (arrows drop: aim higher the further it is), let go when full. */
    private void shoot(AgentHandle a) {
        double d = enemy.distance();
        double lift = 1.2 + d * d * 0.0045;
        a.lookAt(enemy.pos().x, enemy.pos().y + lift, enemy.pos().z);
        if (bowTicks < 0) {
            int bow = -1;
            List<ItemStack> inv = a.inventory();
            for (int i = 0; i < inv.size(); i++) if (inv.get(i).getItem() == net.minecraft.world.item.Items.BOW) { bow = i; break; }
            if (bow < 0) return;
            hold(a, bow);
            a.useItem();
            bowTicks = 0;
            return;
        }
        if (++bowTicks >= BOW_DRAW) {
            a.releaseUse();
            bowTicks = -1;
            hits++;
        }
    }

    private void stopBow(AgentHandle a) {
        if (bowTicks >= 0) { a.releaseUse(); bowTicks = -1; }
    }

    // ---- mace

    /** Best mace: with breach=true the one with the most Breach (armored targets), else most Density. */
    private static int maceSlot(AgentHandle a, boolean breach) {
        List<ItemStack> inv = a.inventory();
        int best = -1, bestLevel = -1;
        var key = breach ? net.minecraft.world.item.enchantment.Enchantments.BREACH : net.minecraft.world.item.enchantment.Enchantments.DENSITY;
        for (int i = 0; i < inv.size(); i++) {
            if (inv.get(i).getItem() != net.minecraft.world.item.Items.MACE) continue;
            int lvl = Gear.level(inv.get(i), key);
            if (lvl > bestLevel) { bestLevel = lvl; best = i; }
        }
        return best;
    }

    private static int windCharge(AgentHandle a) {
        List<ItemStack> inv = a.inventory();
        for (int i = 0; i < inv.size(); i++) if (inv.get(i).getItem() == net.minecraft.world.item.Items.WIND_CHARGE) return i;
        return -1;
    }

    /** Throw a wind charge straight down: it launches us up; the smash happens on the way down. */
    private void windLaunch(AgentHandle a) {
        lowerShield(a);
        if (a.isNavigating()) a.stopNavigation();
        hold(a, windCharge(a));
        a.look(a.yaw(), 90f);
        a.useItem();
        smashCooldown = 20 * 4;
        tactic = "wind-charge jump at " + enemy.name();
        a.log("§cmace smash at " + enemy.name());
    }

    /** In the air: drift toward the enemy, and hit it on the way down (fall distance = mace damage). */
    private void airborne(AgentHandle a) {
        double dx = enemy.pos().x - a.x(), dz = enemy.pos().z - a.z();
        boolean smash = smashCooldown > 0 && maceSlot(a, false) >= 0;   // up from our own wind charge
        if (!enemy.name().equals("creeper")) a.steer(dx, dz);
        else a.steer(-dx, -dz);
        if (smash) {
            hold(a, maceSlot(a, false));
            if (a.verticalSpeed() < 0 && enemy.distance() <= HIT_RANGE + 0.5 && cooldown == 0 && Sight.canHit(a, enemy)) {
                a.lookAt(enemy.pos().x, enemy.pos().y + 1.0, enemy.pos().z);
                a.attack(enemy.id());
                hits++;
                cooldown = 20;
                a.log("§csmash!");
            }
            return;
        }
        // Lunging or knocked back: a normal hit if it comes in reach (falling = a critical hit).
        // On the way down only: that's a critical hit (x1.5).
        if (enemy.distance() <= HIT_RANGE && cooldown == 0 && a.verticalSpeed() < 0 && !enemy.name().equals("creeper")) {
            swing(a, enemy.blocking() && Gear.bestTool(a.inventory(), "axe") >= 0);
            if (tactic.startsWith("hitting") || tactic.startsWith("jumping")) tactic = "critical hit on " + enemy.name();
        }
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

    /** Sword 1.6 attacks/s -> 13 ticks, axe ~1 -> 20, mace 0.6 -> 34, anything else: a fast jab. */
    private static int attackTicks(ItemStack held) {
        if (held.getItem() == net.minecraft.world.item.Items.MACE) return 34;
        String t = Gear.toolType(held);
        if ("sword".equals(t)) return 13;
        if ("axe".equals(t)) return 20;
        return 6;
    }

    /** Breach mace against an armored player, else sword, else axe, else any mace. */
    private static void holdWeapon(AgentHandle a, Seen enemy) {
        List<ItemStack> inv = a.inventory();
        int i = -1;
        if (enemy != null && enemy.player() && enemy.armorCount() >= 2) {
            int m = maceSlot(a, true);
            if (m >= 0 && Gear.level(inv.get(m), net.minecraft.world.item.enchantment.Enchantments.BREACH) > 0) i = m;
        }
        if (i < 0) i = Gear.bestTool(inv, "sword");
        if (i < 0) i = Gear.bestTool(inv, "axe");
        if (i < 0) i = maceSlot(a, false);
        if (i < 0) return;
        hold(a, i);
    }

    private static void holdBest(AgentHandle a, String type) {
        int i = Gear.bestTool(a.inventory(), type);
        if (i >= 0) hold(a, i);
    }

    private boolean eatGapple(AgentHandle a) {
        List<ItemStack> inv = a.inventory();
        int g = -1;
        for (int i = 0; i < inv.size(); i++) {
            String id = Gear.id(inv.get(i));
            if (id.equals("minecraft:enchanted_golden_apple") || id.equals("minecraft:golden_apple")) { g = i; break; }
        }
        if (g < 0) return false;
        hold(a, g);
        a.useItem(); // the server finishes eating by itself (32 ticks) unless we let go
        eatTicks = 34;
        a.log("eating a golden apple (" + Math.round(a.health()) + " HP)");
        return true;
    }

    /** Run the other way, about this many blocks. */
    private int fleeRetry;

    /** Run away, about this many blocks: straight away from the enemy if there's ground there, else the nearest direction that has some. */
    private void flee(AgentHandle a, int blocks) {
        // Running: a Lunge spear jab away from it is faster than any sprint.
        lungeToward(a, a.x() - enemy.pos().x, a.z() - enemy.pos().z);
        if (a.isNavigating() && chasedTo == null) return;
        if (fleeRetry > 0) { fleeRetry--; return; }
        double away = Math.atan2(a.z() - enemy.pos().z, a.x() - enemy.pos().x);
        var w = a.openWorld();
        int[] goal = null;
        for (int k = 0; k < 9 && goal == null; k++) {
            double ang = away + (k == 0 ? 0 : (k % 2 == 1 ? 1 : -1) * ((k + 1) / 2) * Math.PI / 6); // 0, ±30°, ±60°, ±90°, ±120°
            int gx = (int) Math.floor(a.x() + Math.cos(ang) * blocks), gz = (int) Math.floor(a.z() + Math.sin(ang) * blocks);
            int gy = w == null ? (int) Math.floor(a.y()) : standY(w, gx, (int) Math.floor(a.y()), gz);
            if (gy != Integer.MIN_VALUE) goal = new int[]{gx, gy, gz};
        }
        if (goal == null) { fleeRetry = 10; return; }
        chasedTo = null;
        if (blocks == 12) a.log("§elow on health, backing off from " + enemy.name());
        a.navigate(goal[0], goal[1], goal[2], 3);
        fleeRetry = 5; // a failed path gets a different try soon, not every tick
    }

    /** Feet height of a spot to stand on near y in this column, or MIN_VALUE. */
    private static int standY(dev.agentnet.pathing.BlockSource w, int x, int y, int z) {
        for (int dy = 0; dy <= 6; dy++) {
            for (int s : dy == 0 ? new int[]{0} : new int[]{dy, -dy}) {
                var ground = w.get(x, y + s - 1, z);
                var feet = w.get(x, y + s, z);
                var head = w.get(x, y + s + 1, z);
                if (ground == null || feet == null || head == null) continue;
                if (Tunnel.solid(ground) && Tunnel.passable(feet) && Tunnel.passable(head)) return y + s;
            }
        }
        return Integer.MIN_VALUE;
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
