package com.messagetriggers.e2e.mixin;

import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Exposes the chat HUD's backing message list so the driver can assert on
 * streamed pushes: a localStream line is edited in place (never passes
 * through addMessage, so ChatRecorderMixin cannot see it).
 */
@Mixin(ChatHud.class)
public interface ChatHudAccessor {
	@Accessor("messages")
	List<ChatHudLine> getMessages();
}
