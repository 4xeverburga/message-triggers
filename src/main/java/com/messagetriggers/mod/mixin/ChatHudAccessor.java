package com.messagetriggers.mod.mixin;

import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Exposes the chat HUD's backing message list so the bridge can stream:
 * a localStream push re-renders its ONE line in place by replacing the
 * previous ChatHudLine instead of stacking a new message per chunk.
 */
@Mixin(ChatHud.class)
public interface ChatHudAccessor {
	@Accessor("messages")
	List<ChatHudLine> getMessages();
}
