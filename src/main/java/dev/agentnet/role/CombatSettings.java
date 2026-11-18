package dev.agentnet.role;

/** Combat switches the commands can flip (/agent aerial on|off). */
public final class CombatSettings {
    private CombatSettings() {}

    /** Elytra mace against mobs too (players always). */
    public static volatile boolean aerialOnMobs;
}
