package dev.agentnet;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import dev.agentnet.map.Pois;
import dev.agentnet.map.Servers;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.common.HudMod;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.player.tracker.system.IRenderedPlayerTracker;
import xaero.hud.minimap.player.tracker.system.ITrackedPlayerReader;
import xaero.hud.minimap.waypoint.set.WaypointSet;
import xaero.hud.minimap.world.MinimapWorld;

/**
 * Hooks AgentNet into Xaero's Minimap / World Map. Only loaded when Xaero's Minimap is
 * installed.
 *
 * <ul>
 *   <li>Agents show on the minimap and world map as player heads with names, anywhere in
 *       the world, the same way Xaero shows party members.</li>
 *   <li>Waypoints whose name ends in "POI" become AgentNet POIs (checked every 5 s).</li>
 * </ul>
 */
final class XaeroBridge {
    private static int ticks;

    private XaeroBridge() {}

    static void init() {
        ClientLifecycleEvents.CLIENT_STARTED.register(mc -> {
            try {
                HudMod.INSTANCE.getRenderedPlayerTrackerManager().register("agentnet", new AgentTracker());
                AgentNet.LOG.info("Agents will show on Xaero's maps");
            } catch (RuntimeException | LinkageError e) {
                AgentNet.LOG.warn("Could not hook into Xaero's Minimap; agents won't show on it", e);
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 100 != 0) return;
            try {
                syncPois(mc);
            } catch (RuntimeException | LinkageError e) {
                AgentNet.LOG.debug("Xaero waypoint sync failed", e);
            }
        });
    }

    /** Online agents on the server you're on, for Xaero to draw. */
    private static final class AgentTracker implements IRenderedPlayerTracker<Agent> {
        private final ITrackedPlayerReader<Agent> reader = new ITrackedPlayerReader<>() {
            @Override public UUID getId(Agent a) { return a.uuid(); }
            @Override public double getX(Agent a) { return a.x(); }
            @Override public double getY(Agent a) { return a.y(); }
            @Override public double getZ(Agent a) { return a.z(); }
            @Override public ResourceKey<Level> getDimension(Agent a) { return a.dimensionKey(); }
        };

        @Override public ITrackedPlayerReader<Agent> getReader() { return reader; }

        @Override
        public Iterator<Agent> getTrackedPlayerIterator() {
            String server = Servers.current();
            if (server == null) return java.util.Collections.emptyIterator();
            return AgentNetClient.AGENTS.values().stream()
                    .filter(a -> a.isOnline() && a.dimensionKey() != null && a.server().equals(server))
                    .toList().iterator();
        }
    }

    /** Waypoints named "...POI" in the world you're in become POIs for that dimension. */
    private static void syncPois(Minecraft mc) {
        String server = Servers.current();
        if (server == null || mc.level == null) return;
        MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
        if (session == null) return;
        MinimapWorld world = session.getWorldManager().getCurrentWorld();
        if (world == null) return;
        ResourceKey<Level> dim = world.getDimId() != null ? world.getDimId() : mc.level.dimension();

        List<Pois.Waypoint> found = new ArrayList<>();
        for (WaypointSet set : world.getIterableWaypointSets()) {
            for (Waypoint w : set.getWaypoints()) {
                String name = w.getName() == null ? "" : w.getName().trim();
                if (w.isTemporary() || !name.toUpperCase().endsWith("POI")) continue;
                found.add(new Pois.Waypoint(name, w.getX(), w.getY(), w.getZ()));
            }
        }
        int changed = Pois.syncFromXaero(server, dim.identifier().toString(), found);
        if (changed > 0) AgentNetClient.notify("§7POIs updated from Xaero waypoints (" + found.size() + " in this dimension)");
    }
}
