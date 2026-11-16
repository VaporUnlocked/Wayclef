package dev.soprano.task;

import dev.soprano.inventory.InventoryJobs;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/** Trades through the normal offer selection, server result slot, and inventory clicks. */
final class MerchantTrade {
    private static final int RESULT_SLOT = 2;
    private static final int MENU_TIMEOUT = 120;

    private final LocalPlayer player;
    private final MultiPlayerGameMode mode;
    private final MerchantMenu menu;
    private final GatherTrading tradeInfo;
    private final UUID trader;
    private final Item result;
    private final String itemId;
    private final int target;
    private final int startCount;
    private int selected = -1;
    private int previousCount;
    private int started;
    private boolean buying;
    private long revision;
    private String status = "Reading the merchant's offers.";

    MerchantTrade(LocalPlayer player, MultiPlayerGameMode mode, MerchantMenu menu, GatherTrading tradeInfo, JobSpec spec) {
        this.player = player;
        this.mode = mode;
        this.menu = menu;
        this.tradeInfo = tradeInfo;
        trader = spec.entityId();
        itemId = spec.ids().getFirst();
        result = InventoryJobs.item(itemId);
        target = spec.limit();
        startCount = InventoryJobs.count(player, itemId);
        started = player.tickCount;
    }

    String status() { return status; }

    /** Returns true after a trade batch or after visiting a merchant for its live offers. */
    boolean tick() {
        if (!menu.getCarried().isEmpty()) throw new IllegalStateException("The merchant menu cursor is occupied.");
        var sync = StationMenuSync.state(menu);
        var offers = menu.getOffers();
        int elapsed = player.tickCount - started;
        if (elapsed > MENU_TIMEOUT) throw new IllegalStateException("The server did not confirm the merchant operation.");
        if (offers.isEmpty() || sync.contents == 0) return false;

        if (selected < 0) {
            tradeInfo.observe(trader, offers);
            selected = GatherTrading.payable(player, offers, result);
            if (selected < 0) {
                status = offers.stream().anyMatch(offer -> GatherTrading.produces(offer, result))
                    ? "Found a trade for " + itemId + " and recorded its payment."
                    : "This merchant has no available trade for " + itemId + ".";
                return true;
            }
            revision = sync.tradeResult;
            menu.setSelectionHint(selected);
            menu.tryMoveItems(selected);
            player.connection.send(new ServerboundSelectTradePacket(selected));
            started = player.tickCount;
            status = "Selecting the offer for " + itemId + ".";
            return false;
        }

        int count = InventoryJobs.count(player, itemId);
        if (buying) {
            if (elapsed < 3 || count <= previousCount) return false;
            if (count - startCount >= target || menu.getSlot(RESULT_SLOT).getItem().isEmpty()) {
                tradeInfo.observe(trader, offers);
                status = "Traded for " + (count - startCount) + " " + itemId + ".";
                return true;
            }
        } else if (sync.tradeResult == revision) return false;

        if (selected >= offers.size()) throw new IllegalStateException("The merchant changed its offers.");
        var offer = offers.get(selected);
        var output = menu.getSlot(RESULT_SLOT).getItem();
        var inputs = menu.getSlot(0).getItem();
        var secondary = menu.getSlot(1).getItem();
        if (!GatherTrading.produces(offer, result) || !output.is(result)
            || !offer.satisfiedBy(inputs, secondary) && !offer.satisfiedBy(secondary, inputs)) {
            if (count > startCount) {
                tradeInfo.observe(trader, offers);
                status = "Traded for " + (count - startCount) + " " + itemId + ".";
                return true;
            }
            throw new IllegalStateException("The selected trade no longer matches its server-verified result or payment.");
        }
        Slot destination = destination(output);
        if (destination == null) throw new IllegalStateException("There is no inventory space for the merchant result.");
        previousCount = count;
        buying = true;
        started = player.tickCount;
        ItemStack expected = output.copy();
        mode.handleContainerInput(menu.containerId, RESULT_SLOT, 0, ContainerInput.PICKUP, player);
        if (!ItemStack.matches(menu.getCarried(), expected)) {
            throw new IllegalStateException("The merchant did not release exactly one requested result.");
        }
        mode.handleContainerInput(menu.containerId, destination.index, 0, ContainerInput.PICKUP, player);
        if (!menu.getCarried().isEmpty()) throw new IllegalStateException("The merchant result did not fit the reserved slot.");
        status = "Collecting " + itemId + " from the merchant.";
        return false;
    }

    private Slot destination(ItemStack result) {
        var inventory = player.getInventory();
        return GatherInventory.hotbarFirst(menu.slots.stream()
            .filter(slot -> slot.container == inventory && slot.getContainerSlot() < Inventory.INVENTORY_SIZE && slot.mayPlace(result))
            .filter(slot -> !slot.hasItem() || ItemStack.isSameItemSameComponents(slot.getItem(), result))
            .filter(slot -> slot.getMaxStackSize(result) - slot.getItem().getCount() >= result.getCount())
            .toList()).stream().findFirst().orElse(null);
    }
}
