package dev.soprano.task;

import dev.soprano.inventory.InventoryJobs;
import dev.soprano.map.MapVolume;
import dev.soprano.path.Node;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

final class WorldJob {
    record Target(BlockPos position, BlockState expected, Item plant, Direction placementFace) {
        Target { position = position.immutable(); }
    }

    private final JobSpec spec;
    private final ClientLevel level;
    private final LocalPlayer player;
    private final MapVolume.Box area;
    private final ArrayDeque<Target> targets = new ArrayDeque<>();
    private final Set<Integer> ignoredDrops = new HashSet<>();
    private final int startingItems;
    private Node tunnelEnd;
    private int unknown, pickedUp;
    int processed;
    int skipped;
    int accessBreaks;
    String lastBlocked;

    WorldJob(JobSpec spec, ClientLevel level, LocalPlayer player) {
        this(spec, level, player, null);
    }

    WorldJob(JobSpec spec, ClientLevel level, LocalPlayer player, List<Target> prepared) {
        this.spec = spec;
        this.level = level;
        this.player = player;
        area = area(spec, level);
        startingItems = spec.kind() == JobSpec.Kind.COLLECT ? itemCount() : 0;
        if (prepared != null) {
            targets.addAll(prepared);
            return;
        }
        switch (spec.kind()) {
            case TUNNEL -> tunnel();
            case COLLECT -> {
                spec.ids().forEach(InventoryJobs::item);
            }
            case RETURN -> { }
            default -> surveyRegion();
        }
    }

    JobSpec.Kind kind() { return spec.kind(); }
    Node tunnelEnd() { return tunnelEnd; }
    int limit() { return spec.limit(); }
    Target take() { return targets.pollFirst(); }

    Target takeQuarryTarget(Target prepared) {
        Target first = targets.peekFirst();
        if (prepared != null && first != null && prepared.position().getY() == first.position().getY()
            && targets.remove(prepared)) return prepared;
        return take();
    }

    Target firstQuarryCandidate(int limit, Predicate<Target> reachable) {
        if (limit <= 0 || targets.isEmpty()) return null;
        int top = targets.getFirst().position().getY();
        for (Target candidate : targets) {
            if (limit-- == 0 || candidate.position().getY() != top) break;
            if (reachable.test(candidate)) return candidate;
        }
        return null;
    }

    Iterable<Target> plannedTargets() { return Collections.unmodifiableCollection(targets); }
    boolean collecting() { return spec.kind() == JobSpec.Kind.COLLECT; }
    boolean targetsAll() { return spec.targetsAll(); }
    boolean canExcavateAccess() { return spec.kind() == JobSpec.Kind.MINE && spec.nearbyRadius() > 0; }
    boolean collectAfterBreak() { return spec.kind() == JobSpec.Kind.MINE || spec.kind() == JobSpec.Kind.TREES || spec.kind() == JobSpec.Kind.CROPS; }
    boolean finished() { return spec.kind() != JobSpec.Kind.TUNNEL && processed >= limit(); }

    String result() {
        String unit = collecting() ? "matching items collected" : "blocks processed";
        String text = processed + " " + unit + ".";
        if (collectAfterBreak()) text += " " + pickedUp + " items gained during pickups.";
        if (accessBreaks != 0) text += " Cleared " + accessBreaks + " blocks for access.";
        if (skipped != 0) text += " " + skipped + " targets skipped.";
        if (unknown != 0) text += " " + unknown + " positions were outside loaded terrain.";
        if (lastBlocked != null) text += " " + lastBlocked;
        return text;
    }

    private int itemCount() {
        return spec.ids().stream().mapToInt(id -> InventoryJobs.count(player, id)).sum();
    }

    int collected() { return Math.max(0, itemCount() - startingItems); }

    void countPickups(Map<String, Integer> before) {
        for (var entry : InventoryJobs.playerCounts(player).entrySet()) {
            pickedUp += Math.max(0, entry.getValue() - before.getOrDefault(entry.getKey(), 0));
        }
    }

    ItemEntity nearestDrop(AABB area, Set<Integer> excluded) {
        int remaining = collecting() ? limit() - collected() : Integer.MAX_VALUE;
        return level.getEntitiesOfClass(ItemEntity.class, area, drop -> drop.isAlive() && !drop.getItem().isEmpty()
            && drop.getItem().getCount() <= remaining && !ignoredDrops.contains(drop.getId()) && !excluded.contains(drop.getId()) && (!collecting()
            || spec.ids().contains(InventoryJobs.itemId(drop.getItem())))).stream()
            .filter(drop -> !collecting() || area.contains(drop.getBlockX(), drop.getBlockY(), drop.getBlockZ()))
            .min(Comparator.comparingDouble(drop -> drop.distanceToSqr(player))).orElse(null);
    }

    void ignoreDrop(ItemEntity drop) { if (drop != null) ignoredDrops.add(drop.getId()); }

    AABB collectBox() {
        MapVolume.Box box = area;
        return new AABB(box.minX(), box.minY(), box.minZ(), box.maxX() + 1.0, box.maxY() + 1.0, box.maxZ() + 1.0);
    }

    private void surveyRegion() {
        Set<Block> blocks = new HashSet<>();
        for (String id : spec.ids()) {
            blocks.add(BuiltInRegistries.BLOCK.getOptional(Identifier.parse(id))
                .orElseThrow(() -> new IllegalArgumentException("Unknown block: " + id)));
        }
        List<Target> found = new ArrayList<>();
        Predicate<BlockState> matches = switch (spec.kind()) {
            case MINE, TREES -> state -> blocks.contains(state.getBlock());
            case QUARRY -> state -> !state.isAir() && !blocks.contains(state.getBlock());
            case CROPS -> WorldJob::cropType;
            default -> throw new IllegalStateException("This job has no block targets.");
        };
        scan(matches, found);
        Comparator<Target> nearest = Comparator.<Target>comparingDouble(t -> Vec3.atCenterOf(t.position()).distanceToSqr(player.position()))
            .thenComparing(t -> t.position());
        if (spec.kind() == JobSpec.Kind.QUARRY) {
            found.sort(Comparator.comparingInt((Target t) -> t.position().getY()).reversed().thenComparing(nearest));
        } else if (spec.kind() == JobSpec.Kind.TREES) {
            found.sort(Comparator.comparing((Target t) -> t.plant() != null).thenComparing(nearest));
        } else {
            found.sort(nearest);
        }
        targets.addAll(found);
    }

    private void scan(Predicate<BlockState> matches, List<Target> found) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int cx = area.minX() >> 4; cx <= area.maxX() >> 4; cx++) {
            int x1 = Math.max(area.minX(), cx << 4), x2 = Math.min(area.maxX(), (cx << 4) + 15);
            for (int cz = area.minZ() >> 4; cz <= area.maxZ() >> 4; cz++) {
                int z1 = Math.max(area.minZ(), cz << 4), z2 = Math.min(area.maxZ(), (cz << 4) + 15);
                var chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) {
                    unknown += (x2 - x1 + 1) * (z2 - z1 + 1) * (area.maxY() - area.minY() + 1);
                    continue;
                }
                for (int cy = area.minY() >> 4; cy <= area.maxY() >> 4; cy++) {
                    var section = chunk.getSections()[cy - (level.getMinY() >> 4)];
                    if (section.hasOnlyAir() || !section.maybeHas(matches)) continue;
                    int y1 = Math.max(area.minY(), cy << 4), y2 = Math.min(area.maxY(), (cy << 4) + 15);
                    for (int y = y2; y >= y1; y--) {
                        for (int x = x1; x <= x2; x++) {
                            for (int z = z1; z <= z2; z++) {
                                BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                                if (!matches.test(state)) continue;
                                cursor.set(x, y, z);
                                if (!breakable(level, cursor, state)) continue;
                                Target target = switch (spec.kind()) {
                                    case CROPS -> matureCrop(cursor, state);
                                    case TREES -> state.is(BlockTags.LOGS) ? tree(cursor, state) : null;
                                    default -> target(cursor, state);
                                };
                                if (target != null) found.add(target);
                            }
                        }
                    }
                }
            }
        }
    }

    static MapVolume.Box area(JobSpec spec, ClientLevel level) {
        if (spec.nearbyRadius() == 0) {
            if (spec.region() != null) checkBounds(level, spec.region());
            return spec.region();
        }
        BlockPos origin = spec.target();
        if (!Node.validColumn(origin.getX()) || !Node.validColumn(origin.getZ()) || level.isOutsideBuildHeight(origin)) {
            throw new IllegalArgumentException("The nearby job starts outside the world build limits.");
        }
        return nearbyArea(origin, spec.nearbyRadius(), level.getMinY(), level.getMaxY());
    }

    static MapVolume.Box nearbyArea(BlockPos origin, int radius, int minY, int maxY) {
        return new MapVolume.Box(Math.max(Node.MIN_COLUMN, origin.getX() - radius), Math.max(minY, origin.getY() - radius),
            Math.max(Node.MIN_COLUMN, origin.getZ() - radius), Math.min(Node.MAX_COLUMN, origin.getX() + radius),
            Math.min(maxY, origin.getY() + radius), Math.min(Node.MAX_COLUMN, origin.getZ() + radius));
    }

    static void checkBounds(ClientLevel level, MapVolume.Box box) {
        if (!Node.validColumn(box.minX()) || !Node.validColumn(box.maxX()) || !Node.validColumn(box.minZ())
            || !Node.validColumn(box.maxZ()) || level.isOutsideBuildHeight(box.minY()) || level.isOutsideBuildHeight(box.maxY())) {
            throw new IllegalArgumentException("The job selection is outside the world build limits.");
        }
    }

    private static boolean cropType(BlockState state) {
        return state.getBlock() instanceof CropBlock || state.is(Blocks.NETHER_WART) || state.is(Blocks.COCOA)
            || state.is(Blocks.SUGAR_CANE) || state.is(Blocks.CACTUS) || state.is(Blocks.BAMBOO);
    }

    private Target matureCrop(BlockPos at, BlockState state) {
        Block block = state.getBlock();
        if ((state.is(Blocks.SUGAR_CANE) || state.is(Blocks.CACTUS) || state.is(Blocks.BAMBOO))
            && level.getBlockState(at.below()).is(block) && !level.getBlockState(at.below(2)).is(block)) {
            return target(at, state);
        }
        if (block instanceof CropBlock crop && crop.isMaxAge(state)) {
            Item seed = state.is(Blocks.WHEAT) ? Items.WHEAT_SEEDS : state.is(Blocks.BEETROOTS) ? Items.BEETROOT_SEEDS
                : state.is(Blocks.CARROTS) ? Items.CARROT : state.is(Blocks.POTATOES) ? Items.POTATO : block.asItem();
            return seed == Items.AIR ? null : new Target(at, state, seed, Direction.UP);
        }
        if (block instanceof NetherWartBlock && state.getValue(NetherWartBlock.AGE) == NetherWartBlock.MAX_AGE) {
            return new Target(at, state, Items.NETHER_WART, Direction.UP);
        }
        if (block instanceof CocoaBlock && state.getValue(CocoaBlock.AGE) == CocoaBlock.MAX_AGE) {
            return new Target(at, state, Items.COCOA_BEANS, state.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite());
        }
        return null;
    }

    private Target tree(BlockPos at, BlockState state) {
        if (!spec.replant() || !level.getBlockState(at.below()).is(BlockTags.DIRT)) return target(at, state);
        Identifier log = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String path = log.getPath();
        if (!path.endsWith("_log")) return null;
        String replacement = path.equals("mangrove_log") ? "mangrove_propagule" : path.replaceFirst("_log$", "_sapling");
        Item sapling = BuiltInRegistries.ITEM.getOptional(Identifier.fromNamespaceAndPath(log.getNamespace(), replacement)).orElse(null);
        return sapling == null ? null : new Target(at, state, sapling, Direction.UP);
    }

    private void tunnel() {
        BlockPos start = player.blockPosition();
        BlockPos end = spec.target();
        long dx = (long) end.getX() - start.getX(), dz = (long) end.getZ() - start.getZ();
        if (dx == 0 && dz == 0 || Math.abs(dx) + Math.abs(dz) > 128) {
            throw new IllegalArgumentException("Select a tunnel target from 1 to 128 horizontal steps away.");
        }
        int steps = (int) (Math.abs(dx) + Math.abs(dz));
        int endFeetY = start.getY() + (int) Math.round(steps * spec.grade() / 100.0);
        if (end.getY() != endFeetY && end.getY() + 1 != endFeetY) {
            throw new IllegalArgumentException("This grade ends at feet Y " + endFeetY + ". Adjust the grade or select a block at that height.");
        }
        int x = start.getX(), z = start.getZ(), movedX = 0, movedZ = 0;
        int widthStart = -(spec.width() - 1) / 2;
        Set<BlockPos> seen = new LinkedHashSet<>();
        Set<BlockPos> floors = new HashSet<>();
        floors.add(start.below());
        int breaks = 0;
        int previousFeetY = start.getY();
        for (int step = 1; step <= steps; step++) {
            int oldX = x, oldZ = z;
            boolean alongX = movedZ == Math.abs(dz) || movedX < Math.abs(dx)
                && (2L * movedX + 1) * Math.abs(dz) <= (2L * movedZ + 1) * Math.abs(dx);
            if (alongX) { x += Long.signum(dx); movedX++; }
            else { z += Long.signum(dz); movedZ++; }
            int feetY = start.getY() + (int) Math.round(step * spec.grade() / 100.0);
            BlockPos floor = new BlockPos(x, feetY - 1, z);
            if (!loaded(level, floor) || !level.getBlockState(floor).isCollisionShapeFullBlock(level, floor)
                || !level.getBlockState(floor).getFluidState().isEmpty() || seen.contains(floor)) {
                throw new IllegalArgumentException("The tunnel grade has no confirmed floor at " + floor.toShortString() + ".");
            }
            floors.add(floor);
            List<Target> slice = new ArrayList<>();
            if (feetY > previousFeetY) addTunnelCell(new BlockPos(oldX, previousFeetY + spec.height(), oldZ), seen, floors, slice);
            for (int y = Math.max(feetY, previousFeetY) + spec.height() - 1; y >= feetY; y--) {
                for (int w = widthStart; w < widthStart + spec.width(); w++) {
                    BlockPos at = new BlockPos(x + (alongX ? 0 : w), y, z + (alongX ? w : 0));
                    addTunnelCell(at, seen, floors, slice);
                }
            }
            if (breaks + slice.size() > spec.limit()) {
                throw new IllegalArgumentException("The tunnel requires more than the approved " + spec.limit() + " breaks.");
            }
            breaks += slice.size();
            targets.addAll(slice);
            if (spec.torchSpacing() > 0 && step % spec.torchSpacing() == 0) {
                BlockPos torch = new BlockPos(x, feetY, z);
                targets.add(new Target(torch, null, Items.TORCH, Direction.UP));
            }
            previousFeetY = feetY;
        }
        tunnelEnd = new Node(end.getX(), endFeetY * 16, end.getZ());
    }

    private void addTunnelCell(BlockPos at, Set<BlockPos> seen, Set<BlockPos> floors, List<Target> slice) {
        if (floors.contains(at)) throw new IllegalArgumentException("The tunnel dimensions intersect its own floor at " + at.toShortString() + ".");
        if (!seen.add(at)) return;
        if (!loaded(level, at)) throw new IllegalArgumentException("The tunnel enters unloaded terrain at " + at.toShortString() + ".");
        BlockState state = level.getBlockState(at);
        if (state.isAir()) return;
        if (!breakable(level, at, state)) throw new IllegalArgumentException("The tunnel hits a protected or unsafe block at " + at.toShortString() + ".");
        slice.add(target(at, state));
    }

    private static Target target(BlockPos at, BlockState state) { return new Target(at, state, null, null); }

    static boolean loaded(ClientLevel level, BlockPos position) {
        return !level.isOutsideBuildHeight(position) && level.hasChunk(position.getX() >> 4, position.getZ() >> 4);
    }

    static boolean breakable(ClientLevel level, BlockPos position, BlockState state) {
        return !state.isAir() && !state.hasBlockEntity() && state.getFluidState().isEmpty()
            && state.getDestroySpeed(level, position) >= 0 && level.getWorldBorder().isWithinBounds(position);
    }
}
