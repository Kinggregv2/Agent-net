package dev.agentnet.role;

import java.util.List;

import net.minecraft.world.item.ItemStack;

/**
 * What an agent can give away without hurting its own job: a distributor with nothing in
 * stock fetches a requested item from whoever can spare it.
 *
 * <p>Everyone keeps 16 food. Then by role: miners keep a pickaxe, torches and what they make
 * their next pickaxe from; farmers their hoe and seeds; fishers their rod; warriors their
 * weapons, armor and fight supplies; explorers a boat. Builders, makers, traders and
 * distributors keep everything (it's all for their work).
 */
public final class Spare {
    private Spare() {}

    /** How many of this item (id like "minecraft:iron_pickaxe") the agent can hand over. */
    public static int spare(AgentHandle h, String item) {
        Role role = h.role();
        if (role == Role.BUILDER || role == Role.MAKER || role == Role.DISTRIBUTOR) return 0;
        List<ItemStack> inv = h.inventory();
        int have = 0;
        ItemStack sample = ItemStack.EMPTY;
        // "logs": any kind of log (a speedrunner asking for wood), each kind kept back as itself.
        if (item.equals("logs")) {
            java.util.Map<String, Integer> kinds = new java.util.HashMap<>(); java.util.Map<String, ItemStack> samples = new java.util.HashMap<>();
            for (ItemStack s : inv) if (Gear.matches(s, item)) { kinds.merge(Gear.id(s), s.getCount(), Integer::sum); samples.put(Gear.id(s), s); }
            int n = 0;
            for (var e : kinds.entrySet()) n += Math.max(0, e.getValue() - keep(role, e.getKey(), samples.get(e.getKey())));
            return n;
        }
        for (ItemStack s : inv) if (!s.isEmpty() && Gear.id(s).equals(item)) { have += s.getCount(); sample = s; }
        if (have == 0) return 0;
        return Math.max(0, have - keep(role, item, sample));
    }

    private static int keep(Role role, String id, ItemStack s) {
        if (Gear.isFood(s)) return role == Role.WARRIOR && id.contains("golden_apple") ? Integer.MAX_VALUE : 16;
        boolean tool = s.getMaxStackSize() == 1;
        if (role == null) return tool ? 1 : 0;
        return switch (role) {
            case WARRIOR -> {
                if (id.equals("minecraft:arrow") || id.equals("minecraft:wind_charge") || id.equals("minecraft:ender_pearl")
                        || id.equals("minecraft:totem_of_undying") || id.equals("minecraft:experience_bottle")) yield Integer.MAX_VALUE;
                if (tool || id.endsWith("_boat") || id.endsWith("_raft") || id.endsWith("bucket")) yield 1; // one of each weapon, armor piece, boat, bucket
                yield 0;
            }
            case PEARLER -> id.equals("minecraft:ender_pearl") ? Integer.MAX_VALUE : tool ? 1 : 0;
            default -> tool ? 1 : 0;
        };
    }
}
