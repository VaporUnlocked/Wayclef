package dev.soprano.task;

import dev.soprano.game.MinecraftTerrain;
import dev.soprano.game.ToolDurability;
import dev.soprano.inventory.InventoryJobs;
import dev.soprano.path.Node;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Chooses a dry stance and a cast that can reach a loaded fishing spot. */
final class GatherFishing {
    record Spot(BlockPos water, Node stand, String key) { }
    record Aim(Vec3 look) { }

    private static final int WATER_RADIUS = 24;
    private static final int VERTICAL_RADIUS = 12;
    private static final int MAX_WATER_CHECKS = 96;
    private static final int MAX_CAST_DISTANCE = 11;
    private static final int MAX_STANDS_PER_WATER = 16;
    private static final int FLIGHT_STEPS = 64;
    // FishingHook's launch offset, mean speed, gravity, and drag.
    private static final double LAUNCH_OFFSET = .3;
    private static final double CAST_SPEED = 1.1;
    private static final double GRAVITY = .03;
    private static final double DRAG = .92;

    private final ClientLevel level;
    private final LocalPlayer player;
    private final Map<Item, Spot> known = new HashMap<>();
    private BlockPos lastSearch;
    private long lastWaterScan;
    private List<BlockPos> water = List.of();

    GatherFishing(ClientLevel level, LocalPlayer player) {
        this.level = level;
        this.player = player;
    }

    Spot find(Item item, Predicate<String> excluded) {
        if (player.fishing != null) return null;
        ItemStack rod = availableRod(player);
        if (rod.isEmpty()) rod = Items.FISHING_ROD.getDefaultInstance();
        if (!GatherSources.canFish(item, rod, null, null)) return null;
        Spot previous = known.get(item);
        if (previous != null && !excluded.test(previous.key()) && eligibleWater(item, rod, previous.water())) return previous;
        var origin = player.blockPosition();
        if (lastSearch == null || origin.distManhattan(lastSearch) >= 4 || level.getGameTime() - lastWaterScan > 100) {
            water = loadedWater(origin);
            lastSearch = origin.immutable();
            lastWaterScan = level.getGameTime();
        }
        var terrain = new MinecraftTerrain(level, player);
        terrain.beginSearchSlice();
        try {
            Node current = terrain.startAtPlayer();
            if (current == null) return null;
            var candidates = new ArrayList<BlockPos>();
            boolean canCastFromHere = terrain.canFinish(current);
            for (BlockPos at : water) {
                if (candidates.size() == MAX_WATER_CHECKS) break;
                String key = key(item, at);
                if (excluded.test(key) || !eligibleWater(item, rod, at)) continue;
                if (canCastFromHere && aim(at, player.getEyePosition()) != null) {
                    return remember(item, new Spot(at, current, key));
                }
                candidates.add(at);
            }
            for (BlockPos at : candidates) {
                Node stand = shoreline(at, terrain);
                if (stand != null) return remember(item, new Spot(at, stand, key(item, at)));
            }
        } finally {
            terrain.endSearchSlice();
        }
        return null;
    }

    private Spot remember(Item item, Spot spot) {
        known.put(item, spot);
        return spot;
    }

    private static String key(Item item, BlockPos at) {
        return "fish:" + InventoryJobs.itemId(item) + ":" + at.asLong();
    }

    private List<BlockPos> loadedWater(BlockPos center) {
        var found = new ArrayList<BlockPos>();
        var area = WorldJob.nearbyArea(center, WATER_RADIUS, level.getMinY(), level.getMaxY());
        int minY = Math.max(area.minY(), center.getY() - VERTICAL_RADIUS);
        int maxY = Math.min(area.maxY(), center.getY() + VERTICAL_RADIUS);
        var cursor = new BlockPos.MutableBlockPos();
        for (int x = area.minX(); x <= area.maxX(); x++) {
            for (int z = area.minZ(); z <= area.maxZ(); z++) {
                if (!level.getChunkSource().hasChunk(x >> 4, z >> 4)) continue;
                for (int y = maxY; y >= minY; y--) {
                    cursor.set(x, y, z);
                    if (surface(cursor)) found.add(cursor.immutable());
                }
            }
        }
        var body = player.position();
        found.sort(Comparator.comparingDouble(at -> at.distToCenterSqr(body)));
        return List.copyOf(found);
    }

    private Node shoreline(BlockPos at, MinecraftTerrain terrain) {
        double eyeHeight = player.getEyeHeight(Pose.STANDING);
        var body = player.position();
        Comparator<Node> nearest = Comparator.comparingDouble(node -> body.distanceToSqr(node.centerX(), node.y(), node.centerZ()));
        var options = new ArrayList<Node>();
        for (int x = at.getX() - MAX_CAST_DISTANCE; x <= at.getX() + MAX_CAST_DISTANCE; x++) {
            if (!Node.validColumn(x)) continue;
            for (int z = at.getZ() - MAX_CAST_DISTANCE; z <= at.getZ() + MAX_CAST_DISTANCE; z++) {
                if (!Node.validColumn(z) || !level.getChunkSource().hasChunk(x >> 4, z >> 4)
                    || Math.hypot(x - at.getX(), z - at.getZ()) > MAX_CAST_DISTANCE) continue;
                for (Node stand : terrain.floors(x, z, (at.getY() - 1) * 16, (at.getY() + 4) * 16)) {
                    if (terrain.canFinish(stand) && terrain.clearBodyAt(stand.centerX(), stand.y(), stand.centerZ())) options.add(stand);
                }
            }
        }
        options.sort(nearest);
        for (int i = 0; i < Math.min(options.size(), MAX_STANDS_PER_WATER); i++) {
            Node stand = options.get(i);
            if (aim(at, new Vec3(stand.centerX(), stand.y() + eyeHeight, stand.centerZ())) != null) return stand;
        }
        return null;
    }

    private boolean eligibleWater(Item item, ItemStack rod, BlockPos at) {
        if (!surface(at)) return false;
        var biome = level.getBiome(at);
        return GatherSources.canFish(item, rod, biome, false) || openWater(at) && GatherSources.canFish(item, rod, biome, true);
    }

    private boolean surface(BlockPos at) {
        return !level.isOutsideBuildHeight(at.above()) && level.getChunkSource().hasChunk(at.getX() >> 4, at.getZ() >> 4)
            && level.getWorldBorder().isWithinBounds(at)
            && sourceWater(at) && level.getBlockState(at.above()).isAir();
    }

    private boolean sourceWater(BlockPos at) {
        var state = level.getBlockState(at);
        var fluid = state.getFluidState();
        return fluid.is(FluidTags.WATER) && fluid.isSource() && state.getCollisionShape(level, at).isEmpty();
    }

    private boolean openWater(BlockPos center) {
        for (var at : BlockPos.betweenClosed(center.offset(-2, -1, -2), center.offset(2, 2, 2))) {
            if (!level.getChunkSource().hasChunk(at.getX() >> 4, at.getZ() >> 4)) return false;
            if (at.getY() <= center.getY()) {
                if (!sourceWater(at)) return false;
            } else {
                var state = level.getBlockState(at);
                if (!state.isAir() && !state.is(Blocks.LILY_PAD)) return false;
            }
        }
        return true;
    }

    Aim aim(BlockPos at, Vec3 eye) {
        if (!surface(at)) return null;
        double dx = at.getX() + .5 - eye.x;
        double dz = at.getZ() + .5 - eye.z;
        double distance = Math.hypot(dx, dz);
        if (distance < 1 || distance > MAX_CAST_DISTANCE + .5) return null;
        double waterHeight = at.getY() + level.getFluidState(at).getHeight(level, at);
        double drop = eye.y - waterHeight;
        if (drop <= .05) return null;
        Vec3 horizontal = new Vec3(dx / distance, 0, dz / distance);
        // A bite pulls the bobber under the surface. Leave room for the hooked item to rise
        // before it reaches the bank, or the item can hit the wall below the player's feet.
        Vec3 catchStart = new Vec3(at.getX() + .5, waterHeight - .48, at.getZ() + .5);
        Vec3 catchRise = catchStart.subtract(horizontal.scale(Math.min(3.3, distance))).add(0, .83, 0);
        if (level.clip(new ClipContext(catchStart, catchRise, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
            .getType() != HitResult.Type.MISS) return null;
        double degrees = castPitch(distance, drop);
        if (!Double.isFinite(degrees)) return null;
        double pitch = Math.toRadians(degrees);
        Vec3 look = horizontal.scale(Math.cos(pitch)).add(0, -Math.sin(pitch), 0);
        Vec3 point = eye.add(horizontal.scale(LAUNCH_OFFSET));
        Vec3 velocity = look.scale(CAST_SPEED);
        for (int tick = 0; tick < FLIGHT_STEPS; tick++) {
            velocity = velocity.add(0, -GRAVITY, 0);
            Vec3 next = point.add(velocity);
            var hit = level.clip(new ClipContext(point, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.SOURCE_ONLY, player));
            if (hit.getType() == HitResult.Type.BLOCK) return hit.getBlockPos().equals(at) ? new Aim(look) : null;
            if (next.y < waterHeight - .05) return null;
            point = next;
            velocity = velocity.scale(DRAG);
        }
        return null;
    }

    static double castPitch(double distance, double drop) {
        double upper = 82;
        if (distance < castLength(upper, drop)) return Double.NaN;
        // Sweep upward to the first matching arc, then refine within the bracket.
        for (double lower = 78; lower >= -22; lower -= 4) {
            if (castLength(lower, drop) >= distance) {
                for (int iteration = 0; iteration < 12; iteration++) {
                    double pitch = (lower + upper) / 2;
                    if (castLength(pitch, drop) > distance) lower = pitch;
                    else upper = pitch;
                }
                return (lower + upper) / 2;
            }
            upper = lower;
        }
        return Double.NaN;
    }

    static double castLength(double pitch, double drop) {
        double radians = Math.toRadians(pitch);
        double horizontal = CAST_SPEED * Math.cos(radians);
        double vertical = -CAST_SPEED * Math.sin(radians);
        double distance = LAUNCH_OFFSET, height = drop;
        for (int tick = 0; tick < FLIGHT_STEPS; tick++) {
            vertical -= GRAVITY;
            if (height + vertical <= 0) return distance - horizontal * height / vertical;
            distance += horizontal;
            height += vertical;
            horizontal *= DRAG;
            vertical *= DRAG;
        }
        return 0;
    }

    static int rodSlot(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        int selected = inventory.getSelectedSlot();
        if (usableRod(inventory.getItem(selected))) return selected;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            if (usableRod(inventory.getItem(slot))) return slot;
        }
        return -1;
    }

    static ItemStack availableRod(LocalPlayer player) {
        if (usableRod(player.getMainHandItem())) return player.getMainHandItem();
        if (usableRod(player.getOffhandItem())) return player.getOffhandItem();
        int slot = rodSlot(player);
        return slot < 0 ? ItemStack.EMPTY : player.getInventory().getItem(slot);
    }

    static boolean usableRod(ItemStack stack) {
        return stack.is(Items.FISHING_ROD) && ToolDurability.pauseReason(stack, 3, ToolDurability.configuredReserve()) == null;
    }
}
