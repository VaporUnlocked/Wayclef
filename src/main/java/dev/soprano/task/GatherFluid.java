package dev.soprano.task;

import dev.soprano.game.MinecraftTerrain;
import dev.soprano.path.Node;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/** Finds bucketable sources while leaving waterlogged structures alone. */
final class GatherFluid {
    private record Source(BlockPos position, double distance) { }

    private static final int RADIUS = 24;
    private static final int STANCE_CHECKS = 32;
    private static final double REACH_MARGIN = .08;
    private static final Comparator<Source> CLOSEST = Comparator.comparingDouble(Source::distance)
        .thenComparingInt(source -> source.position().getX())
        .thenComparingInt(source -> source.position().getY())
        .thenComparingInt(source -> source.position().getZ());

    private final ClientLevel level;
    private final LocalPlayer player;

    GatherFluid(ClientLevel level, LocalPlayer player) {
        this.level = level;
        this.player = player;
    }

    static boolean supports(Item result) { return content(result) != null; }

    BlockPos nearest(Item result, BlockPos origin, Predicate<BlockPos> excluded) {
        Fluid fluid = content(result);
        if (fluid == null || !Node.validColumn(origin.getX()) || !Node.validColumn(origin.getZ())
            || level.isOutsideBuildHeight(origin)) return null;
        var area = WorldJob.nearbyArea(origin, RADIUS, level.getMinY(), level.getMaxY());
        PriorityQueue<Source> closest = new PriorityQueue<>(CLOSEST.reversed());
        var cursor = new BlockPos.MutableBlockPos();
        Vec3 feet = player.position();
        for (int chunkX = area.minX() >> 4; chunkX <= area.maxX() >> 4; chunkX++) {
            int west = Math.max(area.minX(), chunkX << 4), east = Math.min(area.maxX(), (chunkX << 4) + 15);
            for (int chunkZ = area.minZ() >> 4; chunkZ <= area.maxZ() >> 4; chunkZ++) {
                var chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                int north = Math.max(area.minZ(), chunkZ << 4), south = Math.min(area.maxZ(), (chunkZ << 4) + 15);
                for (int chunkY = area.minY() >> 4; chunkY <= area.maxY() >> 4; chunkY++) {
                    var section = chunk.getSections()[chunkY - (level.getMinY() >> 4)];
                    if (section.hasOnlyAir() || !section.maybeHas(state -> isSource(state, fluid))) continue;
                    int bottom = Math.max(area.minY(), chunkY << 4), top = Math.min(area.maxY(), (chunkY << 4) + 15);
                    for (int y = bottom; y <= top; y++) {
                        for (int x = west; x <= east; x++) {
                            for (int z = north; z <= south; z++) {
                                BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                                if (!isSource(state, fluid)) continue;
                                double distance = feet.distanceToSqr(x + .5, y + .5, z + .5);
                                if (closest.size() == STANCE_CHECKS && distance > closest.element().distance()) continue;
                                cursor.set(x, y, z);
                                if (!level.getWorldBorder().isWithinBounds(cursor) || !level.mayInteract(player, cursor)
                                    || !state.getCollisionShape(level, cursor).isEmpty() || !exposed(cursor)) continue;
                                BlockPos at = cursor.immutable();
                                if (excluded.test(at)) continue;
                                Source candidate = new Source(at, distance);
                                if (closest.size() == STANCE_CHECKS && CLOSEST.compare(candidate, closest.element()) >= 0) continue;
                                if (closest.size() == STANCE_CHECKS) closest.remove();
                                closest.add(candidate);
                            }
                        }
                    }
                }
            }
        }
        if (closest.isEmpty()) return null;
        var terrain = new MinecraftTerrain(level, player);
        var candidates = closest.stream().sorted(CLOSEST).toList();
        terrain.beginSearchSlice();
        try {
            for (Source candidate : candidates) {
                if (hasDryApproach(result, candidate.position(), terrain)) return candidate.position();
            }
        } finally {
            terrain.endSearchSlice();
        }
        return candidates.getFirst().position();
    }

    private boolean hasDryApproach(Item result, BlockPos source, MinecraftTerrain terrain) {
        if (player.onGround() && !player.isInWater() && !player.isInLava() && hit(result, source, player.getEyePosition()) != null) return true;
        double reach = player.blockInteractionRange();
        int radius = (int) Math.min(5, Math.ceil(reach));
        int lower = (source.getY() - radius - 1) * 16, upper = (source.getY() + radius) * 16;
        double eyeHeight = player.getEyeHeight(Pose.STANDING);
        for (int x = source.getX() - radius; x <= source.getX() + radius; x++) {
            for (int z = source.getZ() - radius; z <= source.getZ() + radius; z++) {
                if (!Node.validColumn(x) || !Node.validColumn(z) || Math.hypot(x - source.getX(), z - source.getZ()) > reach) continue;
                for (Node stand : terrain.floors(x, z, lower, upper)) {
                    if (!terrain.canFinish(stand) || !terrain.clearBodyAt(stand.centerX(), stand.y(), stand.centerZ())) continue;
                    if (hit(result, source, new Vec3(stand.centerX(), stand.y() + eyeHeight, stand.centerZ())) != null) return true;
                }
            }
        }
        return false;
    }

    BlockHitResult hit(Item result, BlockPos source, Vec3 eye) {
        Fluid fluid = content(result);
        if (fluid == null || !available(source, fluid)) return null;
        AABB bounds = level.getFluidState(source).getAABB(level, source);
        if (bounds == null || bounds.getYsize() <= 0) return null;
        Vec3 center = bounds.getCenter();
        // Aim near the surface first so the ray can clear the rim of a pool.
        Vec3 upper = new Vec3(center.x, bounds.maxY - Math.min(.05, bounds.getYsize() / 4), center.z);
        BlockHitResult hit = trace(source, eye, upper);
        return hit == null ? trace(source, eye, center) : hit;
    }

    boolean aimed(Item result, BlockPos source) {
        Fluid fluid = content(result);
        if (fluid == null || !available(source, fluid)) return false;
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1).scale(player.blockInteractionRange()));
        return exactHit(source, eye, end) != null;
    }

    private BlockHitResult trace(BlockPos source, Vec3 eye, Vec3 point) {
        return eye.distanceTo(point) < player.blockInteractionRange() - REACH_MARGIN ? exactHit(source, eye, point) : null;
    }

    private BlockHitResult exactHit(BlockPos source, Vec3 eye, Vec3 end) {
        BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(source) ? hit : null;
    }

    private boolean available(BlockPos source, Fluid fluid) {
        if (!WorldJob.loaded(level, source) || !level.getWorldBorder().isWithinBounds(source) || !level.mayInteract(player, source)) return false;
        BlockState state = level.getBlockState(source);
        return isSource(state, fluid) && state.getCollisionShape(level, source).isEmpty();
    }

    private boolean exposed(BlockPos source) {
        for (Direction side : Direction.values()) {
            BlockPos adjacent = source.relative(side);
            if (!WorldJob.loaded(level, adjacent)) continue;
            BlockState neighbor = level.getBlockState(adjacent);
            if (!neighbor.getFluidState().isSource() && !neighbor.isCollisionShapeFullBlock(level, adjacent)) return true;
        }
        return false;
    }

    private static boolean isSource(BlockState state, Fluid fluid) {
        return state.getFluidState().isSourceOfType(fluid) && state.getBlock() instanceof BucketPickup
            && !(state.getBlock() instanceof LiquidBlockContainer) && !state.hasBlockEntity();
    }

    private static Fluid content(Item result) {
        if (!(result instanceof BucketItem bucket)) return null;
        Fluid fluid = bucket.getContent();
        return !fluid.defaultFluidState().isEmpty() && fluid.getBucket() == result ? fluid : null;
    }
}
