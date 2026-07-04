package com.github.shafiquejamal.dirtworld.client

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.toasts.SystemToast
import net.minecraft.network.chat.TextComponent
import net.minecraftforge.client.event.ClientPlayerNetworkEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

object DirtWorldClientEvents {
    private const val TOAST_TEXT: String = "All the blocks are turning into dirt. Place a new block to restore life. But that block cannot be dirt."

    private var shouldShowToast: Boolean = true

    @SubscribeEvent
    fun onPlayerLoggedIn(event: ClientPlayerNetworkEvent.LoggedInEvent) {
        if (!shouldShowToast) {
            return
        }

        shouldShowToast = false
        val minecraft = Minecraft.getInstance()
        SystemToast.add(
            minecraft.toasts,
            SystemToast.SystemToastIds.TUTORIAL_HINT,
            TextComponent("Dirt World"),
            TextComponent(TOAST_TEXT),
        )
    }

    @SubscribeEvent
    fun onPlayerLoggedOut(event: ClientPlayerNetworkEvent.LoggedOutEvent) {
        shouldShowToast = true
    }
}
