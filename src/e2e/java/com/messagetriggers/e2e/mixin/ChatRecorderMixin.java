package com.messagetriggers.e2e.mixin;

import com.messagetriggers.e2e.E2EDriverMod;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records every chat line rendered in the HUD — the SAME injection point as
 * the production ChatHudMixin (three-arg addMessage), so what the driver
 * sees is exactly what the bridge sees. Test-only; never shipped.
 */
@Mixin(ChatHud.class)
public abstract class ChatRecorderMixin {
	@Inject(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V", at = @At("HEAD"))
	private void e2edriver$onChatMessage(Text message, net.minecraft.network.message.MessageSignatureData signature, net.minecraft.client.gui.hud.MessageIndicator indicator, CallbackInfo ci) {
		E2EDriverMod.record(message.getString());
	}
}
