package dev.agentnet.role;

/**
 * Train mode (/agent train on|off): warriors also fight this player (you), so you can test
 * their PvP yourself. Null = off. Nobody else is affected: whitelisted players stay safe.
 */
public final class Training {
    public static volatile String partner;

    private Training() {}
}
