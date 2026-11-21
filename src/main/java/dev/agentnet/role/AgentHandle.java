package dev.agentnet.role;

import java.util.List;

import net.minecraft.world.item.ItemStack;

/**
 * What a role module can see and do with its agent. Kept small on purpose: when
 * Baritone and brains arrive, new abilities get added here, and every module can use them.
 */
public interface AgentHandle {
    String name();
    Role role();
    int importance();
    String task();

    /** What its role is doing right now ("done: ...", "no logs found nearby", ...). */
    String moduleStatus();

    /** Normalized server key (same as the map uses) and dimension id, e.g. "minecraft:overworld". */
    String server();
    String dimension();
    /** Same dimension, and false while either one is still unknown (an agent that just joined has none). */
    static boolean sameDim(String a, String b) { return a != null && a.equals(b); }
    double x();
    double y();
    double z();
    boolean isOnline();

    /** The 36 main-inventory slots (hotbar first), empty stacks included. */
    List<ItemStack> inventory();
    /** Occupied main-inventory slots, 0-36. */
    int usedSlots();
    default double inventoryFill() { return usedSlots() / 36.0; }
    /** Worn armor: head, chest, legs, feet (empty stacks included). */
    List<ItemStack> armor();
    ItemStack offhand();

    // ---- moving items (container clicks; the server corrects us if anything differs)
    /** Swap an inventory slot (0-35) with a hotbar slot (0-8). */
    void moveToHotbar(int index, int hotbarSlot);
    /** Throw the whole stack in this inventory slot on the ground in front of the agent. */
    void dropStack(int index);
    /** True while a chest/shulker/etc. the agent opened is showing its contents. */
    boolean containerOpen();
    /** Menu type of the open container, e.g. "minecraft:generic_9x3", "minecraft:shulker_box". */
    String containerType();
    /** The open container's own slots (not the player part). */
    List<ItemStack> containerSlots();
    /** Shift-click a container slot into the inventory. */
    void quickMoveContainerSlot(int slot);
    /** The hotbar slot's whole stack into this slot of the open container (a number key over it), swapping what is there. */
    default void swapIntoContainer(int menuSlot, int hotbarSlot) {}
    /** Shift-click an inventory slot (0-35) into the open container. */
    void quickMoveToContainer(int index);
    /** Plain click on the open container: slot in the whole menu (its own slots first, then the inventory), button 0 left / 1 right. */
    void clickContainer(int menuSlot, int button);
    /** Where inventory slot 0-35 sits in the open container menu (-1 if none is open). */
    int containerMenuSlot(int index);
    void closeContainer();
    /** Throw one item from this inventory slot. */
    void dropOne(int index);

    // ---- body
    /** Hunger, 0-20. */
    int food();
    /** Item entities lying around within radius, nearest first (positions are estimates). */
    List<net.minecraft.world.phys.Vec3> itemsNear(double radius);
    /** Turn to look at a point. */
    void lookAt(double x, double y, double z);
    /** Swap the held item with the offhand (the F key). */
    void swapOffhand();
    // ---- making things
    /** Recipes in the agent's recipe book (what the server unlocked for it). */
    java.util.Collection<net.minecraft.world.item.crafting.display.RecipeDisplayEntry> recipes();
    /** Stonecutter recipes the server sent, or null. */
    net.minecraft.world.item.crafting.SelectableRecipe.SingleInputSet<net.minecraft.world.item.crafting.StonecutterRecipe> stonecutterRecipes();
    /** The item a recipe display shows (result, station...). */
    ItemStack resolve(net.minecraft.world.item.crafting.display.SlotDisplay display);
    /** Recipe-book click: the server fills the open crafting grid/furnace from the inventory. */
    void placeRecipe(net.minecraft.world.item.crafting.display.RecipeDisplayId id, boolean max);
    /** Press a menu button (stonecutter recipe, etc.). */
    void clickMenuButton(int button);
    /** Shift-click a slot of the agent's own inventory menu (0 = 2x2 crafting result). */
    void quickMoveInventoryMenu(int slot);
    /** Click a slot of the inventory menu (PICKUP: 0 = left, 1 = right) and the menu slot of an inventory index: hand crafting in the 2x2 grid. */
    void clickInventoryMenu(int menuSlot, int button);
    int inventoryMenuSlot(int index);

    /** Switch this agent to another role (takes effect next tick). */
    void changeRole(Role role);
    /** Give this agent a task, as /agent would (main thread). */
    void assignTask(String task);

    /** Run a server command as this agent, without the leading slash. */
    void serverCommand(String command);

    // ---- hands and looking
    float yaw();
    float pitch();
    /** Selected hotbar slot, 0-8. */
    int selectedSlot();
    void selectHotbar(int slot);
    /** Turn to face this direction (degrees, like F3). */
    void look(float yaw, float pitch);
    /** Right-click with the main-hand item (cast/reel a rod, eat, etc.). */
    void useItem();

    // ---- travelling (Baritone-derived pathfinder; its memory is freed after every trip)
    /** Walk to this block (within radius blocks; 0 = exactly on it). Replaces any current trip. */
    void navigate(int x, int y, int z, int radius);
    /** Somewhere in a hurry (saving someone): like navigate, but flown by elytra from {@link ElytraTravel#RUSH_TRIP} blocks on. */
    default void rush(int x, int y, int z, int radius) { navigate(x, y, z, radius); }
    void stopNavigation();
    boolean isNavigating();
    /** "idle", "planning", "walking", "arrived", "failed (why)", "waiting for chunks". */
    String navStatus();

    // ---- seeing and changing blocks
    /**
     * A temporary look at the blocks around the agent (decoded on demand). Use it, then
     * drop the reference: it holds decoded chunks. Null if the agent has no world data.
     */
    dev.agentnet.pathing.BlockSource openWorld();
    /**
     * True if the server won't let this agent place or break blocks here: within the
     * spawn-protection area around world spawn (vanilla default 16) and not an op.
     */
    boolean spawnProtected(int x, int z);
    /** World spawn as the server sent it, or null. */
    net.minecraft.core.BlockPos worldSpawn();
    /** Keep raw chunks while working in one area (farmers); otherwise they're dropped after trips. */
    void keepWorld(boolean keep);
    /**
     * Break a block with the held item, taking as long as a real player would.
     * Returns the ticks it takes (0 = instant), or -1 if it can't be broken.
     */
    int dig(int x, int y, int z);
    boolean isDigging();
    /** Right-click a block face with the held item (plant seeds, place saplings). */
    void useItemOn(int x, int y, int z, net.minecraft.core.Direction face);
    /** Place the held block against this block face, sneaking (never opens the clicked block). */
    void placeAgainst(int x, int y, int z, net.minecraft.core.Direction face);

    // ---- fishing
    /** Entity id of this agent's own fishing bobber, or -1 if none is out. */
    int bobberId();
    /** True while a fish is on the hook (time to reel in). */
    boolean bobberBiting();

    // ---- fighting
    /** Players and hostile mobs within radius, nearest first. */
    List<Seen> entitiesNear(double radius);
    // ---- trading
    /** Right-click an entity (a villager opens its trade screen). */
    void interact(int entityId);
    /** The open trade screen's offers (empty if none is open). */
    List<net.minecraft.world.item.trading.MerchantOffer> tradeOffers();
    /** Pick an offer: the server moves the payment from the inventory into the slots. */
    void selectTrade(int index);
    /** Use the offhand item (raise a shield). */
    void useOffhand();
    /** Let go of a used item (lower the shield, shoot the drawn bow). */
    void releaseUse();
    /** Flying after a wind charge / explosion push; vertical speed (negative = falling). */
    boolean airborne();
    double verticalSpeed();
    /** While flying: drift toward this horizontal direction. */
    void steer(double dx, double dz);
    /** Highest y of the current flight (the fall is measured from it); current y when on the ground. */
    double airPeakY();
    /** Treat this block as water right away (a clutch bucket was just used on it). */
    void expectWater(int x, int y, int z);
    /** Riding a vehicle (boat clutch); a boat that just appeared within 6 blocks (-1 = none); get out. */
    boolean riding();
    int recentBoat();
    void dismount();
    /** Spear jab (Lunge pushes us forward). */
    void stab();
    /** Boat clutch: don't touch down for up to this many ticks (the boat is on its way). */
    void holdFall(int ticks);
    /** A clutch boat was just placed here: land on it. Forget the clutch water/boat. */
    void expectBoat(double x, double y, double z);
    void clearClutch();
    /** useItem while crouching. */
    void useItemSneaking();
    /** Hold sneak (walk slowly, sculk sensors ignore the steps) until set false. */
    void setSneaking(boolean on);
    boolean sneaking();
    /** A normal jump; no-op in the air. */
    void jump();

    /** Elytra: open it (in the air, wearing one). False if it can't. */
    boolean startGliding();
    /** The elytra came off mid-air: plain falling from here. */
    void stopGliding();
    boolean gliding();
    /** Its job watches its own progress for a while (a staircase, a tunnel): the general stuck watch stays out of it. */
    default void holdStuckWatch(long ms) {}
    /** On an elytra trip (a long navigate flown instead of walked). */
    default boolean flying() { return false; }
    /** A firework rocket was just used while gliding: push along the look direction for its burn time. */
    void rocketBoost();
    /** Ticks of rocket burn left (it keeps burning while the elytra is off). */
    int rocketLeft();
    /** One block up on the spot, a block from the inventory under its feet (takes two ticks). */
    boolean pillarUp();
    /** A small step on the spot toward a point at its own height (to stand square on its block). */
    void stepToward(double x, double z);
    /** Dig and pillar straight up out of a cave or tunnel to the open sky. False if there is open sky over it already. */
    default boolean goUp() { return false; }
    /** The server has it using an item right now (shield raised, eating). */
    default boolean usingItem() { return false; }
    /** An entity's health as the server last sent it; NaN if unknown. */
    default float healthOf(int entityId) { return Float.NaN; }
    /** For the next second, keep facing this point while walking (a shield held toward a blaze) instead of the way it goes. */
    default void faceWhileMoving(double x, double y, double z) {}
    /** Busy digging or pillaring its way out of somewhere (a module should not give it new trips meanwhile). */
    default boolean escaping() { return false; }
    /** A jump with this much push along the ground (blocks per tick): 0.265 lands two blocks on. */
    default void hop(double vx, double vz) {}
    /** Diving on purpose for this many seconds: no "up for air" in that time. */
    default void dive(int seconds) {}
    /** A short swim straight toward a point (through water and air only). False if the way is blocked. */
    default boolean swimToward(double x, double y, double z) { return false; }
    /** It is raining (or thundering) in its world. */
    boolean raining();
    /** One of our own active effects ("strength", "minecraft:speed"...). */
    /** Stop digging its way out (the role has a better way on: a bridge). */
    default void cancelEscape() {}
    /** One of the crew died to mobs near this spot in the last ten minutes. */
    default boolean nearDeathSpot(int x, int y, int z, int r) { return false; }
    boolean hasEffect(String id);
    /** Its level (Strength II = 2); 0 without it. */
    default int effectLevel(String id) { return hasEffect(id) ? 1 : 0; }
    /** What an entity wears (feet, legs, chest, head) as the server last sent it; empty if unknown. */
    default java.util.List<ItemStack> armorOf(int entityId) { return java.util.List.of(); }
    /** This mob wears a banner on its head: a raid captain. */
    default boolean captain(int entityId) { return false; }
    /** Horizontal speed in the air (blocks per tick), 0 on the ground. */
    double horizontalSpeed();
    /** Leave the server right now and rejoin a moment later (the leave clutch). */
    void leaveClutch();
    /** The server confirmed we damaged this entity within the last ms milliseconds. */
    boolean hitRecently(int entityId, long ms);
    /** Hit an entity with the held item (and swing the arm). */
    void attack(int entityId);
    /** One swing with the item in this hotbar slot, then back to the one in hand (an axe against a raised shield). */
    default void attackWith(int entityId, int hotbarSlot) { attack(entityId); }
    /** Swing the arm (26.3 punch): the animation everyone sees, and it resets the attack charge. */
    void swingArm();
    /** On the hitlist (and not whitelisted, not one of ours). */
    boolean isEnemy(String player);
    /** Health right now (NaN before the server said), and max health. */
    float health();
    float maxHealth();

    /** Write a line to this agent's log (chat + latest.log). */
    void log(String message);
}
