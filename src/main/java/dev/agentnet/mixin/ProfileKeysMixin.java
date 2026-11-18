package dev.agentnet.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.agentnet.Takeover;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;

/**
 * While you play an agent, don't present your real account's chat-signing keys: they
 * belong to a different player, and the server would reject them.
 */
@Mixin(Minecraft.class)
public abstract class ProfileKeysMixin {
    @Inject(method = "getProfileKeyPairManager", at = @At("HEAD"), cancellable = true)
    private void agentnet$noKeysWhileControlling(CallbackInfoReturnable<ProfileKeyPairManager> cir) {
        if (Takeover.active()) cir.setReturnValue(ProfileKeyPairManager.EMPTY_KEY_MANAGER);
    }
}
