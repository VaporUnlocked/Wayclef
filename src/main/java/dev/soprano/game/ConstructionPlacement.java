package dev.soprano.game;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.function.Function;

/** Finds a real vanilla interaction whose predicted block state fits the blueprint. */
final class ConstructionPlacement {
    record Supply(InteractionHand hand, int slot, ItemStack stack, BlockItem item) { }

    record Aim(BlockHitResult hit, float yaw, float pitch) {
        static Aim toward(BlockHitResult hit, Vec3 eye) {
            Vec3 difference = hit.getLocation().subtract(eye);
            float yaw = (float) (Math.toDegrees(Math.atan2(difference.z, difference.x)) - 90);
            float pitch = (float) -Math.toDegrees(Math.atan2(difference.y, Math.hypot(difference.x, difference.z)));
            return new Aim(hit, yaw, pitch);
        }
    }

    private static final double[] FACE_OFFSETS = {.5, .23, .77};

    private final ClientLevel level;
    private final LocalPlayer player;

    ConstructionPlacement(ClientLevel level, LocalPlayer player) {
        this.level = level;
        this.player = player;
    }

    Aim find(BlockPos target, BlockState wanted, Supply supply, Vec3 eye, Function<BlockPos, BlockState> plan) {
        for (Direction face : Direction.values()) {
            BlockPos support = target.relative(face.getOpposite());
            Aim aim = aimOn(support, face, eye, candidate -> valid(target, wanted, supply, candidate, plan));
            if (aim != null) return aim;
        }
        if (canUpgradeSlab(level.getBlockState(target), wanted)) {
            Direction face = level.getBlockState(target).getValue(BlockStateProperties.SLAB_TYPE) == SlabType.BOTTOM
                ? Direction.UP : Direction.DOWN;
            return aimOn(target, face, eye, candidate -> valid(target, wanted, supply, candidate, plan));
        }
        return null;
    }

    boolean potentiallyInReach(BlockPos target, Vec3 eye) {
        for (Direction face : Direction.values()) {
            if (aimOn(target.relative(face.getOpposite()), face, eye, candidate -> true) != null) return true;
        }
        return breakHit(target, eye) != null;
    }

    Aim breakHit(BlockPos target, Vec3 eye) {
        for (Direction face : Direction.values()) {
            Aim aim = aimOn(target, face, eye, candidate -> true);
            if (aim != null) return aim;
        }
        return null;
    }

    private Aim aimOn(BlockPos support, Direction face, Vec3 eye, java.util.function.Predicate<Aim> accept) {
        if (!loaded(level, support) || !level.mayInteract(player, support)
            || eye.distanceToSqr(Vec3.atCenterOf(support)) > Math.pow(player.blockInteractionRange() + 1, 2)) return null;
        BlockState state = level.getBlockState(support);
        if (state.isAir()) return null;
        for (AABB shape : state.getShape(level, support, CollisionContext.of(player)).toAabbs()) {
            for (double u : FACE_OFFSETS) {
                for (double v : FACE_OFFSETS) {
                    Vec3 point = facePoint(shape.move(support), face, u, v);
                    Vec3 inside = point.relative(face.getOpposite(), .003);
                    if (eye.distanceToSqr(point) > Math.pow(player.blockInteractionRange(), 2)) continue;
                    BlockHitResult hit = level.clip(new ClipContext(eye, inside, ClipContext.Block.OUTLINE,
                        ClipContext.Fluid.NONE, player));
                    if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(support)
                        || hit.getDirection() != face || hit.isInside()) continue;
                    Aim candidate = Aim.toward(hit, eye);
                    if (accept.test(candidate)) return candidate;
                }
            }
        }
        return null;
    }

    private static Vec3 facePoint(AABB shape, Direction face, double u, double v) {
        return switch (face.getAxis()) {
            case X -> new Vec3(face == Direction.EAST ? shape.maxX : shape.minX,
                Mth.lerp(u, shape.minY, shape.maxY), Mth.lerp(v, shape.minZ, shape.maxZ));
            case Y -> new Vec3(Mth.lerp(u, shape.minX, shape.maxX),
                face == Direction.UP ? shape.maxY : shape.minY, Mth.lerp(v, shape.minZ, shape.maxZ));
            case Z -> new Vec3(Mth.lerp(u, shape.minX, shape.maxX), Mth.lerp(v, shape.minY, shape.maxY),
                face == Direction.SOUTH ? shape.maxZ : shape.minZ);
        };
    }

    private boolean valid(BlockPos target, BlockState wanted, Supply supply, Aim aim, Function<BlockPos, BlockState> plan) {
        float yaw = player.getYRot(), pitch = player.getXRot();
        try {
            player.setYRot(aim.yaw());
            player.setXRot(aim.pitch());
            return matchesInteraction(target, wanted, supply, aim.hit(), plan);
        } finally {
            player.setYRot(yaw);
            player.setXRot(pitch);
        }
    }

    boolean matchesInteraction(BlockPos target, BlockState wanted, Supply supply, BlockHitResult hit,
                               Function<BlockPos, BlockState> plan) {
        if (!loaded(level, target) || !level.mayInteract(player, target)) return false;
        BlockState current = level.getBlockState(target);
        if (!current.isAir() && !canUpgradeSlab(current, wanted)
            && !(current.is(Blocks.WATER) && wanted.hasProperty(BlockStateProperties.WATERLOGGED)
                && wanted.getValue(BlockStateProperties.WATERLOGGED))) return false;
        if (!hit.getBlockPos().equals(target) && interactive(level.getBlockState(hit.getBlockPos()))) return false;
        BlockPlaceContext context = supply.item().updatePlacementContext(
            new BlockPlaceContext(player, supply.hand(), supply.stack(), hit));
        if (context == null || !context.canPlace() || !target.equals(context.getClickedPos())) return false;
        BlockState predicted = supply.item().getBlock().getStateForPlacement(context);
        if (predicted == null) return false;
        var itemProperties = supply.stack().get(DataComponents.BLOCK_STATE);
        if (itemProperties != null) predicted = itemProperties.apply(predicted);
        return (matchesDuringConstruction(predicted, wanted) || isFirstSlab(predicted, wanted))
            && predicted.canSurvive(level, target) && level.isUnobstructed(predicted, target, CollisionContext.of(player))
            && companionMatches(target, predicted, plan);
    }

    private boolean companionMatches(BlockPos target, BlockState state, Function<BlockPos, BlockState> plan) {
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            return state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER
                && canPlaceCompanion(target.above(), state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), plan);
        }
        if (state.hasProperty(BlockStateProperties.BED_PART)) {
            return state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT
                && canPlaceCompanion(target.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING)),
                    state.setValue(BlockStateProperties.BED_PART, BedPart.HEAD), plan);
        }
        return true;
    }

    private boolean canPlaceCompanion(BlockPos position, BlockState state, Function<BlockPos, BlockState> plan) {
        return loaded(level, position) && level.mayInteract(player, position) && level.getBlockState(position).isAir()
            && level.isUnobstructed(state, position, CollisionContext.of(player)) && state == plan.apply(position);
    }

    static boolean canUpgradeSlab(BlockState current, BlockState wanted) {
        return isFirstSlab(current, wanted) && !current.getValue(BlockStateProperties.WATERLOGGED);
    }

    static boolean isFirstSlab(BlockState current, BlockState wanted) {
        return current.getBlock() == wanted.getBlock() && wanted.hasProperty(BlockStateProperties.SLAB_TYPE)
            && wanted.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE
            && current.getValue(BlockStateProperties.SLAB_TYPE) != SlabType.DOUBLE
            && !wanted.getValue(BlockStateProperties.WATERLOGGED);
    }

    static boolean matchesDuringConstruction(BlockState current, BlockState wanted) {
        if (current == wanted) return true;
        if (current.getBlock() != wanted.getBlock()) return false;
        if (current.hasProperty(BlockStateProperties.STAIRS_SHAPE))
            return current.setValue(BlockStateProperties.STAIRS_SHAPE, wanted.getValue(BlockStateProperties.STAIRS_SHAPE)) == wanted;
        if (!(current.getBlock() instanceof FenceBlock || current.getBlock() instanceof IronBarsBlock
            || current.getBlock() instanceof WallBlock)) return false;
        for (Property<?> property : current.getProperties()) {
            if (switch (property.getName()) {
                case "north", "south", "east", "west", "up" -> true;
                default -> false;
            }) current = copyProperty(current, wanted, property);
        }
        return current == wanted;
    }

    private static <T extends Comparable<T>> BlockState copyProperty(BlockState state, BlockState from, Property<T> property) {
        return state.setValue(property, from.getValue(property));
    }

    static boolean loaded(ClientLevel level, BlockPos position) {
        return !level.isOutsideBuildHeight(position) && level.hasChunkAt(position)
            && level.getWorldBorder().isWithinBounds(position);
    }

    private static boolean interactive(BlockState support) {
        var block = support.getBlock();
        return support.hasBlockEntity() || block instanceof DoorBlock || block instanceof TrapDoorBlock
            || block instanceof FenceGateBlock || block instanceof ButtonBlock || block instanceof LeverBlock
            || block instanceof RepeaterBlock || block instanceof ComparatorBlock;
    }
}
