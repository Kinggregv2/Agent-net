package dev.agentnet.role;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * What an agent does. Changing an agent's role swaps its module immediately
 * (old module's onStop, new module's onStart).
 *
 * <p>{@link #tier} and {@link #needs} are the "what gets loaded" contract: when brains and
 * Baritone arrive, the scheduler gives HIGH-tier agents more CPU/model time, and only
 * loads Baritone/schematics/etc. for roles that list them.
 */
public enum Role {
    FARMER("Farmer", 0xFF8BC34A, Tier.LOW, EnumSet.of(Capability.PATHING),
            "Works a farm area: harvests and replants wheat, carrots, potatoes, beetroot, or chops and replants trees.",
            FarmerModule::new),
    MINER("Miner", 0xFF9E9E9E, Tier.MEDIUM, EnumSet.of(Capability.PATHING),
            "Mines a block type: finds the nearest one and tunnels to it (avoids lava and water). Alerts the nearest distributor at 50% inventory.",
            MinerModule::new),
    BUILDER("Builder", 0xFFFF9800, Tier.HIGH, EnumSet.of(Capability.BARITONE, Capability.SCHEMATIC),
            "Builds .litematic schematics; a schematic is split between all builders. Requests materials.",
            BuilderModule::new),
    DISTRIBUTOR("Distributor", 0xFF03A9F4, Tier.MEDIUM, EnumSet.of(Capability.BARITONE, Capability.CHEST_MEMORY),
            "Collects items from other agents, stores them, and delivers what others request. Remembers chests.",
            DistributorModule::new),
    EXPLORER("Explorer", 0xFFE91E63, Tier.MEDIUM, EnumSet.of(Capability.PATHING),
            "Walks to chunks nobody has seen yet (within its radius) and revisits POIs, filling the shared map.",
            ExplorerModule::new),
    TRADER("Trader", 0xFF4CAF50, Tier.MEDIUM, EnumSet.of(Capability.BARITONE, Capability.CHEST_MEMORY),
            "Trades with villagers, handles requested trades, and stores the results in a chest.",
            TraderModule::new),
    FISHER("Fisher", 0xFF00BCD4, Tier.MINIMAL, EnumSet.of(Capability.HOLD_USE),
            "AFK fishing. Alerts the nearest distributor at 50% inventory.",
            FisherModule::new),
    WARRIOR("Warrior", 0xFFF44336, Tier.HIGH, EnumSet.of(Capability.BARITONE, Capability.COMBAT),
            "Fully geared fighter that hunts players on the hitlist.",
            WarriorModule::new),
    PEARLER("Pearl puller", 0xFF7B1FA2, Tier.MINIMAL, EnumSet.of(Capability.PATHING),
            "Stays by the stasis chambers. When an agent drops under half health, pulls its ender pearl to bring it home.",
            PearlerModule::new),
    MAKER("Maker", 0xFF795548, Tier.LOW, EnumSet.of(Capability.PATHING),
            "Crafts, smelts, stonecuts and brews at the stations near it: /agent make, /agent brew.",
            MakerModule::new),
    HUNTER("Hunter-gatherer", 0xFF8D6E63, Tier.LOW, EnumSet.of(Capability.PATHING, Capability.COMBAT),
            "Gets food from the land: hunts passive animals (leaves two of each to breed), picks sweet berries, takes hay bales and crops from villages; explores when there is nothing around.",
            HunterModule::new),
    LOOTER("Looter", 0xFFFFC107, Tier.MEDIUM, EnumSet.of(Capability.PATHING, Capability.COMBAT),
            "Loots generated structures (dungeons, mineshafts, villages, temples, trial chambers, ancient cities, end cities) without setting off their traps; can kill an iron golem from a pillar.",
            LooterModule::new),
    TRIAL_GRINDER("Trial grinder", 0xFFB87333, Tier.MEDIUM, EnumSet.of(Capability.PATHING, Capability.COMBAT),
            "Finds a trial chamber and grinds it: beats its spawners for keys, breeze rods and heavy cores, opens the vaults, and stays to fight the spawners again after every cooldown.",
            () -> new LooterModule(true)),
    SPEEDRUNNER("Speedrunner", 0xFF9C27B0, Tier.MEDIUM, EnumSet.of(Capability.PATHING, Capability.COMBAT),
            "Beats the game on its own like a runner: its own pickaxes, bucket, obsidian and flint and steel, its own nether portal, then blaze rods, pearls and the End.",
            SpeedrunnerModule::new),
    FALLOFF("Falloff speedrunner", 0xFFB39DDB, Tier.MEDIUM, EnumSet.of(Capability.PATHING, Capability.COMBAT),
            "Goes with the speedrunners like a helper; when they leave the blaze spawner it stays and farms blaze rods until the falloffs hold two stacks between them, picks nether wart, comes home and becomes a brewer.",
            SpeedrunnerModule::new),
    BREWER("Brewer", 0xFF5E35B1, Tier.LOW, EnumSet.of(Capability.PATHING),
            "Brews potions like a player: a brewing stand from a blaze rod, glass bottles from sand, water, nether wart into awkward potions, blaze powder into Strength.",
            BrewerModule::new),
    PICKUP("Picker-upper", 0xFFCDDC39, Tier.LOW, EnumSet.of(Capability.PATHING),
            "Walks to items lying on the ground and picks them up; brings them to the storage keeper (or calls a distributor when there is no keeper).",
            PickupModule::new),
    RESCUER("Rescuer", 0xFF26A69A, Tier.LOW, EnumSet.of(Capability.PATHING),
            "Brings a pickaxe to agents stranded deep underground without one (digging its way down to them when there is no path); waits at the base in between.",
            RescuerModule::new),
    KEEPER("Storage keeper", 0xFF607D8B, Tier.LOW, EnumSet.of(Capability.PATHING, Capability.CHEST_MEMORY),
            "Stays at the base and keeps its storage: knows what is in every chest, sorts in what distributors bring, hands out what they ask for.",
            KeeperModule::new);

    /** How much compute an agent of this role should get once brains exist. */
    public enum Tier { MINIMAL, LOW, MEDIUM, HIGH }

    /** Subsystems a role needs loaded. */
    public enum Capability { PATHING, BARITONE, HOLD_USE, SCHEMATIC, CHEST_MEMORY, COMBAT, REASONING }

    public final String displayName;
    /** Marker color on the map. */
    public final int color;
    public final Tier tier;
    public final Set<Capability> needs;
    public final String description;
    private final Supplier<RoleModule> factory;

    Role(String displayName, int color, Tier tier, Set<Capability> needs, String description,
         Supplier<RoleModule> factory) {
        this.displayName = displayName;
        this.color = color;
        this.tier = tier;
        this.needs = needs;
        this.description = description;
        this.factory = factory;
    }

    public RoleModule createModule() { return factory.get(); }

    public String id() { return name().toLowerCase(Locale.ROOT); }

    /** "miner", "Miner" -> MINER; null if unknown. */
    public static Role parse(String s) {
        for (Role r : values()) if (r.id().equalsIgnoreCase(s)) return r;
        return null;
    }
}
