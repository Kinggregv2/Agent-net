package dev.agentnet;

import java.util.Optional;

import dev.agentnet.mixin.MinecraftAccessor;
import dev.agentnet.role.Role;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/**
 * You take over an agent: the agent's bot connection closes and your client reconnects
 * to the server under the agent's name, so you play that exact player (same position,
 * inventory, health). Releasing reconnects you as yourself and puts the bot back with
 * its role, importance and task.
 *
 * <p>Triggered automatically when an agent drops below half health (toggle with
 * /agent takeover on|off), or by hand with /agent control <name>.
 * Main thread only.
 */
public final class Takeover {
    /** Take over automatically when an agent is under half health. */
    static boolean auto = false; // agents eat or ask for food by themselves; take over by hand if you want

    private static User yourUser;      // your own account, while you're someone else
    private static String controlled;  // the agent you're playing
    private static String address;
    private static Role role;
    private static int importance;
    private static String task;

    private Takeover() {}

    public static boolean active() { return controlled != null; }

    /** Your real name, even while you're playing an agent. */
    static String yourName() {
        return yourUser != null ? yourUser.getName() : Minecraft.getInstance().getUser().getName();
    }

    /** The agent you're playing, or null. */
    static String controlledName() { return controlled; }

    static void onLowHealth(Agent a) {
        if (a.isClosed()) return;
        String hp = String.format("%.0f/%.0f", a.health(), a.maxHealth());
        // A pearl in a stasis chamber and a pearl puller online: pull it instead of taking over.
        var pearl = dev.agentnet.map.Pearls.get(dev.agentnet.map.Servers.key(a.address), a.name);
        var puller = pearl.isEmpty() ? java.util.Optional.<dev.agentnet.role.AgentHandle>empty()
                : dev.agentnet.bus.AgentBus.closest(dev.agentnet.role.Role.PEARLER, a);
        if (puller.isPresent()) {
            AgentNetClient.notify("§c[" + a.name + "] low health " + hp + " - " + puller.get().name() + " is pulling its pearl");
            dev.agentnet.bus.AgentBus.send(dev.agentnet.bus.Message.of(a.name, puller.get().name(), dev.agentnet.bus.Message.Type.PEARL_PULL,
                    "pull my pearl", java.util.Map.of("agent", a.name)));
            return;
        }
        if (pearl.isPresent()) AgentNetClient.notify("§7[" + a.name + "] has a pearl set, but no pearl puller is online");
        if (!auto) {
            AgentNetClient.notify("§e[" + a.name + "] low health " + hp + " - eating to heal up");
            return;
        }
        if (active()) {
            AgentNetClient.notify("§c[" + a.name + "] low health " + hp + ", but you're already playing " + controlled);
            return;
        }
        AgentNetClient.notify("§c[" + a.name + "] low health " + hp + " - taking control");
        start(a);
    }

    /** Become this agent. Returns false (with a chat message) if that's not possible. */
    static boolean start(Agent a) {
        Minecraft mc = Minecraft.getInstance();
        if (active()) { AgentNetClient.notify("§cYou're already playing " + controlled + "; /agent release first"); return false; }
        if (!a.isOnline()) { AgentNetClient.notify("§c" + a.name + " isn't online"); return false; }
        if (Minecraft.getInstance().getSingleplayerServer() != null) { AgentNetClient.notify("§cTaking over an agent needs a server: leaving your own world would shut it down for the agents too"); return false; }

        controlled = a.name;
        address = a.address;
        role = a.role();
        importance = a.importance;
        task = a.task;
        AgentNetClient.removeAgent(a);

        yourUser = mc.getUser();
        setUser(new User(a.name, a.uuid(), "", Optional.empty(), Optional.empty()));
        reconnect(address, "Taking control of " + a.name);
        return true;
    }

    /** Go back to being yourself; the agent rejoins as a bot. */
    static boolean release() {
        if (!active()) { AgentNetClient.notify("§cYou're not controlling an agent"); return false; }
        String name = controlled, addr = address;
        setUser(yourUser);
        yourUser = null;
        controlled = null;
        reconnect(addr, "Returning to yourself");
        // Give the server a moment to drop your session as the agent before the bot rejoins.
        AgentNetClient.rejoin(name, addr, role, importance, task, 3000);
        return true;
    }

    private static void setUser(User user) {
        ((MinecraftAccessor) Minecraft.getInstance()).agentnet$setUser(user);
    }

    private static void reconnect(String addr, String reason) {
        Minecraft mc = Minecraft.getInstance();
        ServerData current = mc.getCurrentServer();
        ServerData data = current != null ? current : new ServerData(addr, addr, ServerData.Type.OTHER);
        mc.disconnectFromWorld(Component.literal(reason));
        mc.execute(() -> ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(addr), data, false, null));
    }
}
