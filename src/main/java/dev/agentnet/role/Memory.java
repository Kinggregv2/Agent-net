package dev.agentnet.role;

/**
 * What the crew keeps in memory while it plays (chest contents it saw, blocks miners claimed,
 * looted containers, pressure plates, fishing shores, farm reservations...). Most of it is keyed
 * by block position only, so it would carry over to another server and point the agents at
 * chests and claims that don't exist there. When the crew moves to a different server, all of it
 * is wiped and the agents start over with a clean head. (Files kept per server, like bases,
 * farms and storage chests, stay: they're looked up by server already.)
 */
public final class Memory {
    private Memory() {}

    public static void wipe() {
        ChestMemory.CONTENTS.clear();
        BaseWork.BAD.clear();
        LooterModule.LOOTED.clear();
        LooterModule.PLATES.clear();
        MinerModule.CLAIMS.clear();
        FisherModule.AUTO.clear();
        KeeperModule.PENDING.clear();
        KeeperModule.HALVES.clear();
        synchronized (FarmWork.class) { FarmWork.RESERVED.clear(); }
        dev.agentnet.map.Spots.clearClaims();
        dev.agentnet.map.Farms.clearClaims();
    }
}
