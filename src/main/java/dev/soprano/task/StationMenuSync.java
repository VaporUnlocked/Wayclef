package dev.soprano.task;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;

import java.util.Map;
import java.util.WeakHashMap;

public final class StationMenuSync {
    private static final int FURNACE_PROPERTIES = (1 << AbstractFurnaceBlockEntity.DATA_LIT_TIME)
        | (1 << AbstractFurnaceBlockEntity.DATA_COOKING_PROGRESS) | (1 << AbstractFurnaceBlockEntity.DATA_COOKING_TOTAL_TIME);
    private static final int BREWING_PROPERTIES = (1 << BrewingStandBlockEntity.NUM_DATA_VALUES) - 1;
    private static final int ENCHANTING_PROPERTIES = (1 << 10) - 1;
    private static final Map<AbstractContainerMenu, State> MENUS = new WeakHashMap<>();

    static final class State {
        long contents;
        long craftResult;
        long tradeResult;
        long furnaceRevision, furnaceInput, furnaceOutput;
        int furnaceProperties;
        int brewingProperties;
        int enchantingProperties;

        boolean furnaceInputsAhead() { return furnaceInput > furnaceOutput; }
    }

    private StationMenuSync() { }

    static State state(AbstractContainerMenu menu) { return MENUS.computeIfAbsent(menu, ignored -> new State()); }

    static boolean ready(AbstractContainerMenu menu, State state) {
        return state.contents != 0 && (!(menu instanceof AbstractFurnaceMenu)
            || (state.furnaceProperties & FURNACE_PROPERTIES) == FURNACE_PROPERTIES)
            && (!(menu instanceof BrewingStandMenu) || (state.brewingProperties & BREWING_PROPERTIES) == BREWING_PROPERTIES)
            && (!(menu instanceof EnchantmentMenu) || (state.enchantingProperties & ENCHANTING_PROPERTIES) == ENCHANTING_PROPERTIES);
    }

    public static void contents(ClientPacketListener connection, int id) {
        AbstractContainerMenu menu = current(connection, id);
        if (menu == null) return;
        State state = state(menu);
        state.contents++;
        if (menu instanceof AbstractFurnaceMenu) state.furnaceInput = state.furnaceOutput = ++state.furnaceRevision;
        else if (menu instanceof MerchantMenu) state.tradeResult++;
    }

    public static void slot(ClientPacketListener connection, int id, int slot) {
        AbstractContainerMenu current = current(connection, id);
        if (current instanceof AbstractCraftingMenu menu && slot == menu.getResultSlot().index) {
            state(menu).craftResult++;
        } else if (current instanceof MerchantMenu && slot == 2) {
            state(current).tradeResult++;
        } else if (current instanceof AbstractFurnaceMenu menu) {
            State state = state(menu);
            if (slot == AbstractFurnaceMenu.INGREDIENT_SLOT) state.furnaceInput = ++state.furnaceRevision;
            else if (slot == menu.getResultSlot().index) state.furnaceOutput = ++state.furnaceRevision;
        }
    }

    public static void data(ClientPacketListener connection, int id, int property) {
        if (property < 0 || property >= Integer.SIZE) return;
        AbstractContainerMenu menu = current(connection, id);
        if (menu instanceof AbstractFurnaceMenu) state(menu).furnaceProperties |= 1 << property;
        else if (menu instanceof BrewingStandMenu) state(menu).brewingProperties |= 1 << property;
        else if (menu instanceof EnchantmentMenu) state(menu).enchantingProperties |= 1 << property;
    }

    private static AbstractContainerMenu current(ClientPacketListener connection, int id) {
        var player = Minecraft.getInstance().player;
        if (player == null || player.connection != connection) return null;
        AbstractContainerMenu menu = player.containerMenu;
        return menu.containerId == id ? menu : null;
    }
}
