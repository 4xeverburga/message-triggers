package com.messagetriggers.mod.mixin;

import com.messagetriggers.mod.MessageTriggersMod;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes every chat line rendered in the HUD. The bridge only acts on
 * lines shaped "<player> message" (the same contract as the ChatTriggers
 * chat criteria), so the bridge's own local echoes never re-trigger it.
 *
 * Injection target note (verified against the yarn-mapped 1.21.1 jar):
 * incoming network chat calls the THREE-arg addMessage(Text, signature,
 * indicator) directly; the one-arg addMessage(Text) is only a convenience
 * wrapper that delegates to it. Hooking the three-arg overload therefore
 * sees every chat line exactly once — hooking the one-arg would miss all
 * server chat.
 */
@Mixin(ChatHud.class)
public abstract class ChatHudMixin {
	@Inject(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V", at = @At("HEAD"))
	private void messagetriggers$onChatMessage(Text message, net.minecraft.network.message.MessageSignatureData signature, net.minecraft.client.gui.hud.MessageIndicator indicator, CallbackInfo ci) {
		MessageTriggersMod.onChatLine(message.getString());
	}
}
