package dev.agentnet.map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.HttpUtil;

/**
 * One stable name per server, used to keep POIs, chests etc. apart between servers; and the address agents join.
 * Works on a multiplayer server and on a world you host yourself (singleplayer, opened to LAN for the agents).
 */
public final class Servers {
    private Servers() {}

    /** "Play.Example.com" and "play.example.com:25565" are the same server. Your own LAN world: one name per world. */
    public static String key(String address) {
        ServerAddress a = ServerAddress.parseString(address);
        IntegratedServer local = Minecraft.getInstance().getSingleplayerServer();
        if (local != null && isLocalHost(a.getHost()) && local.isPublished() && local.getPort() == a.getPort()) return localKey(local);
        return a.getHost().toLowerCase() + "_" + a.getPort();
    }

    /** The server you're on now, or null. */
    public static String current() {
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getCurrentServer();
        IntegratedServer local = mc.getSingleplayerServer();
        if (local != null) return localKey(local);
        return server == null ? null : key(server.ip);
    }

    /** True while you're in a world (a server, someone's LAN world, or your own). */
    public static boolean inWorld() {
        Minecraft mc = Minecraft.getInstance();
        return mc.getCurrentServer() != null || mc.getSingleplayerServer() != null;
    }

    /**
     * Where agents join you, or null if you're not in a world. Your own world gets opened to LAN first (a free port)
     * with logins not checked against Mojang, so the agents (offline names) can come in, and with room for more than
     * the usual 8 players (see IntegratedServerMixin).
     */
    public static String joinAddress() {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer local = mc.getSingleplayerServer();
        if (local != null) {
            if (!local.isPublished()) {
                int port = HttpUtil.isPortAvailable(25565) ? 25565 : HttpUtil.getAvailablePort();
                if (!local.publishServer(MinecraftServer.MultiplayerScope.LAN, port)) return null;
                notify("§e[AgentNet] opened this world to LAN on port " + local.getPort() + " so agents can join");
            }
            if (local.usesAuthentication()) {
                local.setUsesAuthentication(false);
                notify("§e[AgentNet] LAN logins are no longer checked against Mojang (agents have no accounts): anyone on your network can join by name until you leave the world");
            }
            return "127.0.0.1:" + local.getPort();
        }
        var server = mc.getCurrentServer();
        return server == null ? null : server.ip;
    }

    /** Agents are on the world this client is in (your own one or the same server), so registries can be shared. */
    public static boolean sameAsYours(String address) {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer local = mc.getSingleplayerServer();
        ServerAddress a = ServerAddress.parseString(address);
        if (local != null) return isLocalHost(a.getHost()) && local.isPublished() && local.getPort() == a.getPort();
        var server = mc.getCurrentServer();
        if (server == null) return false;
        ServerAddress b = ServerAddress.parseString(server.ip);
        return a.getHost().equalsIgnoreCase(b.getHost()) && a.getPort() == b.getPort();
    }

    private static boolean isLocalHost(String h) {
        return h.equals("127.0.0.1") || h.equalsIgnoreCase("localhost") || h.equals("::1") || h.equals("0.0.0.0");
    }

    private static String localKey(IntegratedServer s) {
        return "world_" + s.getWorldData().getLevelName().toLowerCase().replaceAll("[^a-z0-9_.-]", "_");
    }

    private static void notify(String msg) {
        dev.agentnet.AgentNetClient.notify(msg);
    }
}
