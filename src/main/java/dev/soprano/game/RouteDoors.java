package dev.soprano.game;

import dev.soprano.path.Step;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;

/** Uses regular block interactions for doors a walking step can pass through when opened. */
final class RouteDoors {
    private static final int OPEN_TIMEOUT = 20;
    private final LinkedHashMap<BlockPos, BlockState> opened = new LinkedHashMap<>();
    private BlockPos waiting;
    private BlockState opening;
    private int waitTicks;
    private String failure;

    String failure() { return failure; }

    boolean ready(MinecraftTerrain terrain, Step step, RoutePermissions permissions) {
        failure = null;
        if (!permissions.useDoors() || step == null) return true;
        Minecraft mc = Minecraft.getInstance();
        if (waiting != null) {
            BlockState state = mc.level.getBlockState(waiting);
            if (state == opening.setValue(BlockStateProperties.OPEN, true)
                || state.is(opening.getBlock()) && MinecraftTerrain.manualDoor(state) && state.getValue(BlockStateProperties.OPEN)) {
                if (permissions.closeDoors()) opened.put(waiting, state);
                waiting = null;
            } else if (++waitTicks >= OPEN_TIMEOUT || !state.is(opening.getBlock())) {
                failure = "Could not open the door, gate, or trapdoor at " + waiting.toShortString() + ".";
                waiting = null;
                return false;
            } else {
                return false;
            }
        }
        BlockPos door = terrain.closedDoorOnStep(step);
        if (door == null) {
            if (permissions.closeDoors()) closeBehind(mc, step);
            return true;
        }
        if (!terrain.canTraverse(step.from(), step.to(), step.move())) return true;
        var state = mc.level.getBlockState(door);
        var hit = doorHit(mc, door, state);
        boolean hatch = state.getBlock() instanceof TrapDoorBlock;
        boolean trapdoorUnderfoot = hatch && state.getCollisionShape(mc.level, door).toAabbs().stream()
            .anyMatch(box -> box.move(door).intersects(mc.player.getBoundingBox().expandTowards(0, -.06, 0)));
        boolean stable = mc.player.onGround() || hatch && mc.player.onClimbable();
        if (hit == null || !stable || trapdoorUnderfoot || !interact(mc, hit)) {
            failure = "The door, gate, or trapdoor at " + door.toShortString() + " cannot be opened from here.";
            return false;
        }
        waiting = door;
        opening = state;
        waitTicks = 0;
        return false;
    }

    void finish() { closeBehind(Minecraft.getInstance(), null); }

    void reset() {
        opened.clear();
        waiting = null;
        opening = null;
        waitTicks = 0;
        failure = null;
    }

    private void closeBehind(Minecraft mc, Step next) {
        if (mc.player == null || mc.level == null || mc.gameMode == null || !mc.player.onGround()) return;
        var iterator = opened.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            BlockPos pos = entry.getKey();
            BlockState state = mc.level.getBlockState(pos);
            if (state != entry.getValue() || !mc.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
                iterator.remove();
                continue;
            }
            AABB closingSpace = new AABB(pos).expandTowards(0, 1, 0).inflate(.08);
            if (closingSpace.intersects(mc.player.getBoundingBox()) || next != null
                && (withinColumn(next.from().x(), next.from().z(), pos) || withinColumn(next.to().x(), next.to().z(), pos))) continue;
            if (!mc.level.noEntityCollision(mc.player, closingSpace)) continue;
            BlockHitResult hit = doorHit(mc, pos, state);
            if (hit == null) continue;
            if (interact(mc, hit)) {
                iterator.remove();
                return;
            }
        }
    }

    private static boolean withinColumn(int x, int z, BlockPos pos) { return x == pos.getX() && z == pos.getZ(); }

    private static BlockHitResult doorHit(Minecraft mc, BlockPos pos, BlockState state) {
        if (mc.gameMode == null || !MinecraftTerrain.manualDoor(state)) return null;
        BlockHitResult hit = blockHit(mc, pos, state);
        if (hit != null || !(state.getBlock() instanceof DoorBlock)) return hit;
        BlockPos upper = pos.above();
        BlockState top = mc.level.getBlockState(upper);
        return top.is(state.getBlock()) ? blockHit(mc, upper, top) : null;
    }

    static BlockHitResult blockHit(Minecraft mc, BlockPos pos, BlockState state) {
        Vec3 eye = mc.player.getEyePosition();
        double reach = mc.player.blockInteractionRange();
        for (AABB box : state.getShape(mc.level, pos).toAabbs()) {
            Vec3 point = box.move(pos).getCenter();
            if (eye.distanceToSqr(point) > reach * reach) continue;
            BlockHitResult hit = mc.level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) return hit;
        }
        return null;
    }

    private static boolean interact(Minecraft mc, BlockHitResult hit) {
        if (!mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit).consumesAction()) return false;
        mc.player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        return true;
    }
}
