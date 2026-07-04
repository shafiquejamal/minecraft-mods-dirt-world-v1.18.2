package com.github.shafiquejamal.dirtworld

import com.github.shafiquejamal.dirtworld.client.DirtWorldClientEvents
import com.github.shafiquejamal.dirtworld.world.DirtWorldWorldEvents
import com.mojang.logging.LogUtils
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.loading.FMLEnvironment
import org.slf4j.Logger

@Mod(DirtWorldMod.MOD_ID)
class DirtWorldMod {
    companion object {
        const val MOD_ID: String = "dirt_world"
        private val LOGGER: Logger = LogUtils.getLogger()
    }

    init {
        MinecraftForge.EVENT_BUS.register(DirtWorldWorldEvents)
        if (FMLEnvironment.dist == Dist.CLIENT) {
            MinecraftForge.EVENT_BUS.register(DirtWorldClientEvents)
        }
        LOGGER.info("Initializing {} Kotlin Forge bootstrap", MOD_ID)
    }
}
