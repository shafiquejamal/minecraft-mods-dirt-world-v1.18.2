package com.github.shafiquejamal.dirtworld.world

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.levelgen.feature.ConfiguredStructureFeature
import net.minecraft.world.level.levelgen.feature.SpikeFeature
import net.minecraft.world.level.levelgen.feature.StructureFeature
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.levelgen.structure.StructureStart
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.world.ChunkEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.util.concurrent.ConcurrentHashMap

object DirtWorldWorldEvents {
    private const val MAX_BLOCKS_SCANNED_PER_TICK: Int = 65536
    private const val MAX_BLOCKS_CHANGED_PER_TICK: Int = 65536

    private val preservedBlocks = setOf(
        Blocks.BEDROCK,
        Blocks.DIRT,
        Blocks.END_PORTAL,
        Blocks.END_PORTAL_FRAME,
        Blocks.GRAVEL,
        Blocks.LAVA,
        Blocks.OBSIDIAN,
        Blocks.WATER,
    )

    private val dirtConversionDenyList = setOf(
        Blocks.GRASS,
        Blocks.TALL_GRASS,
        Blocks.FERN,
        Blocks.LARGE_FERN,
        Blocks.DEAD_BUSH,
        Blocks.BAMBOO,
        Blocks.BAMBOO_SAPLING,
        Blocks.SWEET_BERRY_BUSH,
        Blocks.BROWN_MUSHROOM,
        Blocks.RED_MUSHROOM,
        Blocks.CRIMSON_FUNGUS,
        Blocks.WARPED_FUNGUS,
        Blocks.CRIMSON_ROOTS,
        Blocks.WARPED_ROOTS,
        Blocks.NETHER_SPROUTS,
        Blocks.VINE,
        Blocks.WEEPING_VINES,
        Blocks.WEEPING_VINES_PLANT,
        Blocks.TWISTING_VINES,
        Blocks.TWISTING_VINES_PLANT,
        Blocks.CAVE_VINES,
        Blocks.CAVE_VINES_PLANT,
        Blocks.HANGING_ROOTS,
        Blocks.SMALL_DRIPLEAF,
        Blocks.BIG_DRIPLEAF,
        Blocks.BIG_DRIPLEAF_STEM,
        Blocks.LILY_PAD,
        Blocks.SEAGRASS,
        Blocks.TALL_SEAGRASS,
        Blocks.KELP,
        Blocks.KELP_PLANT,
        Blocks.SNOW,
        Blocks.POWDER_SNOW,
    )

    private val preservedStructures = setOf(
        StructureFeature.PILLAGER_OUTPOST,
        StructureFeature.STRONGHOLD,
        StructureFeature.VILLAGE,
    )

    private val loadedChunks: MutableMap<ProcessedChunkKey, LoadedChunk> = ConcurrentHashMap()
    private val conversionTasks: MutableMap<ProcessedChunkKey, ChunkConversionTask> = ConcurrentHashMap()
    private val playerLastChunkPos: MutableMap<String, ChunkPos> = ConcurrentHashMap()

    @SubscribeEvent
    fun onChunkLoad(event: ChunkEvent.Load) {
        val level = event.world as? ServerLevel ?: return
        val key = chunkKey(level, event.chunk.pos)
        loadedChunks[key] = LoadedChunk(level, event.chunk)
    }

    @SubscribeEvent
    fun onChunkUnload(event: ChunkEvent.Unload) {
        val level = event.world as? ServerLevel ?: return
        val key = chunkKey(level, event.chunk.pos)
        loadedChunks.remove(key)
        conversionTasks.remove(key)
    }

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END) {
            return
        }

        processConversionTasks()
    }

    @SubscribeEvent
    fun onWorldTick(event: TickEvent.WorldTickEvent) {
        if (event.phase != TickEvent.Phase.END) {
            return
        }
        
        val level = event.world as? ServerLevel ?: return
        
        // Check for player chunk changes and queue conversion instantly
        for (player in level.players()) {
            val currentChunkPos = player.chunkPosition()
            val playerId = player.stringUUID
            val lastChunkPos = playerLastChunkPos[playerId]
            
            if (lastChunkPos == null || lastChunkPos != currentChunkPos) {
                playerLastChunkPos[playerId] = currentChunkPos
                queueChunksAroundPlayerCircular(level, currentChunkPos)
            }
        }
    }

    @SubscribeEvent
    @Suppress("UNUSED_PARAMETER")
    fun onServerStopped(_event: ServerStoppedEvent) {
        loadedChunks.clear()
        conversionTasks.clear()
        playerLastChunkPos.clear()
    }

    private fun queueChunksAroundPlayerCircular(level: ServerLevel, playerChunkPos: ChunkPos) {
        // Get simulation distance from server settings
        val simulationDistance = level.server.playerList.simulationDistance
        
        // Collect chunks in circular pattern, prioritized by distance
        val chunksToConvert = mutableListOf<Pair<ChunkPos, Double>>()
        
        for (dx in -simulationDistance..simulationDistance) {
            for (dz in -simulationDistance..simulationDistance) {
                val chunkX = playerChunkPos.x + dx
                val chunkZ = playerChunkPos.z + dz
                
                // Calculate distance for circular pattern
                val distance = kotlin.math.sqrt((dx * dx + dz * dz).toDouble())
                
                // Only include chunks within circular radius
                if (distance <= simulationDistance) {
                    val chunkPos = ChunkPos(chunkX, chunkZ)
                    chunksToConvert.add(Pair(chunkPos, distance))
                }
            }
        }
        
        // Sort by distance (closest first) for priority conversion
        chunksToConvert.sortBy { it.second }
        
        // Queue all chunks in priority order
        for ((chunkPos, _) in chunksToConvert) {
            val key = chunkKey(level, chunkPos)
            val loadedChunk = loadedChunks[key]
            
            if (loadedChunk != null) {
                // Always re-queue to ensure conversion happens
                conversionTasks[key] = ChunkConversionTask(
                    level,
                    loadedChunk.chunk,
                    collectProtectedStructureBoxes(level, loadedChunk.chunk),
                    if (level.dimension() == Level.END) SpikeFeature.getSpikesForLevel(level) else emptyList(),
                )
            }
        }
    }

    private fun processConversionTasks() {
        var scannedBlocks = 0
        var changedBlocks = 0
        val iterator = conversionTasks.entries.iterator()

        while (iterator.hasNext() && scannedBlocks < MAX_BLOCKS_SCANNED_PER_TICK && changedBlocks < MAX_BLOCKS_CHANGED_PER_TICK) {
            val (key, task) = iterator.next()
            val loadedChunk = loadedChunks[key]
            if (loadedChunk == null) {
                conversionTasks.remove(key, task)
                continue
            }

            val result = task.process(
                MAX_BLOCKS_SCANNED_PER_TICK - scannedBlocks,
                MAX_BLOCKS_CHANGED_PER_TICK - changedBlocks,
            )
            scannedBlocks += result.scannedBlocks
            changedBlocks += result.changedBlocks

            if (result.isComplete) {
                task.chunk.setUnsaved(true)
                conversionTasks.remove(key, task)
            } else if (result.changedBlocks > 0) {
                task.chunk.setUnsaved(true)
            }
        }
    }

    private fun shouldPreserveBlock(
        level: ServerLevel,
        state: BlockState,
        protectedStructureBoxes: Collection<BoundingBox>,
        protectedSpikes: List<SpikeFeature.EndSpike>,
        x: Int,
        y: Int,
        z: Int,
    ): Boolean {
        if (state.isAir) {
            return true
        }

        val pos = BlockPos(x, y, z)
        if (state.isCollisionShapeFullBlock(level, pos).not() || !state.fluidState.isEmpty) {
            return true
        }

        if (state.block in preservedBlocks) {
            return true
        }

        if (isDeniedDirtConversion(state)) {
            return true
        }

        if (isInsideProtectedStructure(protectedStructureBoxes, x, y, z)) {
            return true
        }

        return level.dimension() == Level.END && isInsideProtectedEndSpike(protectedSpikes, x, y, z)
    }

    private fun isDeniedDirtConversion(state: BlockState): Boolean =
        state.block in dirtConversionDenyList ||
            state.`is`(BlockTags.FLOWERS) ||
            state.`is`(BlockTags.SAPLINGS) ||
            state.`is`(BlockTags.FLOWER_POTS) ||
            state.`is`(BlockTags.REPLACEABLE_PLANTS)

    private fun collectProtectedStructureBoxes(level: ServerLevel, chunk: ChunkAccess): Set<BoundingBox> {
        val protectedBoxes = linkedSetOf<BoundingBox>()

        for ((configuredFeature, structureStart) in chunk.allStarts) {
            addProtectedStructureBox(configuredFeature, structureStart, protectedBoxes)
        }

        val structureManager = level.structureFeatureManager()
        for ((configuredFeature, references) in chunk.allReferences) {
            if (!shouldPreserveStructure(configuredFeature) || references.isEmpty()) {
                continue
            }

            structureManager.fillStartsForFeature(configuredFeature, references) { structureStart: StructureStart ->
                addProtectedStructureBox(configuredFeature, structureStart, protectedBoxes)
            }
        }

        return protectedBoxes
    }

    private fun addProtectedStructureBox(
        configuredFeature: ConfiguredStructureFeature<*, *>,
        structureStart: StructureStart,
        protectedBoxes: MutableSet<BoundingBox>,
    ) {
        if (shouldPreserveStructure(configuredFeature) && structureStart.isValid) {
            protectedBoxes.add(structureStart.boundingBox)
        }
    }

    private fun shouldPreserveStructure(configuredFeature: ConfiguredStructureFeature<*, *>): Boolean =
        configuredFeature.feature in preservedStructures

    private fun isInsideProtectedStructure(boxes: Collection<BoundingBox>, x: Int, y: Int, z: Int): Boolean {
        val pos = BlockPos(x, y, z)
        return boxes.any { it.isInside(pos) }
    }

    private fun isInsideProtectedEndSpike(spikes: List<SpikeFeature.EndSpike>, x: Int, y: Int, z: Int): Boolean {
        for (spike in spikes) {
            val dx = x - spike.centerX
            val dz = z - spike.centerZ
            val radius = spike.radius + 2
            if (dx * dx + dz * dz <= radius * radius && y in 0..(spike.height + 8)) {
                return true
            }
        }

        return false
    }

    private fun chunkKey(level: ServerLevel, chunkPos: ChunkPos): ProcessedChunkKey =
        ProcessedChunkKey(level.dimension().location().toString(), chunkPos.toLong())

    private data class ProcessedChunkKey(
        val dimensionId: String,
        val chunkPos: Long,
    )

    private data class LoadedChunk(
        val level: ServerLevel,
        val chunk: ChunkAccess,
    )

    private class ChunkConversionTask(
        private val level: ServerLevel,
        val chunk: ChunkAccess,
        private val protectedStructureBoxes: Collection<BoundingBox>,
        private val protectedSpikes: List<SpikeFeature.EndSpike>,
    ) {
        private val dirtState = Blocks.DIRT.defaultBlockState()
        private val mutablePos = BlockPos.MutableBlockPos()
        private var sectionIndex: Int = 0
        private var blockIndex: Int = 0

        fun process(scanLimit: Int, changeLimit: Int): ConversionResult {
            var scannedBlocks = 0
            var changedBlocks = 0
            val minX = chunk.pos.minBlockX
            val minZ = chunk.pos.minBlockZ

            while (sectionIndex < chunk.sections.size && scannedBlocks < scanLimit && changedBlocks < changeLimit) {
                val section = chunk.sections[sectionIndex]
                if (section.hasOnlyAir()) {
                    sectionIndex++
                    blockIndex = 0
                    continue
                }

                val minY = section.bottomBlockY()
                while (blockIndex < BLOCKS_PER_SECTION && scannedBlocks < scanLimit && changedBlocks < changeLimit) {
                    val localX = blockIndex and 15
                    val localZ = (blockIndex shr 4) and 15
                    val localY = blockIndex shr 8
                    val worldY = minY + localY
                    val worldZ = minZ + localZ
                    blockIndex++
                    scannedBlocks++

                    val state = section.getBlockState(localX, localY, localZ)
                    if (shouldPreserveBlock(level, state, protectedStructureBoxes, protectedSpikes, minX + localX, worldY, worldZ)) {
                        continue
                    }

                    mutablePos.set(minX + localX, worldY, worldZ)
                    if (state.hasBlockEntity()) {
                        chunk.removeBlockEntity(mutablePos)
                    }
                    level.setBlock(mutablePos, dirtState, 2)
                    changedBlocks++
                }

                if (blockIndex >= BLOCKS_PER_SECTION) {
                    sectionIndex++
                    blockIndex = 0
                }
            }

            return ConversionResult(scannedBlocks, changedBlocks, sectionIndex >= chunk.sections.size)
        }

        private companion object {
            private const val BLOCKS_PER_SECTION: Int = 16 * 16 * 16
        }
    }

    private data class ConversionResult(
        val scannedBlocks: Int,
        val changedBlocks: Int,
        val isComplete: Boolean,
    )
}
