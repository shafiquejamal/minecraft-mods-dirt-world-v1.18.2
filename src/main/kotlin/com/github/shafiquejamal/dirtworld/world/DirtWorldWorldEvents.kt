package com.github.shafiquejamal.dirtworld.world

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
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
    private const val MAX_CHUNKS_CONVERTED_PER_TICK: Int = 1

    private val preservedBlocks = setOf(
        Blocks.BEDROCK,
        Blocks.DIRT,
        Blocks.END_PORTAL,
        Blocks.GRAVEL,
        Blocks.LAVA,
        Blocks.WATER,
    )

    private val preservedStructures = setOf(
        StructureFeature.PILLAGER_OUTPOST,
        StructureFeature.STRONGHOLD,
        StructureFeature.VILLAGE,
    )

    private val processedChunks: MutableSet<ProcessedChunkKey> = ConcurrentHashMap.newKeySet()
    private val pendingChunks: MutableMap<ProcessedChunkKey, PendingChunk> = ConcurrentHashMap()

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
        if (processedChunks.contains(key)) {
            return
        }

        pendingChunks.putIfAbsent(key, PendingChunk(level, event.chunk))
    }

    @SubscribeEvent
    fun onChunkUnload(event: ChunkEvent.Unload) {
        val level = event.world as? ServerLevel ?: return
        pendingChunks.remove(chunkKey(level, event.chunk.pos))
    }

    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END || !event.haveTime()) {
            return
        }

        var convertedChunks = 0
        val iterator = pendingChunks.entries.iterator()
        while (iterator.hasNext() && convertedChunks < MAX_CHUNKS_CONVERTED_PER_TICK) {
            val (key, pendingChunk) = iterator.next()
            pendingChunks.remove(key, pendingChunk)

            if (processedChunks.contains(key)) {
                continue
            }

            convertChunk(pendingChunk.level, pendingChunk.chunk)
            processedChunks.add(key)
            pendingChunk.chunk.setUnsaved(true)
            convertedChunks++
        }
    }

    @SubscribeEvent
    @Suppress("UNUSED_PARAMETER")
    fun onServerStopped(_event: ServerStoppedEvent) {
        processedChunks.clear()
        pendingChunks.clear()
    }

    private fun convertChunk(level: ServerLevel, chunk: ChunkAccess) {
        val dirtState = Blocks.DIRT.defaultBlockState()
        val protectedStructureBoxes = collectProtectedStructureBoxes(level, chunk)
        val protectedSpikes = if (level.dimension() == Level.END) SpikeFeature.getSpikesForLevel(level) else emptyList()
        val mutablePos = BlockPos.MutableBlockPos()
        val neighborPos = BlockPos.MutableBlockPos()
        val minX = chunk.pos.minBlockX
        val minZ = chunk.pos.minBlockZ

        for (section in chunk.sections) {
            if (section.hasOnlyAir()) {
                continue
            }

            val minY = section.bottomBlockY()
            for (localY in 0 until 16) {
                val worldY = minY + localY
                for (localZ in 0 until 16) {
                    val worldZ = minZ + localZ
                    for (localX in 0 until 16) {
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
                    }
                }
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

        if (isInsideProtectedStructure(protectedStructureBoxes, x, y, z)) {
            return true
        }

        return level.dimension() == Level.END && isInsideProtectedEndSpike(protectedSpikes, x, y, z)
    }

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

    private data class PendingChunk(
        val level: ServerLevel,
        val chunk: ChunkAccess,
    )
}
