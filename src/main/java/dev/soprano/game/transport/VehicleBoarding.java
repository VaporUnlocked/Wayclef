package dev.soprano.game.transport;

import dev.soprano.game.MinecraftTerrain;
import dev.soprano.game.Navigation;
import dev.soprano.path.Goal;
import dev.soprano.path.Node;
import dev.soprano.path.RouteExclusions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.camel.Camel;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Starts a vanilla boarding interaction from a verified itinerary staging point. */
public final class VehicleBoarding {
    public record Approach(Entity vehicle, TransportMode mode, Goal footing) { }

    private static final int MAX_VEHICLES = 6;
    private static final int MAX_FOOTINGS = 3;
    private static final int MAX_SEARCH_RANGE = 32;

    private VehicleBoarding() { }

    /** Finds dry staging positions for stopped boats and equipped mounts in the loaded world. */
    public static List<Approach> approaches(ClientLevel level, LocalPlayer player, TransportMode requested,
                                            Navigation.Options options) {
        int searchRange = Math.min(MAX_SEARCH_RANGE, options.search().horizontalRange());
        var candidates = level.getEntities(player, player.getBoundingBox().inflate(searchRange, 12, searchRange),
            entity -> permitted(requested, mode(entity)) && suitable(level, player, entity));
        var terrain = new MinecraftTerrain(level, player, options.avoidMobs());
        List<Approach> result = new ArrayList<>();
        terrain.beginSearchSlice();
        try {
            for (Entity vehicle : candidates.stream().sorted(Comparator.comparingDouble(player::distanceToSqr))
                .limit(MAX_VEHICLES).toList()) {
                result.addAll(footings(level, player, vehicle, terrain));
            }
        } finally {
            terrain.endSearchSlice();
        }
        return List.copyOf(result);
    }

    private static List<Approach> footings(ClientLevel level, LocalPlayer player, Entity vehicle, MinecraftTerrain terrain) {
        BlockPos at = vehicle.blockPosition();
        TransportMode mode = mode(vehicle);
        Node current = terrain.startAtPlayer();
        if (current != null && aim(level, player, vehicle, player.getEyePosition(), 0) != null) {
            return List.of(new Approach(vehicle, mode, new Goal(current.x(), current.y16(), current.z(), 0)));
        }
        int radius = (int) Math.clamp(Math.ceil(player.entityInteractionRange()), 1, 6);
        int low = Math.max(Node.MIN_Y16, (at.getY() - 3) * 16);
        int high = Math.min(Node.MAX_Y16, (at.getY() + 3) * 16);
        double eyeHeight = player.getEyeHeight(Pose.STANDING);
        var stance = player.getDimensions(Pose.STANDING);
        List<Node> nodes = new ArrayList<>();
        for (int x = at.getX() - radius; x <= at.getX() + radius; x++) {
            for (int z = at.getZ() - radius; z <= at.getZ() + radius; z++) {
                if (!Node.validColumn(x) || !Node.validColumn(z)) continue;
                for (Node node : terrain.floors(x, z, low, high)) {
                    Vec3 feet = new Vec3(node.centerX(), node.y(), node.centerZ());
                    if (!terrain.canFinish(node) || stance.makeBoundingBox(feet).intersects(vehicle.getBoundingBox())
                        || aim(level, player, vehicle, feet.add(0, eyeHeight, 0), .20) == null) continue;
                    nodes.add(node);
                }
            }
        }
        return nodes.stream().sorted(Comparator.comparingDouble(node ->
                player.position().distanceToSqr(node.centerX(), node.y(), node.centerZ())))
            .limit(MAX_FOOTINGS).map(node -> new Approach(vehicle, mode, new Goal(node.x(), node.y16(), node.z(), 0))).toList();
    }

    public static boolean tryBoard(Minecraft minecraft, Approach approach, boolean avoidMobs) {
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null || minecraft.gameMode == null || minecraft.gui.screen() != null
            || !player.isAlive() || !player.onGround() || player.isPassenger() || player.isInLiquid()
            || player.isShiftKeyDown() || player.isUsingItem() || player.isSpectator()
            || new MinecraftTerrain(level, player, avoidMobs).startAtPlayer() == null) return false;
        Entity target = approach.vehicle();
        if (!suitable(level, player, target) || !player.isWithinEntityInteractionRange(target, 0)) return false;
        EntityHitResult aim = aim(level, player, target, player.getEyePosition(), 0);
        if (aim == null) return false;
        InteractionHand hand = emptyHand(player);
        if (hand == null && approach.mode() == TransportMode.BOAT) hand = InteractionHand.MAIN_HAND;
        if (hand != null) return minecraft.gameMode.interact(player, target, aim, hand) instanceof InteractionResult.Success;
        Inventory inventory = player.getInventory();
        int original = inventory.getSelectedSlot();
        for (int slot = 0; Inventory.isHotbarSlot(slot); slot++) {
            if (!inventory.getItem(slot).isEmpty()) continue;
            inventory.setSelectedSlot(slot);
            try {
                return minecraft.gameMode.interact(player, target, aim, InteractionHand.MAIN_HAND) instanceof InteractionResult.Success;
            } finally {
                inventory.setSelectedSlot(original);
            }
        }
        return false;
    }

    private static InteractionHand emptyHand(LocalPlayer player) {
        if (player.getMainHandItem().isEmpty()) return InteractionHand.MAIN_HAND;
        if (player.getOffhandItem().isEmpty()) return InteractionHand.OFF_HAND;
        return null;
    }

    private static TransportMode mode(Entity vehicle) {
        if (vehicle instanceof AbstractBoat) return TransportMode.BOAT;
        if (vehicle instanceof AbstractHorse) return TransportMode.MOUNT;
        return null;
    }

    private static boolean permitted(TransportMode requested, TransportMode actual) {
        return actual != null && (requested == TransportMode.AUTO || requested == actual);
    }

    private static boolean suitable(ClientLevel level, LocalPlayer player, Entity vehicle) {
        if (mode(vehicle) == null || !vehicle.isAlive() || vehicle.isRemoved() || !vehicle.getPassengers().isEmpty()
            || vehicle.getDeltaMovement().horizontalDistance() > .085 || vehicle.level() != level
            || !level.getChunkSource().hasChunk(vehicle.blockPosition().getX() >> 4, vehicle.blockPosition().getZ() >> 4)) return false;
        var area = vehicle.getBoundingBox();
        var exclusions = Navigation.activeExclusions(false);
        if (exclusions.blockingAt(new RouteExclusions.Point(vehicle.getX(), area.minY, vehicle.getZ()),
            new RouteExclusions.Footprint(Math.max(area.maxX - area.minX, area.maxZ - area.minZ),
                area.maxY - area.minY + player.getBbHeight())).isPresent()) return false;

        if (vehicle instanceof AbstractHorse horse) {
            return horse.onGround() && !horse.isInLiquid() && !horse.isBaby() && horse.isTamed()
                && !horse.getItemBySlot(EquipmentSlot.SADDLE).isEmpty()
                && (!(horse instanceof Camel camel) || !camel.isCamelSitting() && !camel.isInPoseTransition());
        }
        BlockPos water = vehicle.blockPosition();
        if (vehicle.onGround() && level.getBlockState(water.below()).is(BlockTags.ICE)) return true;
        if (!level.getFluidState(water).is(FluidTags.WATER)) water = water.below();
        var fluid = level.getFluidState(water);
        return fluid.is(FluidTags.WATER) && fluid.isSource() && !level.getBlockState(water).is(Blocks.BUBBLE_COLUMN)
            && !level.getFluidState(water.above()).is(FluidTags.WATER);
    }

    private static EntityHitResult aim(ClientLevel level, LocalPlayer player, Entity vehicle, Vec3 eye, double margin) {
        double reach = player.entityInteractionRange() - margin;
        if (reach <= 0) return null;
        var box = vehicle.getBoundingBox().deflate(.025);
        Vec3 closest = new Vec3(Math.clamp(eye.x, box.minX, box.maxX), Math.clamp(eye.y, box.minY, box.maxY),
            Math.clamp(eye.z, box.minZ, box.maxZ));
        for (Vec3 aim : List.of(closest, box.getCenter())) {
            if (eye.distanceToSqr(aim) > reach * reach) continue;
            var obstruction = level.clip(new ClipContext(eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (obstruction.getType() == HitResult.Type.MISS) return new EntityHitResult(vehicle, aim);
        }
        return null;
    }
}
