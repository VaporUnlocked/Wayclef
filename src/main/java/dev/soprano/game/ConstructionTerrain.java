package dev.soprano.game;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.attribute.EnvironmentAttributeReader;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;
import java.util.Map;

/** Read-only view used to test footing, exits and survival after proposed block changes. */
record ConstructionTerrain(ClientLevel level, Map<BlockPos, BlockState> changes) implements LevelReader {
    ConstructionTerrain(ClientLevel level, BlockPos position, BlockState replacement) {
        this(level, Map.of(position, replacement));
    }

    ConstructionTerrain {
        changes = Map.copyOf(changes);
    }

    @Override public BlockState getBlockState(BlockPos at) {
        BlockState replacement = changes.get(at);
        return replacement == null ? level.getBlockState(at) : replacement;
    }

    @Override public FluidState getFluidState(BlockPos at) { return getBlockState(at).getFluidState(); }
    @Override public BlockEntity getBlockEntity(BlockPos at) { return changes.containsKey(at) ? null : level.getBlockEntity(at); }
    @Override public BlockGetter getChunkForCollisions(int x, int z) { return hasChunk(x, z) ? this : null; }
    @Override public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean required) {
        return level.getChunk(x, z, status, required);
    }

    @Override public List<VoxelShape> getEntityCollisions(Entity entity, AABB bounds) {
        return level.getEntityCollisions(entity, bounds);
    }

    @Override public boolean hasChunk(int x, int z) { return level.hasChunk(x, z); }
    @Override public int getHeight(Heightmap.Types type, int x, int z) { return level.getHeight(type, x, z); }
    @Override public int getSkyDarken() { return level.getSkyDarken(); }
    @Override public BiomeManager getBiomeManager() { return level.getBiomeManager(); }
    @Override public Holder<Biome> getNoiseBiome(int x, int y, int z) { return level.getNoiseBiome(x, y, z); }
    @Override public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
        return level.getUncachedNoiseBiome(x, y, z);
    }

    @Override public boolean isClientSide() { return true; }
    @Override public int getSeaLevel() { return level.getSeaLevel(); }
    @Override public DimensionType dimensionType() { return level.dimensionType(); }
    @Override public RegistryAccess registryAccess() { return level.registryAccess(); }
    @Override public FeatureFlagSet enabledFeatures() { return level.enabledFeatures(); }
    @Override public EnvironmentAttributeReader environmentAttributes() { return level.environmentAttributes(); }
    @Override public LevelLightEngine getLightEngine() { return level.getLightEngine(); }
    @Override public WorldBorder getWorldBorder() { return level.getWorldBorder(); }
}
