package dev.agentnet.role;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * An entity an agent can see: a player (name) or a mob (type id), where it is, and what a
 * fighter needs to know about it: whether it's using an item (raising a shield, drawing a
 * bow, eating) and with which hand, what it holds, how many armor pieces it wears, whether
 * a creeper is about to blow, whether an enderman is angry, and what it rides (-1 = nothing).
 */
public record Seen(int id, String name, boolean player, boolean hostile, Vec3 pos, double distance,
                   boolean usingItem, boolean usingOffhand, ItemStack mainHand, ItemStack offHand, int armorPieces,
                   boolean swelling, boolean angry, int vehicle) {
    /** Holding a shield up (using the item in the hand that holds a shield). */
    public boolean blocking() {
        if (!usingItem) return false;
        ItemStack h = usingOffhand ? offHand : mainHand;
        return h.getItem() == net.minecraft.world.item.Items.SHIELD;
    }

    /** Drawing a bow or crossbow (skeletons, pillagers, players). */
    public boolean aiming() {
        if (!usingItem) return false;
        ItemStack h = usingOffhand ? offHand : mainHand;
        return h.getItem() == net.minecraft.world.item.Items.BOW || h.getItem() == net.minecraft.world.item.Items.CROSSBOW;
    }

    public int armorCount() { return Integer.bitCount(armorPieces); }
}
