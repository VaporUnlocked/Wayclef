package dev.soprano.build;

import dev.soprano.inventory.InventoryJobs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;

/** Reads a selected container from its normal server-synchronized menu. */
public final class BlueprintStorage {
    private static final int TIMEOUT_TICKS = 80;
    private static final List<Vec3> SAMPLES = List.of(Vec3.ZERO, new Vec3(-.35, 0, 0), new Vec3(.35, 0, 0),
        new Vec3(0, -.35, 0), new Vec3(0, .35, 0), new Vec3(0, 0, -.35), new Vec3(0, 0, .35));

    private record Snapshot(BlockPos position, Map<String, Integer> counts) { }

    private static final class Request {
        private final LocalPlayer player;
        private final BlockPos position;
        private final BlockState block;
        private int ticks;

        private Request(LocalPlayer player, BlockPos position, BlockState block) {
            this.player = player;
            this.position = position;
            this.block = block;
        }
    }

    private static ClientLevel scope;
    private static Request pending;
    private static Snapshot snapshot;
    private static String status = "";

    private BlueprintStorage() { }

    public static Map<String, Integer> counts(ClientLevel level) {
        return level == scope && snapshot != null ? snapshot.counts() : Map.of();
    }

    public static String status(ClientLevel level) { return level == scope ? status : ""; }

    public static String request(Minecraft minecraft, BuildPlan.Cell selection) {
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        syncScope(level);
        if (level == null || player == null || minecraft.gameMode == null) throw new IllegalStateException("Join a world first.");
        if (pending != null) throw new IllegalStateException("Already reading a storage container.");
        if (player.containerMenu != player.inventoryMenu || player.isUsingItem()) {
            throw new IllegalStateException("Close the current container or finish using the held item first.");
        }
        BlockPos position = new BlockPos(selection.x(), selection.y(), selection.z());
        if (!level.hasChunk(position.getX() >> 4, position.getZ() >> 4)) {
            throw new IllegalArgumentException("The selected storage is outside loaded terrain.");
        }
        BlockState block = level.getBlockState(position);
        if (!InventoryJobs.storageBlock(block)) throw new IllegalArgumentException("Pick a supported storage container.");
        if (visibleHit(level, player, position) == null) {
            throw new IllegalArgumentException("Move within reach of the selected container and clear the line of sight.");
        }
        pending = new Request(player, position, block);
        status = "Reading storage at " + coordinates(position) + ".";
        minecraft.gui.setScreen(null);
        return status;
    }

    public static void tick(Minecraft minecraft) {
        syncScope(minecraft.level);
        Request request = pending;
        if (request == null) return;
        LocalPlayer player = minecraft.player;
        if (player != request.player || minecraft.gameMode == null) {
            fail(player, "Storage read cancelled after the player changed.");
            return;
        }
        int age = ++request.ticks;
        if (age <= 2) {
            if (minecraft.gui.screen() != null || player.containerMenu != player.inventoryMenu
                || player.isShiftKeyDown() || player.isUsingItem()) {
                fail(player, "Storage read interrupted by another interaction.");
                return;
            }
            BlockHitResult hit = visibleHit(scope, player, request.position);
            if (hit == null || scope.getBlockState(request.position) != request.block) {
                fail(player, "The selected container moved, changed, or went out of reach.");
                return;
            }
            if (age == 1) {
                player.lookAt(EntityAnchorArgument.Anchor.EYES, hit.getLocation());
                return;
            }
            HitResult aimed = player.pick(player.blockInteractionRange(), 1, false);
            if (!(aimed instanceof BlockHitResult target) || target.getType() != HitResult.Type.BLOCK
                || !target.getBlockPos().equals(request.position)) {
                fail(player, "The selected container went out of sight.");
                return;
            }
            minecraft.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, target);
            return;
        }

        var menu = player.containerMenu;
        if (menu != player.inventoryMenu && menu.getStateId() != 0) {
            if (InventoryJobs.storageSlots(menu, player).isEmpty()) {
                fail(player, "The opened menu exposes no supported storage slots.");
                return;
            }
            snapshot = new Snapshot(request.position, InventoryJobs.storageCounts(menu, player));
            finish(player, "Storage snapshot at " + coordinates(request.position) + ".");
        } else if (age >= TIMEOUT_TICKS) {
            fail(player, "The server did not synchronize a supported storage menu.");
        }
    }

    private static void syncScope(ClientLevel level) {
        if (scope == level) return;
        scope = level;
        pending = null;
        snapshot = null;
        status = "";
    }

    private static void fail(LocalPlayer player, String reason) {
        if (snapshot != null) reason += " Using the snapshot at " + coordinates(snapshot.position()) + ".";
        finish(player, reason);
    }

    private static void finish(LocalPlayer player, String message) {
        pending = null;
        status = message;
        if (player != null) player.sendOverlayMessage(Component.literal(message));
    }

    private static BlockHitResult visibleHit(ClientLevel level, LocalPlayer player, BlockPos position) {
        if (!level.hasChunk(position.getX() >> 4, position.getZ() >> 4)) return null;
        Vec3 eye = player.getEyePosition();
        double reach = player.blockInteractionRange();
        Vec3 center = Vec3.atCenterOf(position);
        for (Vec3 offset : SAMPLES) {
            BlockHitResult hit = level.clip(new ClipContext(eye, center.add(offset), ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(position)
                && eye.distanceToSqr(hit.getLocation()) <= reach * reach) return hit;
        }
        return null;
    }

    private static String coordinates(BlockPos position) {
        return position.getX() + ", " + position.getY() + ", " + position.getZ();
    }
}
