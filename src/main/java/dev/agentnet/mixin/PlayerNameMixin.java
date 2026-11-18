package dev.agentnet.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * Our agents' names (over their heads, in chat, in the tab list) in their role's color: miners grey, farmers green,
 * warriors red... Real players get a gold star and a gold name. Only on this client; the server and other players see plain names.
 */
@Mixin(Player.class)
public abstract class PlayerNameMixin {
    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true, require = 0)
    private void agentnet$roleColor(CallbackInfoReturnable<Component> cir) {
        Player self = (Player) (Object) this;
        int color = dev.agentnet.AgentNetClient.roleColor(self.getGameProfile().name());
        if (cir.getReturnValue() == null) return;
        // Only names drawn on this screen. On your own world the built-in server asks too (for chat and join messages to
        // everyone), and other players saw your agents with a star.
        if (!self.level().isClientSide()) return;
        if (!dev.agentnet.AgentNetClient.isAgent(self.getGameProfile().name())) {
            // A real player: a gold star and a gold name, so a person stands out from the crew at a glance.
            cir.setReturnValue(Component.literal("\u2605 ").withColor(0xFFD700).append(cir.getReturnValue().copy().withColor(0xFFD700)));
            return;
        }
        if (color < 0) return;
        cir.setReturnValue(cir.getReturnValue().copy().withColor(color));
    }
}
