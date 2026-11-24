package dev.agentnet.role;

import java.util.List;

import dev.agentnet.pathing.BlockSource;
import dev.agentnet.pathing.Rules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Crossbow carting. A TNT minecart goes off the moment a burning arrow hits it, and the faster the arrow the
 * bigger the blast (a crossbow bolt: up to 8.7, TNT is 4). A crossbow can't have Flame, so the bolt is set alight
 * on the way: a fire lit with flint and steel between the carter and the cart.
 *
 * <p>The layout, in one straight line, the carter never more than two blocks from its cart:
 * <pre>
 *   carter (one block up)   fire          rail + cart   enemy
 *   [its own block]         [a block]     ground
 * </pre>
 * The block under the fire is between the carter and the cart: it takes the blast, the carter gets next to
 * nothing. Where the ground already has that shape (a ledge to stand under, a step to stand on) nothing is built;
 * on flat ground the carter puts the two blocks down itself.
 *
 * <p>Double cart: with a Piercing crossbow two carts go on the rail, the bolt goes through the first into the second.
 * Never in rain (the fire and the arrow go out). A bow that does have Flame needs no fire.
 */
final class CartPvp {
    private enum State { OFF, LOAD, APPROACH, BUILD, RAIL, CART, FIRE, AIM, DRAW }

    /** rail: where the cart goes. stand: where its feet are before it steps up. fireBase: the block the fire goes on top of. */
    private record Plan(BlockPos rail, BlockPos stand, BlockPos fireBase, boolean placeBase, boolean pillar) {}

    private State state = State.OFF;
    private int timer, total, drawTicks, pillarFails, relights, lightAt;
    private boolean lit;
    private Plan plan;
    private BlockPos rail;      // the rail block (the cart sits in it)
    private boolean crossbow, twoCarts;
    int cooldown;
    String tactic = "";
    private net.minecraft.world.phys.Vec3 lastEnemy;
    private int lastEnemyId = -1;
    private double evx, evz;    // how the enemy moves, blocks a tick (smoothed)

    boolean active() { return state != State.OFF; }

    // ------------------------------------------------------------------ what it carries

    private static int slotOf(AgentHandle a, java.util.function.Predicate<ItemStack> p) {
        List<ItemStack> inv = a.inventory();
        for (int i = 0; i < inv.size(); i++) if (!inv.get(i).isEmpty() && p.test(inv.get(i))) return i;
        return -1;
    }

    private static boolean flame(ItemStack s) { return Gear.level(s, Enchantments.FLAME) > 0; }
    static int railSlot(AgentHandle a) { return slotOf(a, s -> s.getItem() == Items.RAIL); }
    static int cartSlot(AgentHandle a) { return slotOf(a, s -> s.getItem() == Items.TNT_MINECART); }
    static int flintSlot(AgentHandle a) { return slotOf(a, s -> s.getItem() == Items.FLINT_AND_STEEL); }
    /** Its crossbow: one with Flame first (a command-made one), else any. */
    static int crossbowSlot(AgentHandle a) {
        int f = slotOf(a, s -> s.getItem() == Items.CROSSBOW && flame(s));
        return f >= 0 ? f : slotOf(a, s -> s.getItem() == Items.CROSSBOW);
    }
    static int flameBow(AgentHandle a) { return slotOf(a, s -> s.getItem() == Items.BOW && flame(s)); }
    private static boolean arrows(AgentHandle a) { return slotOf(a, s -> s.getItem() == Items.ARROW || s.getItem() == Items.SPECTRAL_ARROW || s.getItem() == Items.TIPPED_ARROW) >= 0; }
    private static boolean block(ItemStack s) {
        String id = Gear.id(s);
        return id.equals("minecraft:cobblestone") || id.equals("minecraft:cobbled_deepslate") || id.equals("minecraft:dirt")
                || id.equals("minecraft:netherrack") || id.equals("minecraft:stone") || id.equals("minecraft:obsidian");
    }
    private static int blockSlot(AgentHandle a) { return slotOf(a, CartPvp::block); }
    private static int blocks(AgentHandle a) { int n = 0; for (ItemStack s : a.inventory()) if (block(s)) n += s.getCount(); return n; }
    private static int cartCount(AgentHandle a) { int n = 0; for (ItemStack s : a.inventory()) if (s.getItem() == Items.TNT_MINECART) n += s.getCount(); return n; }

    private static boolean charged(ItemStack s) {
        var c = s.get(DataComponents.CHARGED_PROJECTILES);
        return c != null && !c.isEmpty();
    }

    /** The weapon it will shoot with needs no fire on the way (it has Flame). */
    private boolean flameWeapon(AgentHandle a) {
        int x = crossbow ? crossbowSlot(a) : flameBow(a);
        return x >= 0 && flame(a.inventory().get(x));
    }

    /** Everything for one cart: rail, TNT minecart, a crossbow (loaded, or arrows to load it) and flint and steel; or a Flame bow. Not in rain. */
    static boolean ready(AgentHandle a) {
        if (a.raining()) return false; // rain puts the fire and a burning arrow out: the cart would just sit there
        if (railSlot(a) < 0 || cartSlot(a) < 0) return false;
        int x = crossbowSlot(a);
        if (x >= 0 && (charged(a.inventory().get(x)) || arrows(a)) && (flame(a.inventory().get(x)) || flintSlot(a) >= 0)) return true;
        return flameBow(a) >= 0 && arrows(a);
    }

    /** Carts left lying are not reused any more (each needs its own fire in line); kept so the warrior's check still compiles. */
    static BlockPos liveNear(AgentHandle a, Seen enemy) { return null; }
    void startShot(AgentHandle a, BlockPos cart) { }

    void start(AgentHandle a) {
        int x = crossbowSlot(a);
        crossbow = x >= 0 && (flame(a.inventory().get(x)) || flintSlot(a) >= 0);
        state = crossbow && !charged(a.inventory().get(x)) ? State.LOAD : State.APPROACH;
        timer = 0;
        total = 0;
        rail = null;
        plan = null;
        relights = 0;
        lightAt = 0;
        a.log("§ccart: " + (crossbow ? "crossbow" : "bow") + " and a TNT minecart");
    }

    // ------------------------------------------------------------------ the move

    /** One tick. False when it's over (done or given up): the warrior takes the fight back. */
    boolean tick(AgentHandle a, Seen enemy) {
        if (state == State.OFF) return false;
        timer++;
        if (++total > 20 * 40) return abort(a, "took too long");
        BlockSource w = a.openWorld();
        if (w == null) return abort(a, "no world data");
        if (a.raining()) return abort(a, "it started raining: the fire and the arrow go out");
        if (lastEnemy != null && lastEnemyId == enemy.id()) {
            double mx = enemy.pos().x - lastEnemy.x, mz = enemy.pos().z - lastEnemy.z;
            if (mx * mx + mz * mz < 4) { evx += (mx - evx) * 0.25; evz += (mz - evz) * 0.25; }
        } else { evx = 0; evz = 0; }
        lastEnemy = enemy.pos();
        lastEnemyId = enemy.id();
        switch (state) {
            case LOAD -> {
                tactic = "loading the crossbow";
                int x = crossbowSlot(a);
                if (x < 0) return abort(a, "lost the crossbow");
                if (charged(a.inventory().get(x))) { go(State.APPROACH); return true; }
                if (timer == 1) { hold(a, x); return true; }
                if (timer == 2) { a.useItem(); return true; }
                // 25 ticks to pull (Quick Charge takes 5 off per level); letting go loads it.
                int need = 27 - 5 * Gear.level(a.inventory().get(x), Enchantments.QUICK_CHARGE);
                if (timer == Math.max(8, need)) a.releaseUse();
                if (timer > need + 15) { timer = 0; } // didn't load: try again
            }
            case APPROACH -> {
                tactic = "getting into place next to " + enemy.name() + " for the cart";
                // One plan, kept until the enemy has moved away from where its cart would go.
                if (plan != null && plan.rail().distToCenterSqr(enemy.pos().x, enemy.pos().y, enemy.pos().z) > 3.5 * 3.5) plan = null;
                if (plan == null && timer % 5 == 1) {
                    plan = plan(a, w, enemy.pos().x + evx * 10, enemy.pos().y, enemy.pos().z + evz * 10, blocks(a) >= 2, enemy.pos().x, enemy.pos().z, System.currentTimeMillis() < bannedUntil ? banned : null);
                    if (plan != null) { a.stopNavigation(); a.navigate(plan.stand().getX(), plan.stand().getY(), plan.stand().getZ(), 0); } // drop whatever chase it was on
                    if (plan != null) a.log("§7cart: standing at " + plan.stand().toShortString() + (plan.pillar() ? " (one block up, on its own blocks)" : " (the ground gives the cover)")
                            + ", fire on " + plan.fireBase().toShortString() + ", cart at " + plan.rail().toShortString());
                }
                if (plan == null) {
                    if (timer > 20 * 5) return abort(a, "nowhere to cart " + enemy.name() + " from" + (blocks(a) < 2 ? " (and under two blocks to build cover with)" : ""));
                    if (!a.isNavigating() && timer % 10 == 1) a.navigate((int) Math.floor(enemy.pos().x), (int) Math.floor(enemy.pos().y), (int) Math.floor(enemy.pos().z), 4);
                    return true;
                }
                BlockPos s = plan.stand();
                boolean there = (int) Math.floor(a.x()) == s.getX() && (int) Math.floor(a.z()) == s.getZ() && Math.abs(a.y() - s.getY()) < 0.6;
                if (there) { if (a.isNavigating()) a.stopNavigation(); rail = plan.rail(); pillarFails = 0; go(plan.placeBase() || plan.pillar() ? State.BUILD : State.RAIL); return true; }
                if (!a.isNavigating() && timer % 5 == 0 || timer % 30 == 0) a.navigate(s.getX(), s.getY(), s.getZ(), 0);
                // Rubble, a crater, no way round: don't stand there trying.
                // ...and don't pick that same spot again for a while.
                if (timer > 20 * 4) { banned = plan.stand(); bannedUntil = System.currentTimeMillis() + 20_000; plan = null; cooldown = 20; state = State.OFF; a.log("§ecart: couldn't get to its spot at " + banned.toShortString() + ": another one next time"); return false; }
            }
            case BUILD -> {
                tactic = "making its own cover next to " + enemy.name();
                // Square in the middle of its block first: on the edge its own body is in the way of the block it
                // wants to put down next to it (a blast had pushed it half a block over, and it tried for half a minute).
                double offX = plan.stand().getX() + 0.5 - a.x(), offZ = plan.stand().getZ() + 0.5 - a.z();
                if (a.y() < plan.stand().getY() + 0.5 && (Math.abs(offX) > 0.15 || Math.abs(offZ) > 0.15)) {
                    if (a.isNavigating()) a.stopNavigation();
                    a.stepToward(plan.stand().getX() + 0.5, plan.stand().getZ() + 0.5);
                    if (timer > 40) { plan = null; go(State.APPROACH); }
                    return true;
                }
                BlockPos fb = plan.fireBase();
                BlockState base = w.get(fb.getX(), fb.getY(), fb.getZ());
                boolean baseThere = base != null && !Rules.canWalkThrough(base);
                if (plan.placeBase() && !baseThere) {
                    // The block between it and the cart: the fire goes on top of it, and it takes the blast.
                    int b = blockSlot(a);
                    if (b < 0) return abort(a, "out of blocks for its cover");
                    if (timer % 6 == 1) { hold(a, b); a.lookAt(fb.getX() + 0.5, fb.getY(), fb.getZ() + 0.5); return true; }
                    if (timer % 6 == 2) { a.useItemOn(fb.getX(), fb.getY() - 1, fb.getZ(), Direction.UP); return true; }
                    // Something is standing where the block goes (the enemy walked in): plan again from here.
                    if (timer > 14) {
                        BlockState under = w.get(fb.getX(), fb.getY() - 1, fb.getZ());
                        a.log(String.format("§7cart: the cover block won't go down at %s (there: %s, under it: %s, holding %s, me %.1f %.1f %.1f, %s %.1f %.1f %.1f): picking another spot",
                                fb.toShortString(), base == null ? "?" : base.getBlock().getName().getString(), under == null ? "?" : under.getBlock().getName().getString(),
                                a.inventory().get(a.selectedSlot()).getHoverName().getString(), a.x(), a.y(), a.z(), enemy.name(), enemy.pos().x, enemy.pos().y, enemy.pos().z));
                        plan = null; go(State.APPROACH); return true;
                    }
                    return true;
                }
                if (plan.pillar() && a.y() < plan.stand().getY() + 0.9) {
                    if (timer % 6 == 0 && !a.airborne() && !a.pillarUp() && ++pillarFails > 6) return abort(a, "couldn't step up on its own block");
                    if (timer > 20 * 6) return abort(a, "building its cover took too long");
                    return true;
                }
                go(State.RAIL);
            }
            case RAIL -> {
                tactic = "rail down";
                int r = railSlot(a);
                if (r < 0) return abort(a, "out of rails");
                if (timer == 1) { hold(a, r); a.lookAt(rail.getX() + 0.5, rail.getY(), rail.getZ() + 0.5); return true; }
                if (timer == 2) { a.useItemOn(rail.getX(), rail.getY() - 1, rail.getZ(), Direction.UP); return true; }
                BlockState s = w.get(rail.getX(), rail.getY(), rail.getZ());
                if (s != null && s.is(net.minecraft.tags.BlockTags.RAILS)) { go(State.CART); return true; }
                if (timer > 12) return abort(a, "the rail didn't go down");
            }
            case CART -> {
                tactic = "cart on the rail";
                int c = cartSlot(a);
                if (c < 0) return abort(a, "out of TNT minecarts");
                if (timer == 1) { hold(a, c); a.lookAt(rail.getX() + 0.5, rail.getY() + 0.1, rail.getZ() + 0.5); return true; }
                if (timer == 2) {
                    a.useItemOn(rail.getX(), rail.getY(), rail.getZ(), Direction.UP);
                    // Double cart: a Piercing bolt goes through the first cart into the second, and both go off.
                    // For one case only: a block between where the cart is and the target's feet. One cart would
                    // spend itself on that block; the first of two takes it away and the second reaches the target.
                    int x = crossbowSlot(a);
                    double cx = rail.getX() + 0.5, cy = rail.getY() + 0.4, cz = rail.getZ() + 0.5;
                    boolean feetShielded = !Sight.clear(w, cx, cy, cz, enemy.pos().x, enemy.pos().y + 0.1, enemy.pos().z)
                            && !Sight.clear(w, cx, cy, cz, enemy.pos().x, enemy.pos().y + 0.45, enemy.pos().z);
                    twoCarts = feetShielded && crossbow && x >= 0 && Gear.level(a.inventory().get(x), Enchantments.PIERCING) > 0 && cartCount(a) >= 2;
                    if (twoCarts) a.log("§7cart: a block shields " + enemy.name() + "'s feet from the cart: two carts");
                    return true;
                }
                if (twoCarts && timer == 4) { int c2 = cartSlot(a); if (c2 >= 0) { hold(a, c2); a.lookAt(rail.getX() + 0.5, rail.getY() + 0.1, rail.getZ() + 0.5); } return true; }
                if (twoCarts && timer == 5) { a.useItemOn(rail.getX(), rail.getY(), rail.getZ(), Direction.UP); return true; }
                if (timer >= (twoCarts ? 7 : 4)) { a.log("§c" + (twoCarts ? "two carts" : "cart") + " placed at " + rail.toShortString()); go(State.FIRE); }
            }
            case FIRE -> {
                // Wait for the enemy to be at the cart; a few seconds at most, a near miss is still worth it.
                double flight = Math.sqrt(rail.distToCenterSqr(a.x(), a.y() + 1.5, a.z())) / (crossbow ? 3.15 : 1.25) + 4;
                double enemyToCart = Math.sqrt(rail.distToCenterSqr(enemy.pos().x + evx * flight, enemy.pos().y, enemy.pos().z + evz * flight));
                tactic = String.format("cart ready (%s is %.0f blocks from it)", enemy.name(), enemyToCart);
                if (friendNear(a, rail)) { tactic = "holding the shot: one of ours is at the cart"; if (timer > 20 * 8) return abort(a, "one of ours stayed at the cart"); return true; }
                boolean now = enemyToCart <= 3.5 || timer > 20 * 4 && enemyToCart <= 6;
                if (!now) { if (timer > 20 * 8) return abort(a, enemy.name() + " left the cart"); return true; }
                if (flameWeapon(a)) { go(State.AIM); return true; }
                // Flint and steel on the block between us and the cart: the bolt catches fire going through it.
                BlockPos fb = plan == null ? null : plan.fireBase();
                int f = flintSlot(a);
                if (fb == null || f < 0) return abort(a, "no flint and steel for the fire");
                BlockState fire = w.get(fb.getX(), fb.getY() + 1, fb.getZ());
                if (fire != null && fire.is(net.minecraft.tags.BlockTags.FIRE)) { go(State.AIM); return true; }
                if (lightAt == 0 || timer - lightAt > 8) { hold(a, f); a.lookAt(fb.getX() + 0.5, fb.getY() + 1.0, fb.getZ() + 0.5); lightAt = timer; lit = false; return true; }
                if (!lit) { a.useItemOn(fb.getX(), fb.getY(), fb.getZ(), Direction.UP); lit = true; return true; }
                if (timer > 20 * 6) return abort(a, "the fire wouldn't light");
            }
            case AIM -> {
                tactic = "shooting the cart";
                int x = crossbow ? crossbowSlot(a) : flameBow(a);
                if (x < 0) return abort(a, "lost its " + (crossbow ? "crossbow" : "bow"));
                if (timer == 1) { hold(a, x); aim(a); return true; }
                aim(a);
                // The fire went out before the shot (it doesn't last on stone): light it again.
                if (!flameWeapon(a) && plan != null) {
                    BlockState fire = w.get(plan.fireBase().getX(), plan.fireBase().getY() + 1, plan.fireBase().getZ());
                    if (fire == null || !fire.is(net.minecraft.tags.BlockTags.FIRE)) {
                        if (++relights > 4) return abort(a, "the fire keeps going out");
                        lightAt = 0; go(State.FIRE); return true;
                    }
                }
                if (!sees(a, w)) { if (timer > 20 * 3) return abort(a, "can't see its cart from here"); return true; }
                if (timer < 3) return true;
                if (crossbow) {
                    a.useItem(); // loaded: fires at once
                    a.log(String.format("§ccart: shot at the %s%s, %s %.0f blocks from it", twoCarts ? "two carts" : "cart", flameWeapon(a) ? "" : " through the fire", enemy.name(),
                            Math.sqrt(rail.distToCenterSqr(enemy.pos().x, enemy.pos().y, enemy.pos().z))));
                    return done(a);
                }
                a.useItem(); // start drawing the bow
                drawTicks = 0;
                go(State.DRAW);
            }
            case DRAW -> {
                aim(a);
                if (++drawTicks >= 10) { // half drawn: fast enough to fly straight, a smaller blast
                    a.releaseUse();
                    a.log("§ccart: shot at the cart with the bow");
                    return done(a);
                }
            }
            default -> {}
        }
        return true;
    }

    /** A crew member (an agent, a friendly player) within the blast's worst part. You, watching in creative or spectator, don't count. */
    private static boolean friendNear(AgentHandle a, BlockPos cart) {
        var me = net.minecraft.client.Minecraft.getInstance().player;
        for (Seen s : a.entitiesNear(40)) {
            if (!s.player() || a.isEnemy(s.name()) || s.name().equalsIgnoreCase(a.name())) continue;
            if (me != null && me.getName().getString().equalsIgnoreCase(s.name()) && (me.isCreative() || me.isSpectator())) continue; // can't be hurt
            if (cart.distToCenterSqr(s.pos().x, s.pos().y, s.pos().z) <= 5 * 5) return true;
        }
        return false;
    }

    private static boolean solid(BlockSource w, int x, int y, int z) { BlockState s = w.get(x, y, z); return s != null && Rules.canStandOn(s) && s.getFluidState().isEmpty(); }
    private static boolean open(BlockSource w, int x, int y, int z) { BlockState s = w.get(x, y, z); return s != null && (s.isAir() || Rules.canWalkThrough(s)); }

    /**
     * Where to cart from, for an enemy at (ex, ey, ez): a rail spot within two blocks of it, and in a straight line
     * from there the block for the fire and, two blocks from the rail, where the carter stands. Three shapes:
     * under a ledge the cart is on; up on a step with a block in front; or flat ground, where it builds the step
     * and the block itself (needs two blocks). The one that needs no building first, then the nearest. Null if none.
     */
    private BlockPos banned;
    private long bannedUntil;

    private static Plan plan(AgentHandle a, BlockSource w, double ex, double ey, double ez, boolean canBuild, double nowX, double nowZ, BlockPos banned) {
        int cx = (int) Math.floor(ex), cy = (int) Math.floor(ey + 0.2), cz = (int) Math.floor(ez);
        Plan best = null;
        double bestScore = Double.MAX_VALUE;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = 1; dy >= -1; dy--) {
            if (dx == 0 && dz == 0) continue;
            int rx = cx + dx, ry = cy + dy, rz = cz + dz;
            if (!open(w, rx, ry + 1, rz) || !solid(w, rx, ry - 1, rz) || a.spawnProtected(rx, rz)) continue;
            BlockState at = w.get(rx, ry, rz);
            if (at == null || !at.isAir()) continue;
            if (Math.hypot(rx + 0.5 - ex, rz + 0.5 - ez) > 2.6) continue;
            for (int[] d : dirs) {
                int mx = rx + d[0], mz = rz + d[1], sx = rx + 2 * d[0], sz = rz + 2 * d[1];
                if (mx == cx && mz == cz || sx == cx && sz == cz) continue; // the enemy is standing there
                if (Math.abs(mx + 0.5 - nowX) < 0.85 && Math.abs(mz + 0.5 - nowZ) < 0.85 || Math.abs(sx + 0.5 - nowX) < 0.85 && Math.abs(sz + 0.5 - nowZ) < 0.85
                        || Math.abs(rx + 0.5 - nowX) < 0.85 && Math.abs(rz + 0.5 - nowZ) < 0.85) continue; // or is right now
                Plan p = null;
                // Under a ledge: the cart and the fire up on it, the carter one block lower in front of it.
                if (open(w, mx, ry, mz) && open(w, mx, ry + 1, mz) && solid(w, mx, ry - 1, mz)
                        && open(w, sx, ry - 1, sz) && open(w, sx, ry, sz) && solid(w, sx, ry - 2, sz))
                    p = new Plan(new BlockPos(rx, ry, rz), new BlockPos(sx, ry - 1, sz), new BlockPos(mx, ry - 1, mz), false, false);
                // Up on a step that is already there, a block in front of it for the fire.
                else if (solid(w, mx, ry, mz) && open(w, mx, ry + 1, mz) && open(w, mx, ry + 2, mz)
                        && solid(w, sx, ry, sz) && open(w, sx, ry + 1, sz) && open(w, sx, ry + 2, sz))
                    p = new Plan(new BlockPos(rx, ry, rz), new BlockPos(sx, ry + 1, sz), new BlockPos(mx, ry, mz), false, false);
                // Flat ground: build the block for the fire and step up on a second one.
                else if (canBuild && open(w, mx, ry, mz) && open(w, mx, ry + 1, mz) && solid(w, mx, ry - 1, mz)
                        && open(w, sx, ry, sz) && open(w, sx, ry + 1, sz) && open(w, sx, ry + 2, sz) && solid(w, sx, ry - 1, sz) && !a.spawnProtected(sx, sz))
                    p = new Plan(new BlockPos(rx, ry, rz), new BlockPos(sx, ry, sz), new BlockPos(mx, ry, mz), true, true);
                if (p == null) continue;
                if (banned != null && banned.getX() == sx && banned.getZ() == sz) continue; // it could not get there a moment ago
                // Cover the ground happens to give is only used when it really covers: measured 7.8 hearts-points from a
                // crater edge against 0.1 from behind its own block. Not good enough: build instead, if it can.
                if (!p.pillar() && exposure(w, sx + 0.5, p.stand().getY(), sz + 0.5, p.rail()) > 0.25) {
                    if (!(canBuild && open(w, mx, ry, mz) && open(w, mx, ry + 1, mz) && solid(w, mx, ry - 1, mz)
                            && open(w, sx, ry, sz) && open(w, sx, ry + 1, sz) && open(w, sx, ry + 2, sz) && solid(w, sx, ry - 1, sz) && !a.spawnProtected(sx, sz))) continue;
                    p = new Plan(new BlockPos(rx, ry, rz), new BlockPos(sx, ry, sz), new BlockPos(mx, ry, mz), true, true);
                }
                double walk = Math.abs(sx + 0.5 - a.x()) + Math.abs(sz + 0.5 - a.z()) + Math.abs(p.stand().getY() - a.y()) * 2;
                double score = walk + Math.hypot(rx + 0.5 - ex, rz + 0.5 - ez) * 3 + (p.pillar() ? 4 : 0);
                // The cart between us and the enemy: the blast throws it away from us.
                if (Math.hypot(sx + 0.5 - ex, sz + 0.5 - ez) < Math.hypot(rx + 0.5 - ex, rz + 0.5 - ez)) score += 20;
                if (score < bestScore) { bestScore = score; best = p; }
            }
        }
        return best;
    }

    /**
     * How much of a body standing at feet (x, y, z) the blast of a cart on this rail reaches, the way the game counts
     * it: straight lines from the cart to a grid of points over the body. 0 = fully shielded, 1 = fully exposed.
     */
    private static double exposure(BlockSource w, double x, double y, double z, BlockPos rail) {
        double cx = rail.getX() + 0.5, cy = rail.getY() + 0.4, cz = rail.getZ() + 0.5;
        int all = 0, hit = 0;
        for (int i = 0; i <= 2; i++) for (int k = 0; k <= 2; k++) for (int j = 0; j <= 4; j++) {
            all++;
            if (Sight.clear(w, cx, cy, cz, x - 0.3 + 0.3 * i, y + 1.8 * j / 4.0, z - 0.3 + 0.3 * k)) hit++;
        }
        return (double) hit / all;
    }

    private boolean done(AgentHandle a) {
        state = State.OFF;
        plan = null;
        lightAt = 0;
        cooldown = 6; // the next cart right away: the crossbow is reloaded on the way
        return false;
    }

    private boolean abort(AgentHandle a, String why) {
        a.log("§ecart: " + why);
        if (state == State.DRAW) a.releaseUse();
        state = State.OFF;
        plan = null;
        lightAt = 0;
        cooldown = 20 * 6;
        return false;
    }

    private void go(State s) { state = s; timer = 0; }

    /** Look at the cart, a little high for the arrow's drop over the distance. */
    private void aim(AgentHandle a) {
        double d = Math.sqrt(rail.distToCenterSqr(a.x(), a.y() + 1.5, a.z()));
        double speed = crossbow ? 3.15 : 1.25;
        double t = d / speed;
        double lift = 0.025 * t * t;
        double[] t3 = target(a);
        a.lookAt(t3[0], t3[1] + lift, t3[2]);
    }

    /** Where on the cart to shoot: its middle; from above, the far top of it (clear of the block in front of us). */
    private double[] target(AgentHandle a) {
        double cx = rail.getX() + 0.5, cz = rail.getZ() + 0.5;
        if (a.y() > rail.getY() + 0.5) {
            double dx = cx - a.x(), dz = cz - a.z(), len = Math.max(0.01, Math.hypot(dx, dz));
            return new double[]{cx + dx / len * 0.3, rail.getY() + 0.6, cz + dz / len * 0.3};
        }
        return new double[]{cx, rail.getY() + 0.35, cz};
    }

    /** Nothing solid between our eyes and the cart (fire is not in the way: the bolt is meant to go through it). */
    private boolean sees(AgentHandle a, BlockSource w) {
        double ex = a.x(), ey = a.y() + 1.5, ez = a.z();
        double[] t3 = target(a);
        double tx = t3[0], ty = t3[1], tz = t3[2];
        double d = Math.sqrt((tx - ex) * (tx - ex) + (ty - ey) * (ty - ey) + (tz - ez) * (tz - ez));
        int steps = (int) (d * 4);
        for (int i = 2; i < steps - 1; i++) {
            double f = (double) i / steps;
            int x = (int) Math.floor(ex + (tx - ex) * f), y = (int) Math.floor(ey + (ty - ey) * f), z = (int) Math.floor(ez + (tz - ez) * f);
            if (x == rail.getX() && y == rail.getY() && z == rail.getZ()) continue;
            BlockState s = w.get(x, y, z);
            if (s == null || s.is(net.minecraft.tags.BlockTags.FIRE)) continue;
            if (!s.isAir() && !Rules.canWalkThrough(s)) return false;
        }
        return true;
    }

    private static void hold(AgentHandle a, int i) {
        if (i >= 9) { a.moveToHotbar(i, 8); i = 8; }
        a.selectHotbar(i);
    }
}
