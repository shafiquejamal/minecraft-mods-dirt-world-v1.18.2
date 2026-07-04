package com.github.shafiquejamal.dirtworld.world

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
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
import net.minecraftforge.event.world.ChunkDataEvent
import net.minecraftforge.event.world.ChunkEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import java.util.concurrent.ConcurrentHashMap

object DirtWorldWorldEvents {
    private const val PROCESSED_CHUNK_KEY: String = "dirt_world_processed"
    private const val CONVERSION_CHECK_INTERVAL_TICKS: Int = 100
    private const val MAX_BLOCKS_SCANNED_PER_TICK: Int = 8192
    private const val MAX_BLOCKS_CHANGED_PER_TICK: Int = 512

    private val preservedBlocks = setOf(
        Blocks.BEDROCK,
        Blocks.DIRT,
        Blocks.END_PORTAL,
        Blocks.GRAVEL,
        Blocks.LAVA,
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
    )

    private val preservedStructures = setOf(
        StructureFeature.PILLAGER_OUTPOST,
        StructureFeature.STRONGHOLD,
        StructureFeature.VILLAGE,
    )

    private val processedChunks: MutableSet<ProcessedChunkKey> = ConcurrentHashMap.newKeySet()
    private val loadedChunks: MutableMap<ProcessedChunkKey, LoadedChunk> = ConcurrentHashMap()
    private val conversionTasks: MutableMap<ProcessedChunkKey, ChunkConversionTask> = ConcurrentHashMap()
    private var serverTickCount: Int = 0

    @SubscribeEvent
    fun onChunkDataLoad(event: ChunkDataEvent.Load) {
        val level = event.world as? ServerLevel ?: return
        if (event.data.getBoolean(PROCESSED_CHUNK_KEY)) {
            processedChunks.add(chunkKey(level, event.chunk.pos))
        }
    }

    @SubscribeEvent
    fun onChunkDataSave(event: ChunkDataEvent.Save) {
        val level = event.world as? ServerLevel ?: return
        val key = chunkKey(level, event.chunk.pos)
        if (processedChunks.contains(key)) {
            event.data.putBoolean(PROCESSED_CHUNK_KEY, true)
        }
    }

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

        serverTickCount++
        if (serverTickCount % CONVERSION_CHECK_INTERVAL_TICKS == 0) {
            queueLoadedChunksForConversion()
        }

        processConversionTasks()
    }

    @SubscribeEvent
    @Suppress("UNUSED_PARAMETER")
    fun onServerStopped(_event: ServerStoppedEvent) {
        processedChunks.clear()
        loadedChunks.clear()
        conversionTasks.clear()
        serverTickCount = 0
    }

    private fun queueLoadedChunksForConversion() {
        for ((key, loadedChunk) in loadedChunks) {
            if (processedChunks.contains(key) || conversionTasks.containsKey(key)) {
                continue
            }

            conversionTasks[key] = ChunkConversionTask(
                loadedChunk.level,
                loadedChunk.chunk,
                collectProtectedStructureBoxes(loadedChunk.level, loadedChunk.chunk),
                if (loadedChunk.level.dimension() == Level.END) SpikeFeature.getSpikesForLevel(loadedChunk.level) else emptyList(),
            )
        }
    }

    private fun processConversionTasks() {
        var scannedBlocks = 0
        var changedBlocks = 0
        val iterator = conversionTasks.entries.iterator()

        while (iterator.hasNext() && scannedBlocks < MAX_BLOCKS_SCANNED_PER_TICK && changedBlocks < MAX_BLOCKS_CHANGED_PER_TICK) {
            val (key, task) = iterator.next()
            if (!loadedChunks.containsKey(key)) {
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
                processedChunks.add(key)
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

    private fun hasVisibleFace(
        level: ServerLevel,
        chunk: ChunkAccess,
        pos: BlockPos,
        neighborPos: BlockPos.MutableBlockPos,
    ): Boolean {
        for (direction in Direction.values()) {
            neighborPos.set(pos.x + direction.stepX, pos.y + direction.stepY, pos.z + direction.stepZ)
            if (neighborPos.y < level.minBuildHeight || neighborPos.y >= level.maxBuildHeight) {
                return true
            }

            val neighborState = if (isInsideChunk(chunk, neighborPos)) {
                chunk.getBlockState(neighborPos)
            } else if (level.isLoaded(neighborPos)) {
                level.getBlockState(neighborPos)
            } else {
                continue
            }

            if (neighborState.isAir) {
                return true
            }
        }

        return false
    }

    private fun isInsideChunk(chunk: ChunkAccess, pos: BlockPos): Boolean =
        pos.x in chunk.pos.minBlockX..(chunk.pos.minBlockX + 15) &&
            pos.z in chunk.pos.minBlockZ..(chunk.pos.minBlockZ + 15)

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
        private val neighborPos = BlockPos.MutableBlockPos()
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
                    if (!hasVisibleFace(level, chunk, mutablePos, neighborPos)) {
                        continue
                    }

                    if (state.hasBlockEntity()) {
                        chunk.removeBlockEntity(mutablePos)
                    }
                    chunk.setBlockState(mutablePos, dirtState, false)
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
