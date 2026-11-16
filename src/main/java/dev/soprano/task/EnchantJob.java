package dev.soprano.task;

import dev.soprano.inventory.InventoryJobs;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.List;

/** Selects an offer advertised by the actual enchanting table. */
final class EnchantJob {
    private static final int SYNC_TIMEOUT = 200;
    private static final int ITEM_SLOT = 0;
    private static final int LAPIS_SLOT = 1;

    private enum Phase { LOAD, OFFER, LAPIS, RESULT, COLLECTED }
    private record Offer(int index, int cost, int seed, Holder<Enchantment> enchantment, int level) { }

    private final Registry<Enchantment> registry;
    private final LocalPlayer player;
    private final MultiPlayerGameMode mode;
    private final Item input;
    private final int offerIndex;
    private final Holder<Enchantment> desired;
    private Phase phase = Phase.LOAD;
    private ItemStack original, output;
    private Offer offer;
    private int tick, deadline, outputCount;
    private String status = "Preparing the enchanting table.";

    EnchantJob(JobSpec spec, ClientLevel level, LocalPlayer player, MultiPlayerGameMode mode) {
        registry = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        this.player = player;
        this.mode = mode;
        input = InventoryJobs.item(spec.ids().getFirst());
        offerIndex = spec.limit() - 1;
        if (spec.ids().size() == 1) desired = null;
        else {
            Identifier id = Identifier.parse(spec.ids().get(1));
            desired = registry.get(id).orElseThrow(() -> new IllegalArgumentException("Unknown enchantment " + id + "."));
        }
    }

    String status() { return status; }

    StationJob.Result tick(EnchantmentMenu menu, int delay) {
        if (++tick < deadline) return StationJob.Result.RUNNING;
        var own = GatherInventory.hotbarFirst(InventoryJobs.playerSlots(menu, player));
        Slot item = menu.getSlot(ITEM_SLOT);
        Slot lapis = menu.getSlot(LAPIS_SLOT);
        return switch (phase) {
            case LOAD -> load(menu, own, item, lapis, delay);
            case OFFER -> choose(menu, item, lapis);
            case LAPIS -> completeOffer(menu, own, item, lapis, delay);
            case RESULT -> collect(menu, own, item, delay);
            case COLLECTED -> {
                if (item.hasItem() || countExact(own, output) != outputCount) {
                    throw new IllegalStateException("The enchanted item transfer was not confirmed.");
                }
                status = "Collected " + output.getHoverName().getString() + ".";
                yield StationJob.Result.FINISHED;
            }
        };
    }

    private StationJob.Result load(EnchantmentMenu menu, List<Slot> own, Slot item, Slot lapis, int delay) {
        if (item.hasItem() || lapis.hasItem()) throw new IllegalStateException("Empty the enchanting table first.");
        var compatible = own.stream().filter(slot -> {
            ItemStack stack = slot.getItem();
            return stack.is(input) && stack.isEnchantable() && item.mayPlace(stack);
        }).toList();
        original = compatible.isEmpty() ? ItemStack.EMPTY : compatible.getFirst().getItem().copyWithCount(1);
        if (original.isEmpty() || InventoryJobs.move(mode, player, menu, compatible, List.of(item),
            InventoryJobs.itemId(input), 1, false) != 1) throw new IllegalStateException("Carry an enchantable " + InventoryJobs.itemId(input) + ".");
        delayUntil(Phase.OFFER, delay);
        return running("Waiting for the enchanting offers.");
    }

    private StationJob.Result choose(EnchantmentMenu menu, Slot item, Slot lapis) {
        requireInput(item);
        if (lapis.hasItem()) throw new IllegalStateException("Unexpected lapis was placed in the enchanting table.");
        int available = InventoryJobs.count(player, InventoryJobs.itemId(Items.LAPIS_LAZULI));
        for (int i = offerIndex >= 0 ? offerIndex : 2; i >= 0; i--) {
            Offer candidate = offerAt(menu, i);
            if (candidate != null && (desired == null || desired.equals(candidate.enchantment()))
                && (player.isCreative() || player.experienceLevel >= candidate.cost() && available >= i + 1)) {
                offer = candidate;
                phase = Phase.LAPIS;
                return running("Selected enchanting offer " + (i + 1) + ".");
            }
            if (offerIndex >= 0) break;
        }
        if (menu.costs[0] == 0 && menu.costs[1] == 0 && menu.costs[2] == 0 && tick - deadline < SYNC_TIMEOUT) {
            return running("Waiting for the enchanting offers.");
        }
        throw new IllegalStateException("No affordable enchanting offer matches the request. Check experience, lapis, and the displayed offers.");
    }

    private StationJob.Result completeOffer(EnchantmentMenu menu, List<Slot> own, Slot item, Slot lapis, int delay) {
        requireInput(item);
        if (!offer.equals(offerAt(menu, offer.index()))) throw new IllegalStateException("The selected enchanting offer changed.");
        int needed = offer.index() + 1;
        if (!player.isCreative()) {
            if (player.experienceLevel < offer.cost() || lapis.hasItem() && !lapis.getItem().is(Items.LAPIS_LAZULI)
                || lapis.getItem().getCount() > needed) throw new IllegalStateException("The selected offer or its supplies changed.");
            if (lapis.getItem().getCount() < needed) {
                if (InventoryJobs.move(mode, player, menu, own, List.of(lapis), InventoryJobs.itemId(Items.LAPIS_LAZULI),
                    needed - lapis.getItem().getCount(), false) == 0) throw new IllegalStateException("The lapis supply changed.");
                deadline = tick + delay;
                return running("Loading lapis for the selected enchantment.");
            }
        }
        mode.handleInventoryButtonClick(menu.containerId, offer.index());
        delayUntil(Phase.RESULT, delay);
        return running("Waiting for the server to enchant the item.");
    }

    private StationJob.Result collect(EnchantmentMenu menu, List<Slot> own, Slot item, int delay) {
        ItemStack result = item.getItem();
        if (ItemStack.matches(original, result) && tick - deadline < SYNC_TIMEOUT) {
            return running("Waiting for the server to enchant the item.");
        }
        Item resultItem = input == Items.BOOK ? Items.ENCHANTED_BOOK : input;
        if (!result.is(resultItem) || result.getCount() != 1 || EnchantmentHelper.getEnchantmentsForCrafting(result)
            .getLevel(offer.enchantment()) < offer.level()) throw new IllegalStateException("The server did not return the selected enchantment.");
        output = result.copy();
        outputCount = countExact(own, output) + 1;
        if (InventoryJobs.move(mode, player, menu, List.of(item), own, InventoryJobs.itemId(output), 1, true) != 1) {
            throw new IllegalStateException("No inventory slot can hold the enchanted item.");
        }
        delayUntil(Phase.COLLECTED, delay);
        return running("Collecting the enchanted item.");
    }

    private void requireInput(Slot item) {
        if (!ItemStack.matches(original, item.getItem())) throw new IllegalStateException("The selected enchanting item changed.");
    }

    private Offer offerAt(EnchantmentMenu menu, int index) {
        Holder<Enchantment> enchantment = registry.asHolderIdMap().byId(menu.enchantClue[index]);
        return menu.costs[index] > 0 && enchantment != null && menu.levelClue[index] > 0
            ? new Offer(index, menu.costs[index], menu.getEnchantmentSeed(), enchantment, menu.levelClue[index]) : null;
    }

    private void delayUntil(Phase next, int delay) {
        phase = next;
        deadline = tick + delay;
    }

    private static int countExact(List<Slot> own, ItemStack stack) {
        return own.stream().filter(slot -> ItemStack.isSameItemSameComponents(slot.getItem(), stack))
            .mapToInt(slot -> slot.getItem().getCount()).sum();
    }

    private StationJob.Result running(String message) {
        status = message;
        return StationJob.Result.RUNNING;
    }
}
