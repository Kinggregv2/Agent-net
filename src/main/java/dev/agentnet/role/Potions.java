package dev.agentnet.role;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Potions in a fight: what a bottle does (by its effects, so "strong_healing" and a custom
 * healing potion are the same thing), and using one. Splash potions are thrown straight down
 * at our own feet (instant, works while running); normal ones are drunk (32 ticks, the caller
 * waits); lingering ones count as splash.
 */
final class Potions {
    private Potions() {}

    /** Effect ids a potion item gives ("minecraft:instant_health", ...); empty for anything else. */
    static Set<String> effectsOf(ItemStack s) {
        Set<String> out = new HashSet<>();
        if (s.isEmpty()) return out;
        var c = s.get(DataComponents.POTION_CONTENTS);
        if (c == null) return out;
        for (var e : c.getAllEffects()) e.getEffect().unwrapKey().ifPresent(k -> out.add(k.identifier().toString()));
        return out;
    }

    static boolean isSplash(ItemStack s) { return s.getItem() == Items.SPLASH_POTION || s.getItem() == Items.LINGERING_POTION; }

    /** Slot of a potion giving this effect; splash first when preferSplash. -1 if none. */
    static int find(AgentHandle a, String effect, boolean preferSplash) {
        String id = effect.contains(":") ? effect : "minecraft:" + effect;
        List<ItemStack> inv = a.inventory();
        int drink = -1, splash = -1;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (!effectsOf(s).contains(id)) continue;
            if (isSplash(s)) { if (splash < 0) splash = i; } else if (s.getItem() == Items.POTION && drink < 0) drink = i;
        }
        if (preferSplash) return splash >= 0 ? splash : drink;
        return drink >= 0 ? drink : splash;
    }

    /**
     * Use the potion in this slot: a splash one thrown at our feet, a normal one started drinking.
     * Returns how many ticks to wait (0 for splash, 32 for drinking).
     */
    static int use(AgentHandle a, int slot) {
        ItemStack s = a.inventory().get(slot);
        boolean splash = isSplash(s);
        if (slot >= 9) { a.moveToHotbar(slot, 8); slot = 8; }
        a.selectHotbar(slot);
        if (splash) a.look(a.yaw(), 90f);
        a.useItem();
        return splash ? 2 : 34;
    }

    /**
     * Before or during a fight: heal when hurt (instant health, then regeneration), then the
     * buffs that are missing (strength, speed, fire resistance when burning, resistance).
     * Drinking only when nothing is right on top of us (it takes 1.6 s). Returns ticks to wait,
     * or 0 if it used nothing.
     */
    static int fightTick(AgentHandle a, double enemyDist, boolean burning) {
        float hp = a.health(), max = Math.max(1f, a.maxHealth());
        boolean close = enemyDist < 3.5;
        if (!Float.isNaN(hp) && hp / max < 0.5f) {
            int s = find(a, "instant_health", true);
            if (s >= 0 && (close ? isSplash(a.inventory().get(s)) : true)) return log(a, s, "healing");
            if (!a.hasEffect("regeneration")) {
                s = find(a, "regeneration", true);
                if (s >= 0 && (!close || isSplash(a.inventory().get(s)))) return log(a, s, "regeneration");
            }
        }
        if (burning && !a.hasEffect("fire_resistance")) {
            int s = find(a, "fire_resistance", true);
            if (s >= 0) return log(a, s, "fire resistance");
        }
        if (enemyDist > 16) return 0; // buffs only with a fight at hand
        for (String buff : new String[]{"strength", "speed", "resistance"}) {
            if (a.hasEffect(buff)) continue;
            int s = find(a, buff, true);
            if (s < 0) continue;
            if (close && !isSplash(a.inventory().get(s))) continue;
            return log(a, s, buff);
        }
        return 0;
    }

    private static int log(AgentHandle a, int slot, String what) {
        boolean splash = isSplash(a.inventory().get(slot));
        a.log((splash ? "splashing " : "drinking ") + "a potion of " + what);
        return use(a, slot);
    }
}
