package dev.soprano.inventory;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractChestBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Live sign discovery and standard slot transactions through the active server menu. */
public final class InventoryJobs {
    private static final int STORAGE_RADIUS = 32;
    private static final RegistryItems ITEM_NAMES = new RegistryItems();

    private InventoryJobs() { }

    public static List<SignStorage.LabeledContainer> labels(ClientLevel level, LocalPlayer player) {
        BlockPos origin = player.blockPosition();
        var containers = new ArrayList<SignStorage.Container>();
        var signs = new ArrayList<SignStorage.Sign>();
        int minX = (origin.getX() - STORAGE_RADIUS - 1) >> 4;
        int maxX = (origin.getX() + STORAGE_RADIUS + 1) >> 4;
        int minZ = (origin.getZ() - STORAGE_RADIUS - 1) >> 4;
        int maxZ = (origin.getZ() + STORAGE_RADIUS + 1) >> 4;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                var chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                for (var entry : chunk.getBlockEntities().entrySet()) {
                    BlockPos pos = entry.getKey();
                    int distance = origin.distManhattan(pos);
                    if (distance > STORAGE_RADIUS + 1) continue;
                    if (entry.getValue() instanceof SignBlockEntity sign) {
                        SignStorage.Sign label = sign(pos, sign);
                        if (label != null) signs.add(label);
                    } else {
                        BlockState state = level.getBlockState(pos);
                        if (storageBlock(state)) containers.add(new SignStorage.Container(position(pos), front(state), distance));
                    }
                }
            }
        }
        return SignStorage.label(containers, signs, ITEM_NAMES);
    }

    /** Rechecks a known container and neighboring sign claims without rescanning all nearby chunks. */
    public static SignStorage.LabeledContainer labelAt(ClientLevel level, BlockPos at) {
        if (!level.hasChunk(at.getX() >> 4, at.getZ() >> 4)) return null;
        BlockState state = level.getBlockState(at);
        if (!storageBlock(state)) return null;
        var signs = new ArrayList<SignStorage.Sign>();
        var positions = new HashSet<BlockPos>();
        positions.add(at);
        Direction facing = facing(state);
        List<BlockPos> signPositions = facing == null || facing == Direction.UP
            ? List.of(at.above()) : List.of(at.above(), at.relative(facing));
        for (BlockPos signPos : signPositions) {
            if (!level.hasChunk(signPos.getX() >> 4, signPos.getZ() >> 4)) continue;
            if (!(level.getBlockEntity(signPos) instanceof SignBlockEntity entity)) continue;
            SignStorage.Sign sign = sign(signPos, entity);
            if (sign == null) continue;
            signs.add(sign);
            for (Direction direction : Direction.values()) positions.add(signPos.relative(direction));
        }
        var containers = new ArrayList<SignStorage.Container>();
        for (BlockPos position : positions) {
            if (!level.hasChunk(position.getX() >> 4, position.getZ() >> 4)) continue;
            BlockState candidate = level.getBlockState(position);
            if (storageBlock(candidate) && level.getBlockEntity(position) != null) {
                containers.add(new SignStorage.Container(position(position), front(candidate), 0));
            }
        }
        return SignStorage.label(containers, signs, ITEM_NAMES).stream()
            .filter(label -> position(label.container()).equals(at)).findFirst().orElse(null);
    }

    private static SignStorage.Sign sign(BlockPos at, SignBlockEntity sign) {
        List<String> front = signLines(sign, SignTextSlot.FRONT);
        List<String> back = signLines(sign, SignTextSlot.BACK);
        if (front.stream().allMatch(String::isBlank)) return new SignStorage.Sign(position(at), back);
        if (back.stream().allMatch(String::isBlank) || front.equals(back)) return new SignStorage.Sign(position(at), front);
        return null;
    }

    private static List<String> signLines(SignBlockEntity sign, SignTextSlot slot) {
        return sign.getText(slot).getMessages(false).stream().map(message -> message.getString().strip()).toList();
    }

    public static boolean storageBlock(BlockState state) {
        var block = state.getBlock();
        if (block instanceof AbstractChestBlock<?> || block instanceof BarrelBlock || block instanceof ShulkerBoxBlock
            || block instanceof HopperBlock || block instanceof DispenserBlock) return true;
        String namespace = BuiltInRegistries.BLOCK.getKey(block).getNamespace();
        return namespace.equals("betterchests") || namespace.equals("better_chests");
    }

    private static SignStorage.Front front(BlockState state) {
        Direction facing = facing(state);
        return facing == null ? SignStorage.Front.UNKNOWN : SignStorage.Front.valueOf(facing.name());
    }

    private static Direction facing(BlockState state) {
        return state.getOptionalValue(BlockStateProperties.HORIZONTAL_FACING)
            .or(() -> state.getOptionalValue(BlockStateProperties.FACING)).orElse(null);
    }

    private static SignStorage.Position position(BlockPos pos) {
        return new SignStorage.Position(pos.getX(), pos.getY(), pos.getZ());
    }

    public static BlockPos position(SignStorage.Container container) {
        SignStorage.Position pos = container.position();
        return new BlockPos(pos.x(), pos.y(), pos.z());
    }

    public static String itemId(ItemStack stack) {
        return itemId(stack.getItem());
    }

    public static String itemId(Item item) { return BuiltInRegistries.ITEM.getKey(item).toString(); }

    public static Item item(String id) {
        return BuiltInRegistries.ITEM.getOptional(Identifier.parse(id))
            .orElseThrow(() -> new IllegalArgumentException("Unknown item: " + id));
    }

    public static Item gatherItem(String id) {
        Identifier key = Identifier.parse(id);
        var registered = BuiltInRegistries.ITEM.getOptional(key);
        if (registered.isPresent()) return registered.get();
        return BuiltInRegistries.FLUID.getOptional(key).map(fluid -> fluid.getBucket())
            .filter(item -> item != Items.AIR && item != Items.BUCKET)
            .orElseThrow(() -> new IllegalArgumentException("Unknown item or bucketable fluid: " + id));
    }

    private static final class RegistryItems implements SignStorage.ItemRegistry {
        private Map<String, List<String>> names;
        private Language language;

        @Override public boolean contains(String itemId) {
            return BuiltInRegistries.ITEM.containsKey(Identifier.parse(itemId));
        }

        @Override public List<String> findDisplayName(String normalizedName) {
            if (names == null || language != Language.getInstance()) {
                language = Language.getInstance();
                names = new HashMap<>();
                for (Item item : BuiltInRegistries.ITEM) {
                    String name = item.getDefaultInstance().getHoverName().getString().strip()
                        .replaceAll("(?U)\\s+", " ").toLowerCase(Locale.ROOT);
                    names.computeIfAbsent(name, key -> new ArrayList<>()).add(BuiltInRegistries.ITEM.getKey(item).toString());
                }
            }
            return names.getOrDefault(normalizedName, List.of());
        }
    }

    /** A vanilla menu or modded chest that actually exposes standard, physical slots. */
    public static List<Slot> storageSlots(AbstractContainerMenu menu, LocalPlayer player) {
        if (menu == player.inventoryMenu) return List.of();
        boolean vanilla = menu instanceof ChestMenu || menu instanceof ShulkerBoxMenu
            || menu instanceof HopperMenu || menu instanceof DispenserMenu;
        var type = BuiltInRegistries.MENU.getKey(menu.getType());
        boolean compatibleChest = type != null && (type.getNamespace().equals("betterchests")
            || type.getNamespace().equals("better_chests"));
        if (!vanilla && !compatibleChest) return List.of();
        List<Slot> slots = menu.slots.stream().filter(slot -> slot.container != player.getInventory())
            .filter(slot -> slot.isActive() && !slot.isFake()).toList();
        if (compatibleChest && slots.stream().anyMatch(slot -> slot.getClass() != Slot.class)) return List.of();
        return slots;
    }

    public static Map<String, Integer> storageCounts(AbstractContainerMenu menu, LocalPlayer player) {
        var counts = new LinkedHashMap<String, Integer>();
        for (Slot slot : storageSlots(menu, player)) {
            if (slot.hasItem()) counts.merge(itemId(slot.getItem()), slot.getItem().getCount(), Integer::sum);
        }
        return Map.copyOf(counts);
    }

    public static List<Slot> playerSlots(AbstractContainerMenu menu, LocalPlayer player) {
        return menu.slots.stream().filter(slot -> slot.container == player.getInventory() && slot.isActive()
            && !slot.isFake() && slot.getContainerSlot() >= 0 && slot.getContainerSlot() < Inventory.INVENTORY_SIZE).toList();
    }

    public static int count(LocalPlayer player, String itemId) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (!stack.isEmpty() && itemId(stack).equals(itemId)) total += stack.getCount();
        }
        return total;
    }

    public static Map<String, Integer> playerCounts(LocalPlayer player) {
        var counts = new HashMap<String, Integer>();
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (!stack.isEmpty()) counts.merge(itemId(stack), stack.getCount(), Integer::sum);
        }
        return Map.copyOf(counts);
    }

    /** One complete pickup/place/return interaction. Never drops items or touches a pre-existing cursor stack. */
    public static int move(MultiPlayerGameMode mode, LocalPlayer player, AbstractContainerMenu menu,
                           List<Slot> sources, List<Slot> destinations, String itemId, int maximum,
                           boolean wholeStackOnly) {
        if (player.containerMenu != menu || !menu.getCarried().isEmpty() || maximum <= 0) return 0;
        for (Slot source : sources) {
            ItemStack stack = source.getItem();
            if (stack.isEmpty() || !itemId(stack).equals(itemId) || !source.mayPickup(player)) continue;
            List<Slot> compatible = destinations.stream().filter(slot -> slot != source && capacity(slot, stack) > 0).toList();
            if (compatible.isEmpty()) continue;
            int room = compatible.stream().mapToInt(slot -> capacity(slot, stack)).sum();
            if (stack.getCount() <= maximum && stack.getCount() <= room) {
                return exchange(mode, player, menu, source, compatible, false);
            }
            if (!wholeStackOnly && source.mayPlace(stack)) {
                return exchange(mode, player, menu, source, List.of(compatible.getFirst()), true);
            }
        }
        return 0;
    }

    private static int exchange(MultiPlayerGameMode mode, LocalPlayer player, AbstractContainerMenu menu,
                                Slot source, List<Slot> destinations, boolean single) {
        ItemStack original = source.getItem().copy();
        mode.handleContainerInput(menu.containerId, source.index, 0, ContainerInput.PICKUP, player);
        if (menu.getCarried().isEmpty()) return 0;
        int moved = 0;
        if (ItemStack.matches(original, menu.getCarried())) {
            for (Slot destination : destinations) {
                if (menu.getCarried().isEmpty()) break;
                if (capacity(destination, original) == 0) continue;
                int before = destination.getItem().getCount();
                mode.handleContainerInput(menu.containerId, destination.index, single ? 1 : 0, ContainerInput.PICKUP, player);
                ItemStack current = destination.getItem();
                if (ItemStack.isSameItemSameComponents(original, current)) moved += Math.max(0, current.getCount() - before);
            }
        }
        if (!menu.getCarried().isEmpty() && source.mayPlace(menu.getCarried())) {
            mode.handleContainerInput(menu.containerId, source.index, 0, ContainerInput.PICKUP, player);
        }
        if (!menu.getCarried().isEmpty()) {
            throw new IllegalStateException("The menu changed during a transfer. Return the cursor stack before continuing.");
        }
        return moved;
    }

    private static int capacity(Slot destination, ItemStack incoming) {
        if (!destination.mayPlace(incoming)) return 0;
        ItemStack present = destination.getItem();
        if (!present.isEmpty() && !ItemStack.isSameItemSameComponents(present, incoming)) return 0;
        return Math.max(0, Math.min(incoming.getMaxStackSize(), destination.getMaxStackSize(incoming)) - present.getCount());
    }
}
