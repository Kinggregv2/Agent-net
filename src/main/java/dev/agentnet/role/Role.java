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
    // The fighting crew (the user, 2026-11-21: "let's just focus on fighting"). The speedrunner and every role that went out
    // for its own materials (miner, hunter, explorer, looter, farmer, fisher, trader, brewer, rescuer) are gone: real players
    // bring the materials now, and a cleanup crew takes what the dead drop.
    BUILDER("Builder", 0xFFFF9800, Tier.HIGH, EnumSet.of(Capability.BARITONE, Capability.SCHEMATIC),
            "Builds .litematic schematics from the materials in the base storage; a schematic is split between all builders.",
            BuilderModule::new),
    DISTRIBUTOR("Distributor", 0xFF03A9F4, Tier.MEDIUM, EnumSet.of(Capability.BARITONE, Capability.CHEST_MEMORY),
            "Takes what the agents carry to the storage and brings them what they ask for, from what is in the base storage. Remembers chests.",
            DistributorModule::new),
    WARRIOR("Warrior", 0xFFF44336, Tier.HIGH, EnumSet.of(Capability.BARITONE, Capability.COMBAT),
            "Fighter geared from the base storage: hunts players on the hitlist, guards the base and protects the real players on its side.",
            WarriorModule::new),
    PEARLER("Pearl puller", 0xFF7B1FA2, Tier.MINIMAL, EnumSet.of(Capability.PATHING),
            "Stays by the stasis chambers. When an agent drops under half health, pulls its ender pearl to bring it home.",
            PearlerModule::new),
    MAKER("Maker", 0xFF795548, Tier.LOW, EnumSet.of(Capability.PATHING),
            "Crafts, smelts, stonecuts and brews at the stations near it: /agent make, /agent brew.",
            MakerModule::new),
    PICKUP("Cleanup crew", 0xFFCDDC39, Tier.LOW, EnumSet.of(Capability.PATHING),
            "Takes the items the dead drop (and anything else left lying around) and brings them to the storage keeper (or a distributor when there is no keeper).",
            PickupModule::new),
    KEEPER("Storage keeper", 0xFF607D8B, Tier.LOW, EnumSet.of(Capability.PATHING, Capability.CHEST_MEMORY),
            "Stays at the base and keeps its storage: knows what is in every chest, sorts in what players and distributors bring, hands out what they ask for.",
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
