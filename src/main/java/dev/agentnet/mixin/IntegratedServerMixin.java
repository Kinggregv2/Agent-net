package dev.agentnet.mixin;

import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Your own world opened to LAN takes 8 players; a crew needs more. */
@Mixin(IntegratedServer.class)
public class IntegratedServerMixin {
    @Inject(method = "getMaxPlayers", at = @At("HEAD"), cancellable = true)
    private void agentnet$roomForTheCrew(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(Math.max(8, dev.agentnet.AgentNetClient.agentCount() + 8));
    }
}
