package com.messagetriggers.mod.mixin;

import net.minecraft.client.gui.hud.ChatHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Rebuilds the chat HUD's rendered (wrapped) view after the bridge edits
 * the backing message list — refresh() is private in vanilla.
 */
@Mixin(ChatHud.class)
public interface ChatHudInvoker {
	@Invoker("refresh")
	void invokeRefresh();
}
