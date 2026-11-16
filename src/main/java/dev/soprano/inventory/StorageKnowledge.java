package dev.soprano.inventory;

import dev.soprano.host.ClientEvents;
import dev.soprano.mixin.FurnaceBlockDataAccessor;
import dev.soprano.mixin.FurnaceDataAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Remembers loaded storage locations and the contents actually available to this game. */
public final class StorageKnowledge implements ClientEvents.Listener {
    public record Location(BlockPos first, BlockPos second, Block block) {
        public String key() {
            if (block instanceof EnderChestBlock) return "storage:ender";
            return "storage:" + first.asLong() + (second == null ? "" : ":" + second.asLong());
        }
    }

    /** Physical furnace slots, last observed cook progress, and estimated remaining lit time. */
    public record FurnaceStock(ItemStack input, ItemStack fuel, ItemStack output, int burnTicks, int cookTicks) {
        public FurnaceStock {
            input = input.copy();
            fuel = fuel.copy();
            output = output.copy();
            burnTicks = Math.max(0, burnTicks);
            cookTicks = Math.max(0, cookTicks);
        }
    }

    private record Snapshot(Location location, Map<String, Integer> counts, FurnaceStock furnace, int observedTick) { }
    private record Opening(Location location, int expires) { }
    private record MenuObservation(Location location, AbstractContainerMenu menu, boolean contentsReceived) { }
    private record IntegratedRead(int requestedTick, CompletableFuture<List<Snapshot>> result) { }

    private static final StorageKnowledge INSTANCE = new StorageKnowledge();
    private static final int CHUNKS_PER_TICK = 24;
    private static final int INTEGRATED_READS_PER_BATCH = 24;
    private static final int REFRESH_INTERVAL = 40;
    private static final int MAX_SNAPSHOTS = 16_384;
    private static final int OPEN_TIMEOUT = 60;

    private ClientLevel level;
    private int tick;
    private int nextScanTick;
    private ChunkScan scan;
    private final Set<Location> loaded = new HashSet<>();
    private final Set<Location> discovering = new HashSet<>();
    private List<Location> currentLocations = List.of();
    private boolean locationsChanged;
    private final LinkedHashMap<String, Snapshot> snapshots = new LinkedHashMap<>(128, .75f, true);
    private final Map<BlockPos, Location> observedLocations = new HashMap<>();
    private final Map<Location, Integer> nextIntegratedRead = new HashMap<>();
    private final Set<Location> pendingIntegrated = new HashSet<>();
    private final ArrayDeque<Location> integratedQueue = new ArrayDeque<>();
    private IntegratedRead integratedRead;
    private Opening opening;
    private MenuObservation menu;

    private StorageKnowledge() { }

    public static void initialize() { ClientEvents.subscribe(INSTANCE); }

    public static List<Location> locations(ClientLevel level) {
        if (INSTANCE.level != level) return List.of();
        if (INSTANCE.locationsChanged) INSTANCE.refreshLocations();
        return INSTANCE.currentLocations;
    }

    private void refreshLocations() {
        var locations = new HashSet<>(loaded);
        locations.addAll(discovering);
        currentLocations = locations.stream().filter(location -> InventoryJobs.storageBlock(location.block().defaultBlockState()))
            .toList();
        locationsChanged = false;
    }

    /** Null means the contents are unknown. An empty map means the container was observed empty. */
    public static Map<String, Integer> counts(ClientLevel level, Location location) {
        Snapshot snapshot = snapshot(level, location);
        return snapshot == null ? null : snapshot.counts();
    }

    public static FurnaceStock furnace(ClientLevel level, BlockPos at) {
        if (INSTANCE.level != level) return null;
        Location location = locationAt(level, at);
        if (location == null || !(location.block() instanceof AbstractFurnaceBlock)) return null;
        Snapshot snapshot = snapshot(level, location);
        if (snapshot == null || snapshot.furnace() == null) return null;
        FurnaceStock stock = snapshot.furnace();
        int elapsed = Math.max(0, INSTANCE.tick - snapshot.observedTick());
        int burn = level.getBlockState(at).getValueOrElse(BlockStateProperties.LIT, true) ? stock.burnTicks() - elapsed : 0;
        return new FurnaceStock(stock.input(), stock.fuel(), stock.output(), burn, stock.cookTicks());
    }

    private static Snapshot snapshot(ClientLevel level, Location location) {
        if (INSTANCE.level != level) return null;
        Snapshot snapshot = INSTANCE.snapshots.get(location.key());
        return snapshot != null && (snapshot.location().equals(location) || location.block() instanceof EnderChestBlock)
            ? snapshot : null;
    }

    public static Location locationAt(ClientLevel level, BlockPos at) {
        LevelChunk chunk = level.getChunkSource().getChunk(at.getX() >> 4, at.getZ() >> 4, ChunkStatus.FULL, false);
        if (chunk == null) return null;
        return locationAt(level, at, chunk.getBlockState(at), chunk.getBlockEntities().get(at));
    }

    private static Location locationAt(ClientLevel level, BlockPos at, BlockState state, BlockEntity entity) {
        if (entity == null || (!(entity instanceof Container) && !InventoryJobs.storageBlock(state))) return null;
        BlockPos partner = partner(at, state);
        if (partner != null) {
            LevelChunk otherChunk = level.getChunkSource().getChunk(partner.getX() >> 4, partner.getZ() >> 4, ChunkStatus.FULL, false);
            if (otherChunk == null || !otherChunk.getBlockEntities().containsKey(partner)
                || !isPair(at, state, partner, otherChunk.getBlockState(partner))) return null;
        }
        return ordered(at, partner, state.getBlock());
    }

    static BlockPos partner(BlockPos at, BlockState state) {
        return state.getBlock() instanceof ChestBlock && state.getValue(BlockStateProperties.CHEST_TYPE) != ChestType.SINGLE
            ? ChestBlock.getConnectedBlockPos(at, state) : null;
    }

    static boolean isPair(BlockPos at, BlockState state, BlockPos partner, BlockState other) {
        return state.getBlock() == other.getBlock()
            && other.getValue(BlockStateProperties.CHEST_TYPE) == state.getValue(BlockStateProperties.CHEST_TYPE).getOpposite()
            && other.getValue(BlockStateProperties.HORIZONTAL_FACING) == state.getValue(BlockStateProperties.HORIZONTAL_FACING)
            && partner.equals(partner(at, state)) && at.equals(partner(partner, other));
    }

    static Location ordered(BlockPos at, BlockPos partner, Block block) {
        if (partner != null && partner.compareTo(at) < 0) return new Location(partner.immutable(), at.immutable(), block);
        return new Location(at.immutable(), partner == null ? null : partner.immutable(), block);
    }

    @Override
    public void tickPost() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != level) bind(mc.level);
        if (level == null || mc.player == null) return;
        tick++;
        if (opening != null && tick > opening.expires()) opening = null;
        updateMenu(mc.player);
        scanLocations(mc);
        updateIntegrated(mc);
    }

    @Override
    public void disconnected() { bind(null); }

    private void bind(ClientLevel world) {
        level = world;
        tick = nextScanTick = 0;
        opening = null;
        menu = null;
        scan = null;
        loaded.clear();
        discovering.clear();
        currentLocations = List.of();
        locationsChanged = false;
        snapshots.clear();
        observedLocations.clear();
        nextIntegratedRead.clear();
        pendingIntegrated.clear();
        integratedQueue.clear();
        integratedRead = null;
    }

    @Override
    public void packetSending(Connection connection, Packet<?> packet) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || player.connection.getConnection() != connection) return;
        if (mc.level != level) bind(mc.level);
        if (packet instanceof ServerboundUseItemOnPacket use && player.containerMenu == player.inventoryMenu) {
            Location location = locationAt(level, use.hitResult().getBlockPos());
            opening = location == null ? null : new Opening(location, tick + OPEN_TIMEOUT);
        } else if (packet instanceof ServerboundContainerClosePacket close
            && menu != null && menu.menu().containerId == close.getContainerId()) {
            updateMenu(player);
            menu = null;
        }
    }

    public static void menuOpened(ClientPacketListener connection, int id) { INSTANCE.observeOpen(connection, id); }

    public static void menuContents(ClientPacketListener connection, int id) { INSTANCE.observeContents(connection, id); }

    public static void blockUpdated(ClientPacketListener connection, BlockPos at) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.connection != connection || mc.level == null || mc.level != INSTANCE.level) return;
        Location location = mc.level.getBlockState(at).hasBlockEntity() ? locationAt(mc.level, at) : null;
        Location observed = INSTANCE.observedLocations.get(at);
        if (observed != null && !observed.equals(location)) {
            INSTANCE.snapshots.remove(observed.key());
            INSTANCE.removeObservedLocation(observed);
        }
        if (location != null) {
            INSTANCE.locationsChanged |= INSTANCE.loaded.add(location);
            INSTANCE.enqueueIntegrated(mc, location);
        }
    }

    private void observeOpen(ClientPacketListener connection, int id) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.connection != connection || mc.level != level) return;
        Opening request = opening;
        opening = null;
        menu = null;
        if (request == null || tick > request.expires()) return;
        AbstractContainerMenu opened = player.containerMenu;
        if (opened.containerId != id || !matchingSlots(request.location(), opened, inventorySlots(request.location(), opened, player).size())) return;
        menu = new MenuObservation(request.location(), opened, false);
    }

    private void observeContents(ClientPacketListener connection, int id) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.connection != connection || mc.level != level
            || menu == null || menu.menu().containerId != id || menu.menu() != player.containerMenu) return;
        menu = new MenuObservation(menu.location(), menu.menu(), true);
        updateMenu(player);
    }

    private void updateMenu(LocalPlayer player) {
        if (menu == null) return;
        Location location = menu.location();
        if (menu.menu() != player.containerMenu || !location.equals(locationAt(level, location.first()))) {
            menu = null;
            return;
        }
        if (!menu.contentsReceived()) return;
        AbstractContainerMenu current = menu.menu();
        List<Slot> slots = inventorySlots(location, current, player);
        if (!matchingSlots(location, current, slots.size())) return;
        var counts = new HashMap<String, Integer>();
        for (Slot slot : slots) addCount(slot.getItem(), counts);
        FurnaceStock stock = current instanceof AbstractFurnaceMenu furnace ? readFurnace(furnace) : null;
        remember(new Snapshot(location, Map.copyOf(counts), stock, tick));
        locationsChanged |= loaded.add(location);
    }

    private boolean matchingSlots(Location location, AbstractContainerMenu menu, int slots) {
        Block block = location.block();
        if (block instanceof AbstractFurnaceBlock) return menu instanceof AbstractFurnaceMenu && slots == 3;
        if (block instanceof BrewingStandBlock) return menu instanceof BrewingStandMenu && slots == 5;
        if (block instanceof ChestBlock || block instanceof EnderChestBlock || block instanceof BarrelBlock) {
            return menu instanceof ChestMenu && slots == (location.second() == null ? 27 : 54);
        }
        if (block instanceof HopperBlock) return menu instanceof HopperMenu && slots == 5;
        if (block instanceof DispenserBlock) return menu instanceof DispenserMenu && slots == 9;
        if (block instanceof ShulkerBoxBlock) return menu instanceof ShulkerBoxMenu && slots == 27;
        if (InventoryJobs.storageBlock(block.defaultBlockState())) return slots > 0;
        return level.getBlockEntity(location.first()) instanceof Container inventory
            && slots > 0 && slots == inventory.getContainerSize();
    }

    private static List<Slot> inventorySlots(Location location, AbstractContainerMenu menu, LocalPlayer player) {
        if (InventoryJobs.storageBlock(location.block().defaultBlockState())) return InventoryJobs.storageSlots(menu, player);
        return menu.slots.stream().filter(slot -> slot.container != player.getInventory() && slot.isActive() && !slot.isFake()).toList();
    }

    private static FurnaceStock readFurnace(AbstractFurnaceMenu menu) {
        ContainerData data = ((FurnaceDataAccessor) menu).soprano$furnaceData();
        return furnaceStock(menu.getSlot(AbstractFurnaceMenu.INGREDIENT_SLOT).getItem(),
            menu.getSlot(AbstractFurnaceMenu.FUEL_SLOT).getItem(), menu.getResultSlot().getItem(), data);
    }

    private static FurnaceStock furnaceStock(ItemStack input, ItemStack fuel, ItemStack output, ContainerData data) {
        return new FurnaceStock(input, fuel, output, data.get(AbstractFurnaceBlockEntity.DATA_LIT_TIME),
            data.get(AbstractFurnaceBlockEntity.DATA_COOKING_PROGRESS));
    }

    private void remember(Snapshot snapshot) {
        Location location = snapshot.location();
        Snapshot previous = snapshots.put(location.key(), snapshot);
        if (previous != null && !previous.location().equals(location)) removeObservedLocation(previous.location());
        // Ender chest contents belong to the player and survive changes to any one chest.
        if (!(location.block() instanceof EnderChestBlock)) {
            observedLocations.put(location.first(), location);
            if (location.second() != null) observedLocations.put(location.second(), location);
        }
        if (snapshots.size() > MAX_SNAPSHOTS) removeObservedLocation(snapshots.pollFirstEntry().getValue().location());
    }

    private void removeObservedLocation(Location location) {
        observedLocations.remove(location.first(), location);
        if (location.second() != null) observedLocations.remove(location.second(), location);
    }

    private void scanLocations(Minecraft mc) {
        if (scan == null && tick < nextScanTick) return;
        ChunkPos center = mc.player.chunkPosition();
        int radius = mc.options.getEffectiveRenderDistance();
        if (scan == null || scan.radius != radius || Math.abs(center.x() - scan.x) > 2 || Math.abs(center.z() - scan.z) > 2) {
            scan = new ChunkScan(center, radius);
            discovering.clear();
            locationsChanged = true;
        }
        for (int index = 0; index < CHUNKS_PER_TICK && scan.hasNext(); index++) {
            ChunkPos position = scan.next();
            LevelChunk chunk = level.getChunkSource().getChunk(position.x(), position.z(), ChunkStatus.FULL, false);
            if (chunk == null) continue;
            for (var entry : chunk.getBlockEntities().entrySet()) {
                BlockPos at = entry.getKey();
                Location location = locationAt(level, at, chunk.getBlockState(at), entry.getValue());
                if (location == null || !discovering.add(location)) continue;
                locationsChanged = true;
                enqueueIntegrated(mc, location);
            }
        }
        if (!scan.hasNext()) {
            loaded.clear();
            loaded.addAll(discovering);
            discovering.clear();
            locationsChanged = true;
            nextIntegratedRead.keySet().retainAll(loaded);
            scan = null;
            nextScanTick = tick + REFRESH_INTERVAL;
        }
    }

    private void enqueueIntegrated(Minecraft mc, Location location) {
        if (mc.getSingleplayerServer() != null && tick >= nextIntegratedRead.getOrDefault(location, 0)
            && pendingIntegrated.size() < MAX_SNAPSHOTS && pendingIntegrated.add(location)) {
            integratedQueue.addLast(location);
        }
    }

    private void updateIntegrated(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) return;
        if (integratedRead != null) {
            if (!integratedRead.result().isDone()) return;
            try {
                for (Snapshot snapshot : integratedRead.result().getNow(List.of())) {
                    Location location = snapshot.location();
                    Snapshot existing = snapshots.get(location.key());
                    if ((existing == null || existing.observedTick() < integratedRead.requestedTick())
                        && location.equals(locationAt(level, location.first()))) {
                        remember(new Snapshot(location, snapshot.counts(), snapshot.furnace(), tick));
                    }
                }
            } catch (CompletionException ignored) {
                // A concurrent world exit or reload can interrupt a passive read.
            }
            integratedRead = null;
        }
        if (integratedQueue.isEmpty()) return;
        var batch = new ArrayList<Location>(INTEGRATED_READS_PER_BATCH);
        for (int index = 0; index < INTEGRATED_READS_PER_BATCH && !integratedQueue.isEmpty(); index++) {
            Location location = integratedQueue.removeFirst();
            pendingIntegrated.remove(location);
            if (!location.equals(locationAt(level, location.first()))) continue;
            nextIntegratedRead.put(location, tick + REFRESH_INTERVAL);
            batch.add(location);
        }
        if (batch.isEmpty()) return;
        ResourceKey<Level> dimension = level.dimension();
        UUID user = mc.player.getUUID();
        integratedRead = new IntegratedRead(tick, server.submit(() -> readIntegrated(server, dimension, user, batch)));
    }

    private static List<Snapshot> readIntegrated(IntegratedServer server, ResourceKey<Level> dimension, UUID user,
                                                 List<Location> batch) {
        ServerLevel level = server.getLevel(dimension);
        ServerPlayer player = server.getPlayerList().getPlayer(user);
        if (level == null || player == null || player.level() != level) return List.of();
        var reads = new ArrayList<Snapshot>();
        for (Location location : batch) {
            if (!location.block().builtInRegistryHolder().key().identifier().getNamespace().equals("minecraft")) continue;
            Snapshot inventory = integratedSnapshot(level, player, location);
            if (inventory != null) reads.add(inventory);
        }
        return reads;
    }

    private static Snapshot integratedSnapshot(ServerLevel level, ServerPlayer player, Location location) {
        BlockEntity first = loadedEntity(level, location.first());
        if (first == null || first.getBlockState().getBlock() != location.block() || !level.mayInteract(player, location.first())) return null;
        var counts = new HashMap<String, Integer>();
        if (location.block() instanceof EnderChestBlock) {
            addCounts(player.getEnderChestInventory(), counts);
            return new Snapshot(location, Map.copyOf(counts), null, -1);
        }
        if (!readable(first, player)) return null;
        if (location.second() != null) {
            BlockEntity second = loadedEntity(level, location.second());
            if (second == null || !isPair(location.first(), first.getBlockState(), location.second(), second.getBlockState())
                || !level.mayInteract(player, location.second()) || !readable(second, player)) return null;
            addCounts((Container) second, counts);
        } else if (partner(location.first(), first.getBlockState()) != null) return null;
        addCounts((Container) first, counts);
        FurnaceStock furnace = first instanceof AbstractFurnaceBlockEntity oven ? readFurnace(oven) : null;
        return new Snapshot(location, Map.copyOf(counts), furnace, -1);
    }

    private static FurnaceStock readFurnace(AbstractFurnaceBlockEntity furnace) {
        ContainerData data = ((FurnaceBlockDataAccessor) furnace).soprano$furnaceData();
        return furnaceStock(furnace.getItem(AbstractFurnaceMenu.INGREDIENT_SLOT), furnace.getItem(AbstractFurnaceMenu.FUEL_SLOT),
            furnace.getItem(AbstractFurnaceMenu.RESULT_SLOT), data);
    }

    private static BlockEntity loadedEntity(ServerLevel level, BlockPos at) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4);
        return chunk == null ? null : chunk.getBlockEntities().get(at);
    }

    private static boolean readable(BlockEntity entity, ServerPlayer player) {
        return entity instanceof Container
            && (!(entity instanceof BaseContainerBlockEntity storage) || storage.canOpen(player))
            && (!(entity instanceof RandomizableContainer loot) || loot.getLootTable() == null);
    }

    private static void addCounts(Container inventory, Map<String, Integer> counts) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            addCount(inventory.getItem(slot), counts);
        }
    }

    private static void addCount(ItemStack stack, Map<String, Integer> counts) {
        if (!stack.isEmpty()) counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
    }

    /** Near-first traversal keeps local storage ready while the outer view is still being indexed. */
    private static final class ChunkScan {
        final int x, z, radius;
        private int ring, offset;

        ChunkScan(ChunkPos center, int radius) {
            x = center.x();
            z = center.z();
            this.radius = radius;
        }

        boolean hasNext() { return ring <= radius; }

        ChunkPos next() {
            if (ring == 0) {
                ring = 1;
                return new ChunkPos(x, z);
            }
            int length = 2 * ring;
            int side = offset / length;
            int step = offset % length;
            ChunkPos at = switch (side) {
                case 0 -> new ChunkPos(x - ring + step, z - ring);
                case 1 -> new ChunkPos(x + ring, z - ring + step);
                case 2 -> new ChunkPos(x + ring - step, z + ring);
                default -> new ChunkPos(x - ring, z + ring - step);
            };
            if (++offset == 4 * length) {
                offset = 0;
                ring++;
            }
            return at;
        }
    }
}
