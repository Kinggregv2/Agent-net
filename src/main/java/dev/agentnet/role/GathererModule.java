package dev.agentnet.role;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.agentnet.bus.AgentBus;
import dev.agentnet.bus.Message;
import net.minecraft.world.item.ItemStack;

/**
 * Shared base for roles that fill their inventory (farmer, miner, fisher):
 * <ul>
 *   <li>Over 50% full: ask the nearest distributor to come collect.</li>
 *   <li>When the distributor arrives (COLLECT_DROP), throw it everything except the
 *       job items ({@link #keepSlots}), then say DROP_DONE.</li>
 *   <li>Hungry: eat food from the inventory, or ask a distributor for some.</li>
 * </ul>
 * Subclasses add their own work loop in {@link #work}.
 */
abstract class GathererModule implements RoleModule {
    /** Working for itself (the speedrunner's own mining): nothing is handed to distributors or the builder. */
    boolean keepsLoad;
    static final double ALERT_AT = 0.5;
    /** Re-send if nobody came after this long. */
    private static final long RESEND_MS = 5 * 60_000;
    /** Only look at the inventory once a second. */
    private static final int CHECK_EVERY_TICKS = 20;
    private static final int HUNGRY_BELOW = 14;
    private static final int EAT_TICKS = 40;
    private static final long FOOD_REQUEST_MS = 3 * 60_000;
    private static final int DROP_TIMEOUT = 15 * 20;

    private boolean alerted;
    private long alertedAt;
    private int ticks;

    private String dropFor;
    private int dropTicks;
    private int eatTicks;
    private long foodRequestedAt;
    private long toolRequestedAt;

    @Override
    public final void tick(AgentHandle a) {
        ticks++;
        if (dropFor != null) { dropStep(a); return; }
        if (eatTicks > 0) { eatTicks--; return; }
        if (pickup.tick(a)) return;
        if (ticks % CHECK_EVERY_TICKS == 0) {
            checkInventory(a);
            if (checkHunger(a)) return;
        }
        work(a);
    }

    /** The role's own behavior, every tick. */
    protected void work(AgentHandle a) {}

    /**
     * Inventory slots (0-35) to keep when handing everything to a distributor.
     * Default: the best tool of each kind and one stack of food.
     */
    protected Set<Integer> keepSlots(AgentHandle a) {
        List<ItemStack> inv = a.inventory();
        Set<Integer> keep = new HashSet<>();
        for (String t : new String[]{"pickaxe", "axe", "hoe", "shovel", "sword", "rod"}) {
            int i = Gear.bestTool(inv, t);
            if (i >= 0) keep.add(i);
        }
        keepPickKit(inv, keep);
        keepFood(inv, keep, Set.of());
        return keep;
    }

    /**
     * Never hand over the means to make a new pickaxe: a second pickaxe, a stack of cobblestone, the sticks, some wood
     * and a crafting table. Miners gave all of that to the distributor with their load, and when the one pickaxe broke
     * deep underground they were stuck there for good: most of the crew's supply trouble.
     */
    static void keepPickKit(List<ItemStack> inv, Set<Integer> keep) {
        int picks = 0;
        boolean cobble = false, sticks = false, wood = false, table = false;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (s.isEmpty() || keep.contains(i)) continue;
            String id = Gear.id(s);
            if (id.endsWith("_pickaxe") && picks < 1) { keep.add(i); picks++; }
            else if (!cobble && (id.equals("minecraft:cobblestone") || id.equals("minecraft:cobbled_deepslate"))) { keep.add(i); cobble = true; }
            else if (!sticks && id.equals("minecraft:stick")) { keep.add(i); sticks = true; }
            else if (!wood && (id.endsWith("_planks") || id.endsWith("_log") && !id.contains("stripped"))) { keep.add(i); wood = true; }
            else if (!table && id.equals("minecraft:crafting_table")) { keep.add(i); table = true; }
        }
    }

    /** Keep the best stack of food that isn't one of this job's own products. */
    static void keepFood(List<ItemStack> inv, Set<Integer> keep, Set<String> products) {
        int best = -1;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (!Gear.isFood(s) || products.contains(Gear.id(s))) continue;
            if (best < 0 || Gear.nutrition(s) * s.getCount() > Gear.nutrition(inv.get(best)) * inv.get(best).getCount()) best = i;
        }
        if (best >= 0) keep.add(best);
    }

    /** Ask the nearest distributor for a tool this job needs (at most once every 3 minutes). */
    protected void requestTool(AgentHandle a, String item) {
        long now = System.currentTimeMillis();
        if (now - toolRequestedAt < FOOD_REQUEST_MS) return;
        toolRequestedAt = now;
        BuilderModule.requestMaterial(a, item, 1);
    }

    private final Pickup pickup = new Pickup();

    @Override
    public void onMessage(AgentHandle a, Message m) {
        if (m.type() == Message.Type.COLLECT_DROP && !keepsLoad) {
            dropFor = m.from();
            dropTicks = 0;
        }
        if (m.type() == Message.Type.ITEM_DELIVERED) pickup.start();
    }

    /** One stack every 3 ticks, thrown toward the distributor. */
    private void dropStep(AgentHandle a) {
        if (++dropTicks > DROP_TIMEOUT) { finishDrop(a); return; }
        if (dropTicks % 3 != 0) return;
        AgentHandle d = find(dropFor);
        if (d == null) { dropFor = null; return; }
        Set<Integer> keep = keepSlots(a);
        List<ItemStack> inv = a.inventory();
        for (int i = 0; i < inv.size(); i++) {
            if (inv.get(i).isEmpty() || keep.contains(i)) continue;
            a.lookAt(d.x(), d.y() + 0.5, d.z());
            a.dropStack(i);
            return;
        }
        finishDrop(a);
    }

    private void finishDrop(AgentHandle a) {
        AgentBus.send(Message.of(a.name(), dropFor, Message.Type.DROP_DONE, "here you go", Map.of()));
        a.log("handed its harvest to " + dropFor);
        dropFor = null;
        alerted = false;
    }

    /** Eat if hungry. True while starting to eat (skip work this tick). */
    private boolean checkHunger(AgentHandle a) {
        if (a.food() >= HUNGRY_BELOW) return false;
        List<ItemStack> inv = a.inventory();
        int best = -1;
        for (int i = 0; i < inv.size(); i++) {
            if (Gear.isFood(inv.get(i)) && (best < 0 || Gear.nutrition(inv.get(i)) > Gear.nutrition(inv.get(best)))) best = i;
        }
        if (best < 0) {
            long now = System.currentTimeMillis();
            if (now - foodRequestedAt > FOOD_REQUEST_MS) {
                foodRequestedAt = now;
                BuilderModule.requestMaterial(a, "food", 16);
            }
            return false;
        }
        int slot = best;
        if (slot >= 9) { a.moveToHotbar(slot, 8); slot = 8; }
        a.selectHotbar(slot);
        a.useItem(); // the server finishes eating by itself unless we let go
        eatTicks = EAT_TICKS;
        return true;
    }

    static AgentHandle find(String name) {
        for (AgentHandle h : AgentBus.agents.get()) if (h.name().equalsIgnoreCase(name)) return h;
        return null;
    }

    /** This role brings its load to the base chests itself: no distributor called. */
    protected boolean carriesHome(AgentHandle a) { return false; }

    private void checkInventory(AgentHandle a) {
        if (carriesHome(a) || keepsLoad) return;
        double fill = a.inventoryFill();
        if (fill <= ALERT_AT) { alerted = false; return; }
        long now = System.currentTimeMillis();
        if (alerted && now - alertedAt < RESEND_MS) return;

        var distributor = AgentBus.closest(Role.DISTRIBUTOR, a);
        alerted = true;
        alertedAt = now;
        if (distributor.isEmpty()) {
            a.log("inventory " + percent(fill) + " full, but no distributor is online");
            return;
        }
        AgentBus.send(Message.of(a.name(), distributor.get().name(), Message.Type.INVENTORY_FULL,
                "inventory " + percent(fill) + " full, come collect",
                Map.of("x", coord(a.x()), "y", coord(a.y()), "z", coord(a.z()),
                       "fill", String.valueOf(a.usedSlots()), "dimension", String.valueOf(a.dimension()))));
    }

    static String coord(double v) { return String.valueOf((int) Math.floor(v)); }

    static String percent(double f) { return Math.round(f * 100) + "%"; }
}
