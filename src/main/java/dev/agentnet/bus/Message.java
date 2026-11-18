package dev.agentnet.bus;

import java.util.Map;

/**
 * One message between agents (or from you). {@code data} carries structured fields
 * (coordinates, item ids, counts) so receivers don't have to parse {@code text}.
 */
public record Message(String from, String to, Type type, String text, Map<String, String> data, long sentAt) {

    public enum Type {
        /** Farmer/miner/fisher to the nearest distributor: "come collect". data: x, y, z, fill */
        INVENTORY_FULL,
        /** Anyone to a distributor: "I need items". data: item, count, x, y, z */
        ITEM_REQUEST,
        /** Distributor to requester: items handed over. data: item, count */
        ITEM_DELIVERED,
        /** Anyone: a chest was found or filled. data: x, y, z, dimension */
        CHEST_FOUND,
        /** Anyone to a trader: "get me this trade". data: item, count */
        TRADE_REQUEST,
        /** Coordinator to a builder: your part of a schematic. data: file, x1, y1, z1, x2, y2, z2 */
        BUILD_ASSIGNMENT,
        /** Anyone to warriors: hitlist player seen. data: player, x, y, z */
        ENEMY_SPOTTED,
        /** Distributor to a gatherer standing next to it: "drop your stuff for me". */
        COLLECT_DROP,
        /** Distributor to any agent: "drop me this, someone needs it". data: item, count (the agent only gives what it can spare). */
        HAND_OVER,
        /** Agent under attack to every warrior: "help, I'm being attacked". data: id (attacker entity id), name, x, y, z */
        HELP,
        /** Distributor to a storage keeper: "I'm dropping my load for you at the hub". */
        DEPOSIT,
        /** Distributor to a storage keeper: "get me this from storage". data: item, count */
        KEEPER_REQUEST,
        /** Gatherer to distributor: everything but my job items is on the ground. */
        DROP_DONE,
        /** Miner to one distributor: my load is on the ground here, come and get it before it despawns. data: x, y, z, items */
        GROUND_PICKUP,
        /** Anyone to a pearl puller: pull this agent's stasis pearl. data: agent */
        PEARL_PULL,
        /** Builder to an agent standing where a block goes: "step aside". data: x, y, z (the block) */
        STEP_ASIDE,
        /** Free-form text. */
        TEXT
    }

    public Message {
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public static Message of(String from, String to, Type type, String text, Map<String, String> data) {
        return new Message(from, to, type, text, data, System.currentTimeMillis());
    }

    public String get(String key) { return data.get(key); }
}
