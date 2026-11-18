package dev.agentnet.role;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.agentnet.AgentFiles;
import dev.agentnet.AgentNet;
import net.minecraft.world.item.ItemStack;

/**
 * The crew's war status.
 * <ul>
 *   <li>{@link Status#PEACE}: everyone does their normal job.</li>
 *   <li>{@link Status#TRANSITION}: every farmer, fisher, builder... became a warrior or a
 *       distributor. Distributors hand out war kits; a distributor that isn't the closest
 *       one to any warrior takes a kit itself and becomes a warrior (the last one never does).
 *       Once every warrior has its kit, the status becomes {@link Status#WAR}.</li>
 *   <li>{@link Status#WAR}: fighting (no warrior AI yet), distributors keep supplying.</li>
 * </ul>
 * The kit is in agentnet/warkit.txt ("item count"; armor, weapons and shields match any
 * material, so "minecraft:netherite_helmet 1" means "a helmet").
 */
public final class War {
    public enum Status { PEACE, TRANSITION, WAR }

    public record KitItem(String item, int count) {}

    private static volatile Status status = Status.PEACE;
    private static List<KitItem> kit;

    private War() {}

    public static Status status() { return status; }
    public static boolean active() { return status != Status.PEACE; }
    public static void set(Status s) { status = s; }

    public static synchronized List<KitItem> kit() {
        if (kit != null) return kit;
        Path f = AgentFiles.dir().resolve("warkit.txt");
        List<String> lines;
        try {
            if (!Files.exists(f)) {
                Files.write(f, List.of("# War kit every warrior gets: item count",
                        "# Armor, weapons and shields match any material (a diamond helmet counts as a helmet).",
                        "minecraft:netherite_helmet 1", "minecraft:netherite_chestplate 1", "minecraft:netherite_leggings 1",
                        "minecraft:netherite_boots 1", "minecraft:netherite_sword 1", "minecraft:netherite_axe 1",
                        "minecraft:shield 1", "minecraft:golden_apple 64", "minecraft:experience_bottle 64",
                        "minecraft:ender_pearl 16", "minecraft:cooked_beef 32"), StandardCharsets.UTF_8);
            }
            lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            AgentNet.LOG.warn("Could not read warkit.txt", e);
            lines = List.of();
        }
        List<KitItem> out = new ArrayList<>();
        for (String l : lines) {
            String[] p = l.strip().split("\\s+");
            if (l.startsWith("#") || p.length < 1 || p[0].isEmpty()) continue;
            int n = 1;
            if (p.length > 1) try { n = Integer.parseInt(p[1]); } catch (NumberFormatException ignored) {}
            out.add(new KitItem(p[0].contains(":") ? p[0] : "minecraft:" + p[0], n));
        }
        kit = out;
        return kit;
    }

    /** How many of a kit item an agent has, counting worn armor and the offhand. */
    public static int have(AgentHandle a, String item) {
        int n = 0;
        for (ItemStack s : a.inventory()) if (Gear.matches(s, item)) n += s.getCount();
        for (ItemStack s : a.armor()) if (Gear.matches(s, item)) n += s.getCount();
        if (Gear.matches(a.offhand(), item)) n += a.offhand().getCount();
        return n;
    }

    /** Does this agent have its whole kit? (Consumables count once there's at least half.) */
    public static boolean hasKit(AgentHandle a) {
        for (KitItem k : kit()) {
            int need = k.count() > 1 ? Math.max(1, k.count() / 2) : 1;
            if (have(a, k.item()) < need) return false;
        }
        return true;
    }
}
