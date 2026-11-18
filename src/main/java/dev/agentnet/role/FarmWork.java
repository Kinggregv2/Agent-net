package dev.agentnet.role;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.agentnet.AgentFiles;
import dev.agentnet.AgentNet;
import dev.agentnet.map.Farms;
import net.minecraft.core.BlockPos;

/**
 * What farmers on the same field share:
 * <ul>
 *   <li><b>Reservations</b>: a farmer reserves a block before going for it, so two
 *       farmers never chase the same crop. Freed when the job is done; a reservation
 *       nobody renews expires after 30 s (a farmer got stuck or left).</li>
 *   <li><b>Farmland memory</b>: every block ever seen as farmland in a field, saved to
 *       agentnet/farmland/. If one turns back into dirt, farmers till it again.</li>
 * </ul>
 * Main thread only (all farmer modules tick there).
 */
final class FarmWork {
    private static final long RESERVATION_MS = 30_000;

    private record Reservation(String agent, long at) {}

    /** farm id -> block -> who's on it */
    static final Map<String, Map<BlockPos, Reservation>> RESERVED = new HashMap<>();
    /** farm id -> every block that was farmland once */
    private static final Map<String, Set<BlockPos>> FARMLAND = new HashMap<>();

    private FarmWork() {}

    private static String id(Farms.Farm f) { return f.server() + "|" + f.name(); }

    /** True if this agent may take the block (free, expired, or already its own). Takes it. */
    static boolean reserve(Farms.Farm f, BlockPos p, String agent) {
        Map<BlockPos, Reservation> m = RESERVED.computeIfAbsent(id(f), k -> new HashMap<>());
        Reservation r = m.get(p);
        long now = System.currentTimeMillis();
        if (r != null && !r.agent.equalsIgnoreCase(agent) && now - r.at < RESERVATION_MS) return false;
        m.put(p, new Reservation(agent, now));
        return true;
    }

    /** Someone else is on this block right now. */
    static boolean takenByOther(Farms.Farm f, BlockPos p, String agent) {
        Map<BlockPos, Reservation> m = RESERVED.get(id(f));
        if (m == null) return false;
        Reservation r = m.get(p);
        return r != null && !r.agent.equalsIgnoreCase(agent) && System.currentTimeMillis() - r.at < RESERVATION_MS;
    }

    static void release(Farms.Farm f, BlockPos p, String agent) {
        Map<BlockPos, Reservation> m = RESERVED.get(id(f));
        if (m == null) return;
        Reservation r = m.get(p);
        if (r != null && r.agent.equalsIgnoreCase(agent)) m.remove(p);
    }

    static void releaseAll(String agent) {
        for (Map<BlockPos, Reservation> m : RESERVED.values()) m.values().removeIf(r -> r.agent.equalsIgnoreCase(agent));
    }

    /** Number of blocks other farmers currently hold in this field (for status/tests). */
    static int reservedByOthers(Farms.Farm f, String agent) {
        Map<BlockPos, Reservation> m = RESERVED.get(id(f));
        if (m == null) return 0;
        long now = System.currentTimeMillis();
        return (int) m.values().stream().filter(r -> !r.agent.equalsIgnoreCase(agent) && now - r.at < RESERVATION_MS).count();
    }

    // ------------------------------------------------------------------ farmland memory

    static Set<BlockPos> farmland(Farms.Farm f) {
        return FARMLAND.computeIfAbsent(id(f), k -> load(f));
    }

    /** Add newly seen farmland blocks; saves only if something new was learned. */
    static void rememberFarmland(Farms.Farm f, Collection<BlockPos> seen) {
        Set<BlockPos> known = farmland(f);
        if (known.addAll(seen)) save(f, known);
    }

    private static Path file(Farms.Farm f) {
        String safe = (f.server() + "__" + f.name()).replaceAll("[^A-Za-z0-9._-]", "_");
        return AgentFiles.dir().resolve("farmland").resolve(safe + ".txt");
    }

    private static Set<BlockPos> load(Farms.Farm f) {
        Set<BlockPos> out = new HashSet<>();
        Path p = file(f);
        try {
            if (Files.exists(p)) {
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    String[] s = line.trim().split(" ");
                    if (s.length == 3) out.add(new BlockPos(Integer.parseInt(s[0]), Integer.parseInt(s[1]), Integer.parseInt(s[2])));
                }
            }
        } catch (IOException | NumberFormatException e) {
            AgentNet.LOG.warn("Could not read {}", p, e);
        }
        return out;
    }

    private static void save(Farms.Farm f, Set<BlockPos> known) {
        Path p = file(f);
        List<String> lines = new ArrayList<>(known.size());
        for (BlockPos b : known) lines.add(b.getX() + " " + b.getY() + " " + b.getZ());
        try {
            Files.createDirectories(p.getParent());
            Files.write(p, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            AgentNet.LOG.warn("Could not write {}", p, e);
        }
    }

    /** Forget a field entirely (farm removed). */
    static void forget(Farms.Farm f) {
        RESERVED.remove(id(f));
        FARMLAND.remove(id(f));
        try { Files.deleteIfExists(file(f)); } catch (IOException ignored) {}
    }
}
