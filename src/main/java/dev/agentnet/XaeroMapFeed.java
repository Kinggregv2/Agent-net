package dev.agentnet;

import java.lang.reflect.Field;

import dev.agentnet.map.MapFeed;
import net.minecraft.client.Minecraft;
import xaero.map.MapProcessor;
import xaero.map.MapWriter;
import xaero.map.MapWriterFabric;
import xaero.map.WorldMapSession;
import xaero.map.biome.BiomeGetter;
import xaero.map.cache.BlockStateShortShapeCache;

/**
 * Draws what agents see on Xaero's World Map. A second Xaero map writer (with its own
 * progress, so the one following you is undisturbed) is pointed at the area around a
 * queued agent chunk for a few seconds, then moves to the next area. Only touched when
 * Xaero's World Map is installed. Render thread only.
 */
final class XaeroMapFeed {
    private static final long MAX_DWELL_MS = 20_000;
    private static final int PASSES_PER_TICK = 8;
    private static final long PASS_BUDGET_NS = 4_000_000;
    private static long lastProgress, lastServed;
    private static long firstCursor = -1;
    private static boolean lapDone;
    private static Field cursorX, cursorZ;

    /** The writer's position in its row-by-row walk over the area (fields X and Z). */
    private static long cursor(MapWriter w) throws ReflectiveOperationException {
        if (cursorX == null) { cursorX = field(MapWriter.class, "X"); cursorZ = field(MapWriter.class, "Z"); }
        // Row (Z) first, so walking forward always counts up and a wrap counts down. (+2^31 keeps negatives ordered.)
        return ((long) (cursorZ.getInt(w) + 0x40000000) << 32) | ((cursorX.getInt(w) + 0x40000000L) & 0xFFFFFFFFL);
    }

    private static MapProcessor processorFor;
    private static MapWriter scout;
    private static boolean broken;
    private static int centerX, centerZ;
    private static long areaStarted;
    private static boolean hasArea;
    private static Object lastLevel;
    private static String lastServer;

    private XaeroMapFeed() {}

    static void init() {
        MapFeed.enabled = true;
        AgentNet.LOG.info("Xaero's World Map found: agents will draw what they see on it");
    }

    static void tick(Minecraft mc) {
        ticksRun++;
        if (broken || mc.level == null || mc.player == null) return;
        // Another world: another server clears the queue; another dimension of the same one keeps it (each chunk knows its
        // dimension and waits until you're there).
        if (mc.level != lastLevel) {
            String server = dev.agentnet.map.Servers.current();
            if (lastServer == null || !lastServer.equals(server)) MapFeed.clear();
            lastServer = server; lastLevel = mc.level; hasArea = false;
        }
        try {
            WorldMapSession session = WorldMapSession.getCurrentSession();
            if (session == null) { why = "no Xaero session"; return; }
            MapProcessor proc = session.getMapProcessor();
            if (proc == null || proc.getWorld() == null) { why = "no map processor or world"; return; }
            if (proc != processorFor) { scout = makeScout(proc); processorFor = proc; hasArea = false; }

            String dim = mc.level.dimension().identifier().toString();
            long now = System.currentTimeMillis();
            // Done with an area once the writer's cursor has gone all the way round it (it walks the
            // square around the "player" row by row and wraps back to where it started).
            boolean settled = hasArea && lapDone;
            if (!hasArea || settled || now - areaStarted > MAX_DWELL_MS) {
                if (hasArea && !lapDone) AgentNet.LOG.debug("map feed: area {},{} cut off after {} s", centerX, centerZ, MAX_DWELL_MS / 1000);
                if (hasArea) MapFeed.finishArea(centerX, centerZ);
                MapFeed.Pending next = MapFeed.next(mc.level, dim);
                hasArea = next != null;
                if (!hasArea) { why = "nothing queued in " + dim; return; }
                centerX = next.cx();
                centerZ = next.cz();
                areaStarted = now;
                scout = makeScout(proc); // a fresh cursor, starting at the beginning of this area
                firstCursor = -1; lapDone = false;
                lastProgress = now;
                lastServed = MapFeed.served();
            }
            double px, py, pz;
            synchronized (proc.mainStuffSync) {
                px = proc.mainPlayerX; py = proc.mainPlayerY; pz = proc.mainPlayerZ;
                proc.mainPlayerX = centerX * 16 + 8;
                proc.mainPlayerZ = centerZ * 16 + 8;
            }
            MapFeed.begin(dim);
            try {
                // Several passes per tick, within a small time budget so your frame rate doesn't notice.
                long until = System.nanoTime() + PASS_BUDGET_NS;
                for (int i = 0; i < PASSES_PER_TICK && System.nanoTime() < until; i++) {
                    scout.onRender(proc.getBiomeColorCalculator(), proc.getOverlayManager());
                    passes++;
                    why = "writing";
                    long c = cursor(scout);
                    if (firstCursor != -1 && c < firstCursor) { lapDone = true; break; } // row number went back: wrapped, area done
                    firstCursor = c;
                }
            } finally {
                MapFeed.end();
                synchronized (proc.mainStuffSync) {
                    // Put your position back, unless Xaero already refreshed it meanwhile.
                    if (proc.mainPlayerX == centerX * 16 + 8 && proc.mainPlayerZ == centerZ * 16 + 8) {
                        proc.mainPlayerX = px; proc.mainPlayerY = py; proc.mainPlayerZ = pz;
                    }
                }
            }
            if (MapFeed.served() != lastServed) { lastServed = MapFeed.served(); lastProgress = System.currentTimeMillis(); }
        } catch (Throwable t) {
            broken = true;
            MapFeed.enabled = false;
            MapFeed.clear();
            AgentNet.LOG.warn("Xaero map feed stopped (this Xaero version is different than expected)", t);
        }
    }

    private static MapWriter makeScout(MapProcessor proc) throws ReflectiveOperationException {
        MapWriter main = proc.getMapWriter();
        BlockStateShortShapeCache shapes = (BlockStateShortShapeCache) field(MapWriter.class, "blockStateShortShapeCache").get(main);
        BiomeGetter biomes = (BiomeGetter) field(MapWriter.class, "biomeGetter").get(main);
        MapWriter w = new MapWriterFabric(proc.getOverlayManager(), shapes, biomes);
        w.setMapProcessor(proc);
        return w;
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static volatile String why = "not started";
    private static volatile long ticksRun, passes;

    static String stats() {
        return "[" + why + "; ticks " + ticksRun + ", passes " + passes + ", area " + (hasArea ? centerX + "," + centerZ : "none") + (lapDone ? " lap done" : "") + "; asked " + MapFeed.asked + ", not queued " + MapFeed.notQueued + ", other dim " + MapFeed.otherDim + ", queued in area " + (hasArea && lastLevel instanceof net.minecraft.client.multiplayer.ClientLevel cl ? MapFeed.around(cl.dimension().identifier().toString(), centerX, centerZ, 40) + " (client has " + MapFeed.aroundLoaded(cl, cl.dimension().identifier().toString(), centerX, centerZ, 40) + ")" : "?") + ", last asked " + MapFeed.lastAskX + "," + MapFeed.lastAskZ + ", you at chunk " + (net.minecraft.client.Minecraft.getInstance().player == null ? "?" : net.minecraft.client.Minecraft.getInstance().player.chunkPosition().x() + "," + net.minecraft.client.Minecraft.getInstance().player.chunkPosition().z()) + "] " + MapFeed.offered + " chunk(s) in from agents, " + MapFeed.queued() + " waiting, " + MapFeed.served() + " drawn, "
                + MapFeed.dropped + " dropped (queue full)" + ", " + MapFeed.skippedLoaded + " skipped (yours)" + (broken ? ", FEED STOPPED" : "");
    }
}
