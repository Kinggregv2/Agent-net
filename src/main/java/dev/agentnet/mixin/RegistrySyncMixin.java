package dev.agentnet.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Your own world opened to LAN: Fabric API turns away every player without Fabric when a mod adds registry entries
 * ("This server requires Fabric Loader and Fabric API installed on your client!"). The agents run in this same game,
 * so they already have the same blocks and items with the same ids: no sync needed. Only they skip it.
 */
@Mixin(targets = "net.fabricmc.fabric.impl.registry.sync.RegistrySyncManager", remap = false)
public class RegistrySyncMixin {
    @Inject(method = "configureClient", at = @At("HEAD"), cancellable = true, require = 0)
    private static void agentnet$agentsNeedNoSync(ServerConfigurationPacketListenerImpl handler, MinecraftServer server, CallbackInfo ci) {
        // -Dagentnet.lanSkipSync=true: everyone skips it (agents of another AgentNet player on your network, who aren't
        // in this client's list). Off by default: a vanilla client in a world with modded blocks would go wrong.
        if (Boolean.getBoolean("agentnet.lanSkipSync") || dev.agentnet.AgentNetClient.isAgent(handler.getOwner().name())) ci.cancel();
    }
}
