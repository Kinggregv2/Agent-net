package dev.agentnet.role;

import java.util.List;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

/** Judging items: which rod is best, how worn a tool is. */
public final class Gear {
    private Gear() {}

    public static int level(ItemStack s, ResourceKey<Enchantment> key) {
        for (var e : s.getEnchantments().entrySet()) {
            Holder<Enchantment> h = e.getKey();
            if (h.is(key)) return e.getIntValue();
        }
        return 0;
    }

    /** Remaining durability as a fraction, 1 for items that don't wear. */
    public static double durability(ItemStack s) {
        if (s.isEmpty() || !s.isDamageableItem() || s.getMaxDamage() <= 0) return 1;
        return 1 - (double) s.getDamageValue() / s.getMaxDamage();
    }

    /**
     * How good a fishing rod is for a fisher who should keep himself going: any rod with
     * Mending beats any without; then Lure, then Luck of the Sea, then Unbreaking, then
     * how much durability is left. 0 = not a rod.
     */
    public static long rodScore(ItemStack s) {
        if (s.isEmpty() || s.getItem() != Items.FISHING_ROD) return 0;
        long score = 1;
        if (level(s, Enchantments.MENDING) > 0) score += 1_000_000_000L;
        score += level(s, Enchantments.LURE) * 10_000_000L;
        score += level(s, Enchantments.LUCK_OF_THE_SEA) * 100_000L;
        score += level(s, Enchantments.UNBREAKING) * 1_000L;
        score += Math.round(durability(s) * 999);
        return score;
    }

    public static String id(ItemStack s) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
    }

    public static boolean isFood(ItemStack s) {
        return !s.isEmpty() && s.has(net.minecraft.core.component.DataComponents.FOOD);
    }

    /** Ask the nearest distributor for 16 food. */
    public static void requestFood(AgentHandle a) { BuilderModule.requestMaterial(a, "food", 16); }

    public static int nutrition(ItemStack s) {
        var f = s.get(net.minecraft.core.component.DataComponents.FOOD);
        return f == null ? 0 : f.nutrition();
    }

    public static boolean isShulker(ItemStack s) {
        return !s.isEmpty() && id(s).endsWith("shulker_box");
    }

    /** Items inside a shulker box item (empty list for anything else). */
    public static List<ItemStack> contents(ItemStack s) {
        var c = s.get(net.minecraft.core.component.DataComponents.CONTAINER);
        return c == null ? List.of() : c.nonEmptyItemCopyStream().toList();
    }

    /** "hoe", "axe", "pickaxe", "shovel", "sword", "rod" for tools, else null. */
    public static String toolType(ItemStack s) {
        if (s.isEmpty()) return null;
        if (s.getItem() == Items.FISHING_ROD) return "rod";
        if (s.getItem() == Items.SHIELD) return "shield";
        String path = id(s);
        for (String t : new String[]{"pickaxe", "axe", "hoe", "shovel", "sword", "helmet", "chestplate", "leggings", "boots"}) {
            if (path.endsWith("_" + t)) return t;
        }
        return null;
    }

    /** Tier of a tool from its material, for picking the better one. */
    public static int tier(ItemStack s) {
        String p = id(s);
        if (p.contains("netherite")) return 6;
        if (p.contains("diamond")) return 5;
        if (p.contains("iron")) return 4;
        if (p.contains("copper")) return 3;
        if (p.contains("stone")) return 2;
        if (p.contains("golden")) return 1;
        return 0;
    }

    /** Hold the best tool it carries for breaking this block (pickaxe for stone, axe for wood...). */
    public static void holdBestFor(AgentHandle a, net.minecraft.world.level.block.state.BlockState st) { Tunnel.holdBest(a, st); }

    /** How good an armor piece is: material first, then Protection, then Mending/Unbreaking, then durability left. */
    public static double armorScore(ItemStack s) {
        if (s.isEmpty()) return -1;
        return tier(s) * 100
                + level(s, Enchantments.PROTECTION) * 10
                + (level(s, Enchantments.BLAST_PROTECTION) + level(s, Enchantments.PROJECTILE_PROTECTION) + level(s, Enchantments.FIRE_PROTECTION)) * 4
                + (level(s, Enchantments.MENDING) > 0 ? 8 : 0) + level(s, Enchantments.UNBREAKING) * 2
                + durability(s) * 5;
    }

    /** The best armor piece of a type ("helmet", "chestplate"...) carried, or -1. */
    public static int bestArmor(List<ItemStack> inv, String type) {
        int best = -1;
        double bestScore = -1;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (!type.equals(toolType(s))) continue;
            double sc = armorScore(s);
            if (sc > bestScore) { bestScore = sc; best = i; }
        }
        return best;
    }

    /** Best tool of a type, or -1: mending, then material, then durability left. */
    public static int bestTool(List<ItemStack> inv, String type) {
        if (type.equals("rod")) return bestRod(inv);
        int best = -1;
        double bestScore = -1;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (!type.equals(toolType(s))) continue;
            double sc = (level(s, Enchantments.MENDING) > 0 ? 100 : 0) + tier(s) * 10 + durability(s);
            if (sc > bestScore) { bestScore = sc; best = i; }
        }
        return best;
    }

    /** Does this stack satisfy a request? "food" = any food; a tool id = any tool of that type. */
    public static boolean matches(ItemStack s, String wanted) {
        if (s.isEmpty()) return false;
        if (wanted.equals("food")) return isFood(s);
        if (id(s).equals(wanted)) return true;
        String type = toolType(s);
        return type != null && type.equals(toolType(new ItemStack(
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(wanted)))));
    }

    /** Inventory slot (0-35) of the best rod, or -1. */
    public static int bestRod(List<ItemStack> inv) {
        int best = -1;
        long bestScore = 0;
        for (int i = 0; i < inv.size(); i++) {
            long sc = rodScore(inv.get(i));
            // ties go to the hotbar / lower slot, so we don't shuffle rods around for nothing
            if (sc > bestScore) { bestScore = sc; best = i; }
        }
        return best;
    }

    /** Pickaxe tier needed to get drops from this block name ("iron_ore", "ancient_debris", "logs"): 0 = none, 1 wood, 2 stone, 3 iron, 4 diamond, 5 netherite. -1 = unknown block. */
    public static int pickTierFor(String block) {
        var blocks = MinerModule.resolve(block);
        if (blocks.isEmpty()) return -1;
        String p = MinerModule.requiredPickaxe(blocks.iterator().next().defaultBlockState());
        if (p == null) return 0;
        return p.contains("wooden") ? 1 : p.contains("stone") ? 2 : p.contains("iron") ? 3 : p.contains("diamond") ? 4 : 5;
    }

    public static String pickTierName(int tier) {
        return switch (tier) { case 0 -> "none"; case 1 -> "wooden"; case 2 -> "stone"; case 3 -> "iron"; case 4 -> "diamond"; default -> "netherite"; };
    }
}
