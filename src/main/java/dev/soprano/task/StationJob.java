package dev.soprano.task;

import dev.soprano.inventory.InventoryJobs;
import dev.soprano.inventory.SignStorage;
import dev.soprano.mixin.FurnaceDataAccessor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.item.crafting.display.SmithingRecipeDisplay;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.EnchantingTableBlock;
import net.minecraft.world.level.block.SmithingTableBlock;
import net.minecraft.world.level.block.StonecutterBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.storage.loot.providers.number.floats.ResolvableFloat;
import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

final class StationJob {
    enum Result { RUNNING, NEXT, FINISHED, COOKING }
    private record Pending(String itemId, int expectedPlayerCount) { }
    private record CraftAck(long result, long contents, boolean requireContents) {
        boolean arrived(StationMenuSync.State sync) {
            return sync.craftResult > result && (!requireContents || sync.contents > contents);
        }
    }
    private record CookingRecipe(int duration, ItemStack result) { }
    private record FutureFuel(int burnTicks, int cookDuration) { }
    private record CookWait(Block furnace, ItemStack input, ItemStack output, int yield,
                            int remainingTicks, int remainingOutput) { }

    private static final class ManualCraft {
        final List<ItemStack> pattern;
        final int outputCount;
        int nextSlot;
        boolean tookResult;
        String rejection;

        ManualCraft(List<ItemStack> pattern, int outputCount) {
            this.pattern = pattern;
            this.outputCount = outputCount;
        }
    }

    private static final int CLICK_DELAY = 4;
    private static final int RECIPE_TIMEOUT = 100;
    private static final int SYNC_TIMEOUT = 200;
    private static final int FURNACE_OUTPUT_FLUSH_DELAY = 60;

    private final JobSpec spec;
    private final ClientLevel level;
    private final LocalPlayer player;
    private final MultiPlayerGameMode mode;
    private final boolean gathering;
    private final BrewJob brew;
    private final EnchantJob enchant;
    private final Map<String, Integer> remaining;
    private final List<SignStorage.LabeledContainer> storage;
    private int nextStorage, done, inputLoaded, elapsed, delay, recipeWait;
    private int syncWait;
    private int stonecutChoice = -1;
    private long bookResultBefore, bookContentsBefore;
    private int initialFurnaceInput, previousFurnaceInput = -1;
    private int furnaceYield, furnaceSyncWait, fuelStartWait, verifiedCooked;
    private float bestCookProgress;
    private boolean verified, orderedRecipe, gameDataCrafting, takeExistingFurnaceOutput, checkedCookingRecipe, detachedOnce;
    private AbstractContainerMenu openedMenu;
    private StationMenuSync.State sync;
    private CraftAck craftAck;
    private Pending pending;
    private CookingRecipe cookingRecipe;
    private CookWait readyToDetach, detached;
    private ManualCraft manualCraft;
    private String stockedInput;
    private ItemStack stonecutOutput;
    private String status = "Preparing the station.";

    StationJob(JobSpec spec, ClientLevel level, LocalPlayer player, MultiPlayerGameMode mode) {
        this(spec, level, player, mode, false);
    }

    StationJob(JobSpec spec, ClientLevel level, LocalPlayer player, MultiPlayerGameMode mode, boolean gathering) {
        this.spec = spec;
        this.level = level;
        this.player = player;
        this.mode = mode;
        this.gathering = gathering;
        brew = spec.kind() == JobSpec.Kind.BREW ? new BrewJob(spec, level, player, mode) : null;
        enchant = spec.kind() == JobSpec.Kind.ENCHANT ? new EnchantJob(spec, level, player, mode) : null;
        if (brew == null && enchant == null) for (String id : spec.ids()) InventoryJobs.item(id);
        spec.amounts().keySet().forEach(InventoryJobs::item);
        remaining = new LinkedHashMap<>(spec.amounts());
        storage = storageJob() ? InventoryJobs.labels(level, player).stream()
            .filter(label -> label.acceptedItems().stream().anyMatch(remaining::containsKey)).limit(spec.limit()).toList() : List.of();
    }

    boolean storageJob() { return spec.kind() == JobSpec.Kind.UNLOAD || spec.kind() == JobSpec.Kind.RESTOCK; }
    boolean inventoryCrafting() { return spec.kind() == JobSpec.Kind.CRAFT && spec.target() == null; }
    List<SignStorage.LabeledContainer> storageLabels() { return storage; }
    String status() { return status; }
    void opened() {
        openedMenu = player.containerMenu;
        sync = StationMenuSync.state(openedMenu);
        syncWait = 0;
        delay = inventoryCrafting() ? networkDelay() : 0;
    }

    int remainingCookTicks() {
        CookWait wait = readyToDetach == null ? detached : readyToDetach;
        return wait == null ? 0 : wait.remainingTicks();
    }

    int remainingCookOutput() {
        CookWait wait = readyToDetach == null ? detached : readyToDetach;
        return wait == null ? 0 : wait.remainingOutput();
    }

    void detachFurnace() {
        if (readyToDetach == null || detachedOnce) throw new IllegalStateException("The furnace is not ready to cook on its own.");
        detachedOnce = true;
        detached = readyToDetach;
        readyToDetach = null;
    }

    BlockPos target() {
        if (!storageJob()) return spec.target();
        return nextStorage >= storage.size() ? null : InventoryJobs.position(storage.get(nextStorage).container());
    }

    boolean validTarget(BlockPos position) {
        var state = level.getBlockState(position);
        return switch (spec.kind()) {
            case CRAFT -> state.getBlock() instanceof CraftingTableBlock;
            case SMITH -> state.getBlock() instanceof SmithingTableBlock;
            case BREW -> state.getBlock() instanceof BrewingStandBlock;
            case ENCHANT -> state.getBlock() instanceof EnchantingTableBlock;
            case STONECUT -> state.getBlock() instanceof StonecutterBlock;
            case FURNACE -> state.getBlock() instanceof AbstractFurnaceBlock && (detached == null
                || state.is(detached.furnace()) && level.getBlockEntity(position) instanceof AbstractFurnaceBlockEntity);
            case RESTOCK, UNLOAD -> currentLabel() != null;
            case TAKE -> InventoryJobs.storageBlock(state) && level.getBlockEntity(position) != null;
            default -> false;
        };
    }

    private SignStorage.LabeledContainer currentLabel() {
        BlockPos target = target();
        if (target == null) return null;
        SignStorage.LabeledContainer label = InventoryJobs.labelAt(level, target);
        return label != null && label.acceptedItems().equals(storage.get(nextStorage).acceptedItems()) ? label : null;
    }

    void advance() {
        nextStorage++;
        delay = elapsed = 0;
        verified = false;
    }

    String incompleteStorage() {
        return status + " Remaining " + remaining.entrySet().stream()
            .map(entry -> entry.getValue() + " " + entry.getKey()).reduce((a, b) -> a + ", " + b).orElse("none") + ".";
    }

    Result tick(AbstractContainerMenu menu) {
        readyToDetach = null;
        if (menu != openedMenu || menu != player.containerMenu) throw new IllegalStateException("The station menu changed.");
        if (!inventoryCrafting() && !StationMenuSync.ready(menu, sync)) return awaitSync("Waiting for the server to send the station contents.");
        if (craftAck != null) {
            if (!craftAck.arrived(sync) || !menu.getCarried().isEmpty() || !playerTransferMatches()) {
                return awaitSync("Waiting for the server to confirm the crafting grid.");
            }
            craftAck = null;
        }
        syncWait = 0;
        if (!menu.getCarried().isEmpty()) throw new IllegalStateException("Return the cursor stack before the job can continue.");
        if (delay > 0) {
            delay--;
            return Result.RUNNING;
        }
        if (pending != null) {
            if (!playerTransferMatches()) {
                throw new IllegalStateException("Inventory changed while waiting for the " + pending.itemId() + " transfer.");
            }
            pending = null;
        }
        return switch (spec.kind()) {
            case CRAFT -> craft(menu);
            case SMITH -> smith(menu);
            case BREW -> {
                if (!(menu instanceof BrewingStandMenu stand)) throw new IllegalStateException("The selected block did not open a brewing stand.");
                Result result = brew.tick(stand, networkDelay());
                status = brew.status();
                yield result;
            }
            case ENCHANT -> {
                if (!(menu instanceof EnchantmentMenu table)) throw new IllegalStateException("The selected block did not open an enchanting table.");
                Result result = enchant.tick(table, networkDelay());
                status = enchant.status();
                yield result;
            }
            case STONECUT -> stonecut(menu);
            case FURNACE -> furnace(menu);
            case UNLOAD, RESTOCK -> storage(menu);
            case TAKE -> take(menu);
            default -> throw new IllegalStateException("Unknown station job.");
        };
    }

    private boolean playerTransferMatches() {
        return pending == null || InventoryJobs.count(player, pending.itemId()) == pending.expectedPlayerCount();
    }

    private Result awaitSync(String message) {
        if (++syncWait > SYNC_TIMEOUT) throw new IllegalStateException(message.replace("Waiting for", "Timed out waiting for"));
        status = message;
        return Result.RUNNING;
    }

    private Result storage(AbstractContainerMenu menu) {
        if (remaining.isEmpty()) return Result.FINISHED;
        SignStorage.LabeledContainer label = currentLabel();
        if (label == null) {
            status = "A storage label changed or is ambiguous.";
            return Result.NEXT;
        }
        List<Slot> other = InventoryJobs.storageSlots(menu, player);
        if (other.isEmpty()) {
            status = "The storage menu has no supported physical slot adapter.";
            return Result.NEXT;
        }
        List<Slot> own = InventoryJobs.playerSlots(menu, player);
        boolean unload = spec.kind() == JobSpec.Kind.UNLOAD;
        for (String id : label.acceptedItems()) {
            int outstanding = remaining.getOrDefault(id, 0);
            int moved = transfer(menu, unload ? own : other, unload ? other : own, id, outstanding, false, !unload);
            if (moved == 0) continue;
            if (moved == outstanding) remaining.remove(id);
            else remaining.put(id, outstanding - moved);
            done += moved;
            status = (unload ? "Unloaded " : "Restocked ") + done + " items.";
            return Result.RUNNING;
        }
        status = "Matching stock or free slots were unavailable.";
        return Result.NEXT;
    }

    private Result take(AbstractContainerMenu menu) {
        String item = spec.ids().getFirst();
        int outstanding = spec.limit() - done;
        if (outstanding <= 0) return Result.FINISHED;
        List<Slot> storage = InventoryJobs.storageSlots(menu, player);
        if (storage.isEmpty()) throw new IllegalStateException("This storage menu has no supported physical slots.");
        int moved = transfer(menu, storage, InventoryJobs.playerSlots(menu, player), item, outstanding, false, true);
        if (moved == 0) {
            if (done == 0) throw new IllegalStateException("No available " + item + " in this container.");
            return Result.FINISHED;
        }
        done += moved;
        status = "Took " + done + " " + item + " from storage.";
        return Result.RUNNING;
    }

    private Result craft(AbstractContainerMenu rawMenu) {
        if (!(rawMenu instanceof AbstractCraftingMenu menu)) {
            throw new IllegalStateException("The selected block did not open a crafting menu.");
        }
        if (!verified) {
            if (menu.getInputGridSlots().stream().anyMatch(Slot::hasItem)) {
                throw new IllegalStateException("Empty the crafting grid before starting the job.");
            }
            verified = true;
        }
        String itemId = spec.ids().getFirst();
        int needed = spec.limit() - done;
        if (manualCraft != null) return craftFromGameData(menu, itemId);
        if (needed == 0) return Result.FINISHED;
        Slot result = menu.getResultSlot();
        boolean bookConfirmed = gathering && orderedRecipe
            && (sync.craftResult > bookResultBefore || sync.contents > bookContentsBefore)
            && menu.getInputGridSlots().stream().anyMatch(Slot::hasItem);
        if (result.hasItem() && (!gathering || bookConfirmed)) {
            if (!InventoryJobs.itemId(result.getItem()).equals(itemId)) {
                throw new IllegalStateException("The server returned a different crafting result.");
            }
            int moved = takeCraftingResult(menu, result, InventoryJobs.playerSlots(menu, player), itemId, needed);
            if (moved == 0) throw new IllegalStateException("No inventory slot fits the next full crafting result.");
            done += moved;
            orderedRecipe = false;
            status = "Crafted " + done + " / " + spec.limit() + " " + itemId + ".";
            return Result.RUNNING;
        }
        if (orderedRecipe) {
            if (++recipeWait > Math.max(RECIPE_TIMEOUT, networkDelay() * 2)) {
                return tryCraftFromGameData(menu, itemId, needed, new IllegalStateException("The server did not place the recipe ingredients."));
            }
            return Result.RUNNING;
        }
        if (gameDataCrafting) return tryCraftFromGameData(menu, itemId, needed, new IllegalStateException("The crafting grid is no longer empty."));
        RecipeDisplayEntry recipe;
        try {
            recipe = chooseRecipe(menu, itemId, needed);
        } catch (IllegalStateException unavailable) {
            return tryCraftFromGameData(menu, itemId, needed, unavailable);
        }
        bookResultBefore = sync.craftResult;
        bookContentsBefore = sync.contents;
        mode.handlePlaceRecipe(menu.containerId, recipe.id(), false);
        orderedRecipe = true;
        recipeWait = 0;
        status = "Waiting for the recipe book to fill the grid.";
        return Result.RUNNING;
    }

    private Result stonecut(AbstractContainerMenu rawMenu) {
        if (!(rawMenu instanceof StonecutterMenu menu)) throw new IllegalStateException("The selected block did not open a stonecutter.");
        var input = menu.getSlot(StonecutterMenu.INPUT_SLOT);
        var result = menu.getSlot(StonecutterMenu.RESULT_SLOT);
        String inputId = spec.ids().getFirst(), outputId = spec.ids().getLast();
        var own = InventoryJobs.playerSlots(menu, player);
        if (!verified) {
            if (input.hasItem() || result.hasItem()) throw new IllegalStateException("Empty the stonecutter before starting the job.");
            verified = true;
        }
        if (done == spec.limit()) return Result.FINISHED;
        if (input.hasItem() && !input.getItem().is(InventoryJobs.item(inputId))) {
            throw new IllegalStateException("The stonecutter input changed.");
        }
        if (inputLoaded < spec.limit()) {
            int moved = transfer(menu, own, List.of(input), inputId, spec.limit() - inputLoaded, false, false);
            if (moved > 0) {
                inputLoaded += moved;
                status = "Loading " + inputLoaded + " / " + spec.limit() + " into the stonecutter.";
                return Result.RUNNING;
            }
            if (!input.hasItem()) throw new IllegalStateException("Missing " + inputId + " for the stonecutter.");
        }
        if (input.getItem().getCount() != inputLoaded - done) {
            throw new IllegalStateException("The stonecutter input count changed.");
        }
        if (stonecutChoice < 0 || menu.getSelectedRecipeIndex() != stonecutChoice && recipeWait == 0) {
            selectStonecut(menu, outputId);
            return Result.RUNNING;
        }
        if (!result.hasItem() || menu.getSelectedRecipeIndex() != stonecutChoice) {
            if (++recipeWait > Math.max(RECIPE_TIMEOUT, 2 * networkDelay())) {
                throw new IllegalStateException("The server did not confirm the stonecutter result for " + outputId + ".");
            }
            return Result.RUNNING;
        }
        if (!ItemStack.matches(stonecutOutput, result.getItem())) {
            throw new IllegalStateException("The server returned a different stonecutter result.");
        }
        if (transfer(menu, List.of(result), own, outputId, stonecutOutput.getCount(), true, true) != stonecutOutput.getCount()) {
            throw new IllegalStateException("No inventory slot fits the full stonecutter result.");
        }
        done++;
        recipeWait = 0;
        status = "Stonecut " + done + " / " + spec.limit() + " " + inputId + ".";
        return Result.RUNNING;
    }

    private void selectStonecut(StonecutterMenu menu, String outputId) {
        var context = SlotDisplayContext.fromLevel(level);
        var recipes = menu.getVisibleRecipes().entries();
        Item wanted = InventoryJobs.item(outputId);
        for (int i = 0; i < recipes.size(); i++) {
            ItemStack shown = recipes.get(i).recipe().optionDisplay().resolveForFirstStack(context);
            if (!shown.is(wanted)) continue;
            stonecutChoice = i;
            stonecutOutput = shown.copy();
            mode.handleInventoryButtonClick(menu.containerId, i);
            recipeWait = 1;
            status = "Waiting for the server to confirm the stonecutter recipe for " + outputId + ".";
            return;
        }
        throw new IllegalStateException("The server has no matching stonecutter recipe for " + outputId + ".");
    }

    private Result smith(AbstractContainerMenu rawMenu) {
        if (!(rawMenu instanceof SmithingMenu menu)) throw new IllegalStateException("The selected block did not open a smithing table.");
        var inputs = List.of(menu.getSlot(SmithingMenu.TEMPLATE_SLOT), menu.getSlot(SmithingMenu.BASE_SLOT),
            menu.getSlot(SmithingMenu.ADDITIONAL_SLOT));
        var result = menu.getSlot(SmithingMenu.RESULT_SLOT);
        String itemId = spec.ids().getFirst();
        if (manualCraft == null) {
            if (inputs.stream().anyMatch(Slot::hasItem) || result.hasItem()) {
                throw new IllegalStateException("Empty the smithing table before starting the job.");
            }
            if (done == spec.limit()) return Result.FINISHED;
            manualCraft = chooseSmithing(menu, inputs, InventoryJobs.item(itemId), spec.limit() - done);
            if (manualCraft == null) throw new IllegalStateException("No available smithing recipe and ingredients for " + itemId + ".");
        }
        for (int i = 0; i < inputs.size(); i++) {
            ItemStack expected = i < manualCraft.nextSlot ? manualCraft.pattern.get(i) : ItemStack.EMPTY;
            if (!ItemStack.matches(expected, inputs.get(i).getItem())) throw new IllegalStateException("The smithing table contents changed.");
        }
        while (manualCraft.nextSlot < inputs.size() && manualCraft.pattern.get(manualCraft.nextSlot).isEmpty()) manualCraft.nextSlot++;
        var own = InventoryJobs.playerSlots(menu, player);
        if (manualCraft.nextSlot < inputs.size()) {
            ItemStack expected = manualCraft.pattern.get(manualCraft.nextSlot);
            var matching = own.stream().filter(slot -> ItemStack.isSameItemSameComponents(slot.getItem(), expected)).toList();
            if (transfer(menu, matching, List.of(inputs.get(manualCraft.nextSlot)), InventoryJobs.itemId(expected), 1, false, false) != 1) {
                throw new IllegalStateException("The smithing ingredients changed while filling the table.");
            }
            manualCraft.nextSlot++;
            status = "Filling the smithing table for " + itemId + ".";
            return Result.RUNNING;
        }
        if (!result.hasItem()) {
            if (++recipeWait > Math.max(RECIPE_TIMEOUT, networkDelay() * 2)) {
                throw new IllegalStateException("The server did not accept the smithing recipe for " + itemId + ".");
            }
            status = "Waiting for the server to confirm the smithing recipe for " + itemId + ".";
            return Result.RUNNING;
        }
        if (!result.getItem().is(InventoryJobs.item(itemId)) || result.getItem().getCount() != manualCraft.outputCount) {
            throw new IllegalStateException("The server returned a different smithing result.");
        }
        int moved = transfer(menu, List.of(result), own, itemId, manualCraft.outputCount, true, true);
        if (moved != manualCraft.outputCount) throw new IllegalStateException("No inventory slot fits the full smithing result.");
        done += moved;
        manualCraft = null;
        recipeWait = 0;
        status = "Smithed " + done + " / " + spec.limit() + " " + itemId + ".";
        return Result.RUNNING;
    }

    private ManualCraft chooseSmithing(SmithingMenu menu, List<Slot> inputs, Item item, int needed) {
        ContextMap context = SlotDisplayContext.fromLevel(level);
        var contents = craftingContents(menu);
        var candidates = new ArrayList<RecipeDisplay>();
        recipes().stream().map(RecipeDisplayEntry::display).filter(SmithingRecipeDisplay.class::isInstance).forEach(candidates::add);
        candidates.addAll(GatherPlan.gameRecipeDisplays(level, item));
        for (RecipeDisplay candidate : candidates) {
            if (!(candidate instanceof SmithingRecipeDisplay recipe) || !(recipe.result() instanceof SlotDisplay.ItemStackSlotDisplay)
                || !recipe.isEnabled(level.enabledFeatures())) continue;
            ItemStack output = recipe.result().resolveForFirstStack(context);
            if (!output.is(item) || output.getCount() > needed) continue;
            var cells = GatherPlan.smithingSlots(recipe);
            List<StackedContents.IngredientInfo<ItemStack>> requirements = new ArrayList<>();
            for (int i = 0; i < cells.size(); i++) {
                if (cells.get(i) == SlotDisplay.Empty.INSTANCE) continue;
                Slot slot = inputs.get(i);
                Predicate<ItemStack> accepts = acceptsInput(cells.get(i), context);
                requirements.add(stack -> slot.mayPlace(stack) && accepts.test(stack));
            }
            ManualCraft selected = pickPattern(contents, cells, requirements, output.getCount());
            if (selected != null) return selected;
        }
        return null;
    }

    private Result tryCraftFromGameData(AbstractCraftingMenu menu, String itemId, int needed, IllegalStateException unavailable) {
        if (menu.getInputGridSlots().stream().anyMatch(Slot::hasItem)) throw unavailable;
        manualCraft = chooseGameRecipe(menu, itemId, needed);
        if (manualCraft == null) {
            throw new IllegalStateException("No available game recipe and ingredient combination produces " + itemId + " in this grid.", unavailable);
        }
        gameDataCrafting = true;
        orderedRecipe = false;
        recipeWait = 0;
        return craftFromGameData(menu, itemId);
    }

    private Result craftFromGameData(AbstractCraftingMenu menu, String itemId) {
        List<Slot> grid = menu.getInputGridSlots();
        List<Slot> own = InventoryJobs.playerSlots(menu, player);
        if (manualCraft.tookResult || manualCraft.rejection != null) return clearCraftingGrid(menu, grid, own);
        for (int i = 0; i < grid.size(); i++) {
            ItemStack expected = i < manualCraft.nextSlot ? manualCraft.pattern.get(i) : ItemStack.EMPTY;
            if (!ItemStack.matches(expected, grid.get(i).getItem())) {
                throw new IllegalStateException("The crafting grid changed before Gather could verify the recipe.");
            }
        }
        while (manualCraft.nextSlot < grid.size() && manualCraft.pattern.get(manualCraft.nextSlot).isEmpty()) manualCraft.nextSlot++;
        if (manualCraft.nextSlot < grid.size()) {
            ItemStack expected = manualCraft.pattern.get(manualCraft.nextSlot);
            List<Slot> matching = own.stream().filter(slot -> slot.mayPickup(player)
                && ItemStack.isSameItemSameComponents(slot.getItem(), expected)).toList();
            // Returning the unused part of a stack is a third click after the result revision changes.
            boolean returnsRemainder = !matching.isEmpty() && matching.getFirst().getItem().getCount() > 1;
            CraftAck placement = craftUpdate(returnsRemainder);
            if (transfer(menu, matching, List.of(grid.get(manualCraft.nextSlot)), InventoryJobs.itemId(expected), 1, false, false) != 1) {
                manualCraft.rejection = "The ingredients changed while filling the crafting grid.";
                return clearCraftingGrid(menu, grid, own);
            }
            awaitCraftUpdate(placement);
            manualCraft.nextSlot++;
            status = "Filling the crafting grid for " + itemId + ".";
            return Result.RUNNING;
        }

        Slot result = menu.getResultSlot();
        if (!result.hasItem()) {
            if (++recipeWait <= RECIPE_TIMEOUT) {
                status = "Waiting for the server to confirm " + itemId + ".";
                return Result.RUNNING;
            }
            manualCraft.rejection = "The server did not accept the crafting recipe for " + itemId + ".";
        } else if (!result.getItem().is(InventoryJobs.item(itemId)) || result.getItem().getCount() != manualCraft.outputCount) {
            manualCraft.rejection = "The server returned a different crafting result for " + itemId + ".";
        } else {
            int moved = takeCraftingResult(menu, result, own, itemId, manualCraft.outputCount);
            if (moved == manualCraft.outputCount) {
                done += moved;
                manualCraft.tookResult = true;
                status = "Crafted " + done + " / " + spec.limit() + " " + itemId + ".";
                return Result.RUNNING;
            }
            manualCraft.rejection = "No inventory slot fits the next full crafting result.";
        }
        return clearCraftingGrid(menu, grid, own);
    }

    private Result clearCraftingGrid(AbstractCraftingMenu menu, List<Slot> grid, List<Slot> own) {
        for (int i = 0; i < grid.size(); i++) {
            Slot slot = grid.get(i);
            if (!slot.hasItem()) continue;
            ItemStack expected = manualCraft.pattern.get(i);
            ItemStack current = slot.getItem();
            if (i >= manualCraft.nextSlot || expected.isEmpty() || !manualCraft.tookResult && !ItemStack.matches(expected, current)) {
                throw new IllegalStateException("The crafting grid changed while Gather was editing it.");
            }
            CraftAck clearing = craftUpdate(true);
            if (transfer(menu, List.of(slot), own, InventoryJobs.itemId(current), current.getCount(), false, true) == 0) {
                throw new IllegalStateException("No inventory space for the items still in the crafting grid.");
            }
            awaitCraftUpdate(clearing);
            return Result.RUNNING;
        }
        String rejection = manualCraft.rejection;
        manualCraft = null;
        if (rejection != null) throw new IllegalStateException(rejection);
        return done == spec.limit() ? Result.FINISHED : Result.RUNNING;
    }

    private int takeCraftingResult(AbstractCraftingMenu menu, Slot result, List<Slot> own, String item, int count) {
        CraftAck taking = craftUpdate(true);
        int moved = transfer(menu, List.of(result), own, item, count, true, true);
        if (gathering && moved > 0) awaitCraftUpdate(taking);
        return moved;
    }

    private CraftAck craftUpdate(boolean requireContents) {
        return new CraftAck(sync.craftResult, sync.contents, requireContents);
    }

    private void awaitCraftUpdate(CraftAck ack) {
        craftAck = ack;
        delay = 0;
    }

    private ManualCraft chooseGameRecipe(AbstractCraftingMenu menu, String itemId, int needed) {
        Item item = InventoryJobs.item(itemId);
        ContextMap context = SlotDisplayContext.fromLevel(level);
        var contents = craftingContents(menu);
        for (RecipeDisplay recipe : GatherPlan.gameRecipeDisplays(level, item)) {
            if (!recipe.isEnabled(level.enabledFeatures())) continue;
            ItemStack output = recipe.result().resolveForFirstStack(context);
            if (output.isEmpty() || !output.is(item) || needed % output.getCount() != 0) continue;
            List<SlotDisplay> cells = recipeCells(recipe, menu.getGridWidth(), menu.getGridHeight());
            if (cells.isEmpty() || cells.size() != menu.getInputGridSlots().size()) continue;
            List<StackedContents.IngredientInfo<ItemStack>> requirements = cells.stream()
                .filter(cell -> cell != SlotDisplay.Empty.INSTANCE).<StackedContents.IngredientInfo<ItemStack>>map(cell -> acceptsInput(cell, context)::test).toList();
            ManualCraft selected = pickPattern(contents, cells, requirements, output.getCount());
            if (selected != null) return selected;
        }
        return null;
    }

    private StackedContents<ItemStack> craftingContents(AbstractContainerMenu menu) {
        var contents = new StackedContents<ItemStack>();
        for (Slot slot : InventoryJobs.playerSlots(menu, player)) {
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty() && slot.mayPickup(player) && Inventory.isUsableForCrafting(stack)) contents.account(stack, stack.getCount());
        }
        return contents;
    }

    private static ManualCraft pickPattern(StackedContents<ItemStack> contents, List<SlotDisplay> cells,
                                           List<StackedContents.IngredientInfo<ItemStack>> requirements, int outputCount) {
        if (requirements.isEmpty()) return null;
        List<ItemStack> picked = new ArrayList<>();
        if (!contents.tryPick(requirements, 1, stack -> picked.add(stack.copyWithCount(1)))) return null;
        var selected = picked.iterator();
        List<ItemStack> pattern = cells.stream().map(cell -> cell == SlotDisplay.Empty.INSTANCE ? ItemStack.EMPTY : selected.next()).toList();
        return new ManualCraft(pattern, outputCount);
    }

    private static List<SlotDisplay> recipeCells(RecipeDisplay recipe, int width, int height) {
        List<SlotDisplay> grid = new ArrayList<>(Collections.nCopies(width * height, SlotDisplay.Empty.INSTANCE));
        if (recipe instanceof ShapedCraftingRecipeDisplay shaped) {
            if (shaped.width() > width || shaped.height() > height || shaped.ingredients().size() != shaped.width() * shaped.height()) {
                return List.of();
            }
            for (int row = 0; row < shaped.height(); row++) {
                for (int col = 0; col < shaped.width(); col++) grid.set(row * width + col, shaped.ingredients().get(row * shaped.width() + col));
            }
        } else if (recipe instanceof ShapelessCraftingRecipeDisplay shapeless && shapeless.ingredients().size() <= grid.size()) {
            for (int i = 0; i < shapeless.ingredients().size(); i++) grid.set(i, shapeless.ingredients().get(i));
        } else return List.of();
        return grid;
    }

    private static Predicate<ItemStack> acceptsInput(SlotDisplay display, ContextMap context) {
        if (display instanceof SlotDisplay.WithRemainder remainder) return acceptsInput(remainder.input(), context);
        if (display instanceof SlotDisplay.Composite composite) {
            List<Predicate<ItemStack>> choices = composite.contents().stream().map(entry -> acceptsInput(entry, context)).toList();
            return stack -> choices.stream().anyMatch(choice -> choice.test(stack));
        }
        List<ItemStack> choices = display.resolveForStacks(context);
        if (display instanceof SlotDisplay.ItemStackSlotDisplay) {
            return stack -> choices.stream().anyMatch(choice -> ItemStack.isSameItemSameComponents(stack, choice));
        }
        var items = choices.stream().filter(stack -> !stack.isEmpty()).map(ItemStack::getItem).distinct().toList();
        return stack -> items.contains(stack.getItem());
    }

    private RecipeDisplayEntry chooseRecipe(AbstractCraftingMenu menu, String itemId, int needed) {
        StackedItemContents contents = new StackedItemContents();
        player.getInventory().fillStackedContents(contents);
        menu.fillCraftSlotsStackedContents(contents);
        return chooseRecipe(itemId, needed, menu.getGridWidth(), menu.getGridHeight(),
            (requirements, crafts) -> contents.canCraft(requirements, null));
    }

    boolean verifyCraftSupplies() {
        StackedContents<Holder<Item>> contents = new StackedContents<>();
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (!stack.isEmpty() && Inventory.isUsableForCrafting(stack)) contents.account(stack.typeHolder(), stack.getCount());
        }
        int size = inventoryCrafting() ? 2 : 3;
        String item = spec.ids().getFirst();
        try {
            chooseRecipe(item, spec.limit(), size, size, (requirements, crafts) -> contents.tryPick(requirements, 1, null));
        } catch (IllegalStateException unavailable) {
            boolean hasInstalledRecipe = GatherPlan.gameRecipeDisplays(level, InventoryJobs.item(item)).stream().anyMatch(recipe ->
                recipe instanceof ShapedCraftingRecipeDisplay shaped && shaped.width() <= size && shaped.height() <= size
                || recipe instanceof ShapelessCraftingRecipeDisplay shapeless && shapeless.ingredients().size() <= size * size);
            if (hasInstalledRecipe) return false;
            throw unavailable;
        }
        try {
            chooseRecipe(item, spec.limit(), size, size, (requirements, crafts) -> contents.tryPick(requirements, crafts, null));
            return true;
        } catch (IllegalStateException partialSupply) {
            // Different recipes may collectively cover the order even when none can fill it on its own.
            return false;
        }
    }

    private RecipeDisplayEntry chooseRecipe(String itemId, int needed, int width, int height,
                                            BiPredicate<List<Ingredient>, Integer> hasIngredients) {
        ContextMap context = SlotDisplayContext.fromLevel(level);
        RecipeDisplayEntry missing = null;
        boolean outputFound = false;
        for (RecipeDisplayEntry recipe : recipes()) {
            if (!(recipe.display() instanceof ShapedCraftingRecipeDisplay)
                && !(recipe.display() instanceof ShapelessCraftingRecipeDisplay)) continue;
            ItemStack result = recipe.display().result().resolveForFirstStack(context);
            if (result.isEmpty() || !InventoryJobs.itemId(result).equals(itemId)) continue;
            outputFound = true;
            if (needed % result.getCount() != 0) continue;
            if (recipe.display() instanceof ShapedCraftingRecipeDisplay shaped
                && (shaped.width() > width || shaped.height() > height)) continue;
            if (recipe.display() instanceof ShapelessCraftingRecipeDisplay shapeless
                && shapeless.ingredients().size() > width * height) continue;
            if (recipe.craftingRequirements().isEmpty()) continue;
            if (hasIngredients.test(recipe.craftingRequirements().orElseThrow(), needed / result.getCount())) return recipe;
            if (missing == null) missing = recipe;
        }
        if (missing != null) {
            String names = missing.craftingRequirements().orElseThrow().stream()
                .map(ingredient -> ingredient.items().limit(4).map(holder -> {
                    String id = BuiltInRegistries.ITEM.getKey(holder.value()).toString();
                    return id + " (have " + InventoryJobs.count(player, id) + ")";
                }).reduce((a, b) -> a + " or " + b).orElse("unavailable ingredient"))
                .reduce((a, b) -> a + ", " + b).orElse(itemId);
            throw new IllegalStateException("Missing ingredients. One craft requires " + names + ".");
        }
        throw new IllegalStateException(outputFound ? "Use a larger crafting grid or a quantity matching full recipe outputs."
            : "No unlocked recipe-book entry produces " + itemId + ".");
    }

    private Result furnace(AbstractContainerMenu rawMenu) {
        if (!(rawMenu instanceof AbstractFurnaceMenu menu)) {
            throw new IllegalStateException("The selected block did not open a furnace menu.");
        }
        String input = stockedInput == null ? spec.ids().get(0) : stockedInput;
        String fuel = spec.ids().get(1), output = spec.ids().get(2);
        Slot ingredient = menu.getSlot(AbstractFurnaceMenu.INGREDIENT_SLOT);
        Slot fuelSlot = menu.getSlot(AbstractFurnaceMenu.FUEL_SLOT);
        Slot result = menu.getResultSlot();
        if (detached != null && !resumeFurnace(ingredient.getItem(), result.getItem(), output)) return Result.RUNNING;
        if (gathering && InventoryJobs.item(fuel) == Items.AIR) return useStockedFurnace(menu, ingredient, fuelSlot, result, input, output);
        if (!verified) {
            if (ingredient.hasItem() || result.hasItem()) throw new IllegalStateException("Empty the furnace input and output first.");
            if (!gathering && fuelSlot.hasItem() && !InventoryJobs.itemId(fuelSlot.getItem()).equals(fuel)) {
                throw new IllegalStateException("The furnace contains a different fuel.");
            }
            ItemStack fuelSample = fuelSlot.hasItem() ? fuelSlot.getItem() : player.getInventory().getNonEquipmentItems().stream()
                .filter(stack -> !stack.isEmpty() && InventoryJobs.itemId(stack).equals(fuel)).findFirst()
                .orElseGet(() -> new ItemStack(InventoryJobs.item(fuel)));
            if (!fuelSample.has(DataComponents.COOKING_FUEL) || !fuelSlot.mayPlace(fuelSample)
                || !menu.isLit() && !fuelSlot.hasItem() && InventoryJobs.count(player, fuel) <= (fuel.equals(input) ? spec.limit() : 0)) {
                throw new IllegalStateException("Missing usable furnace fuel " + fuel + ".");
            }
            if (!gathering) verifyFurnaceRecipe(input, output);
            if (InventoryJobs.count(player, input) < spec.limit()) {
                throw new IllegalStateException("Missing furnace input " + input + ".");
            }
            verified = true;
        }
        if (gathering && !furnaceOutputReady(ingredient.getItem(), result.getItem(), output)) return Result.RUNNING;
        if (inputLoaded == spec.limit() && !ingredient.hasItem() && !result.hasItem()) {
            if (done == 0) throw new IllegalStateException("The furnace input disappeared without a matching output.");
            return Result.FINISHED;
        }
        if (ingredient.hasItem() && !InventoryJobs.itemId(ingredient.getItem()).equals(input)) {
            throw new IllegalStateException("The furnace input changed during the job.");
        }
        observeFurnace(menu, ingredient);
        if (++elapsed > 1_200) throw new IllegalStateException("The furnace stopped making progress. Check fuel, input and output.");
        List<Slot> own = InventoryJobs.playerSlots(menu, player);
        if (result.hasItem()) {
            if (!InventoryJobs.itemId(result.getItem()).equals(output)) throw new IllegalStateException("The furnace produced a different output.");
            if (!gathering || inputLoaded == spec.limit() && !ingredient.hasItem() || shouldFlushFurnace(result)) {
                return collectFurnaceOutput(menu, result, own, output);
            }
        }
        if (inputLoaded < spec.limit()) {
            int moved = transfer(menu, own, List.of(ingredient), input, spec.limit() - inputLoaded, false, false);
            if (moved > 0) {
                inputLoaded += moved;
                return furnaceProgress();
            }
            if (!ingredient.hasItem()) throw new IllegalStateException("No valid furnace input could be loaded.");
        }
        if (fuelStartWait > 0) {
            if (menu.isLit()) fuelStartWait = 0;
            else {
                if (++fuelStartWait > 2 * networkDelay() + 12) throw new IllegalStateException("The server did not start the furnace with the supplied fuel.");
                status = "Waiting for the server to start the furnace.";
                return Result.RUNNING;
            }
        }
        if (ingredient.hasItem() && (gathering || fuel.equals(InventoryJobs.itemId(Items.LAVA_BUCKET))) && fuelSlot.getItem().is(Items.BUCKET)) {
            int moved = transfer(menu, List.of(fuelSlot), own, InventoryJobs.itemId(Items.BUCKET), 1, true, true);
            if (moved == 0) throw new IllegalStateException("No inventory space for the empty furnace fuel bucket.");
            return furnaceProgress();
        }
        if (!menu.isLit() && ingredient.hasItem() && !fuelSlot.hasItem()) {
            int moved = transfer(menu, own, List.of(fuelSlot), fuel, 1, false, false);
            if (moved > 0) {
                if (gathering) fuelStartWait = 1;
                return furnaceProgress();
            }
            if (result.hasItem()) return collectFurnaceOutput(menu, result, own, output);
            throw new IllegalStateException("Missing usable furnace fuel " + fuel + ".");
        }
        return gathering ? prepareBackgroundCooking(menu, ingredient, fuelSlot, result, input, fuel, output) : Result.RUNNING;
    }

    private Result useStockedFurnace(AbstractFurnaceMenu menu, Slot ingredient, Slot fuel, Slot result, String input, String output) {
        List<Slot> own = InventoryJobs.playerSlots(menu, player);
        if (result.hasItem() && !InventoryJobs.itemId(result.getItem()).equals(output)) {
            throw new IllegalStateException("The furnace has a different output.");
        }
        if (!verified && result.hasItem()) takeExistingFurnaceOutput = true;
        if (takeExistingFurnaceOutput) {
            verified = true;
            return done == 0 ? collectFurnaceOutput(menu, result, own, output) : Result.FINISHED;
        }
        if (ingredient.hasItem() && !InventoryJobs.itemId(ingredient.getItem()).equals(input)) {
            String actual = InventoryJobs.itemId(ingredient.getItem());
            CookingRecipe planned = verified ? null : lookupCookingRecipe(input, output);
            CookingRecipe observed = planned == null ? null : lookupCookingRecipe(actual, output);
            if (observed == null || !ItemStack.matches(planned.result(), observed.result())) {
                throw new IllegalStateException("The furnace is cooking a different ingredient.");
            }
            stockedInput = actual;
            input = actual;
            cookingRecipe = observed;
            checkedCookingRecipe = true;
        }
        if (!verified) {
            initialFurnaceInput = ingredient.getItem().getCount();
            inputLoaded = Math.min(initialFurnaceInput, spec.limit());
            if (InventoryJobs.count(player, input) < spec.limit() - inputLoaded) {
                throw new IllegalStateException("Missing furnace input " + input + ".");
            }
            verified = true;
        }
        if (!furnaceOutputReady(ingredient.getItem(), result.getItem(), output)) return Result.RUNNING;
        observeFurnace(menu, ingredient);
        boolean complete = inputLoaded == spec.limit() && ingredient.getItem().getCount() <= Math.max(0, initialFurnaceInput - spec.limit());
        if (result.hasItem() && (complete || shouldFlushFurnace(result))) return collectFurnaceOutput(menu, result, own, output);
        if (complete) {
            if (done == 0) throw new IllegalStateException("The furnace input disappeared without a matching output.");
            return Result.FINISHED;
        }
        ItemStack fuelStack = fuel.getItem();
        if (!menu.isLit() && (fuelStack.isEmpty() || !fuelStack.has(DataComponents.COOKING_FUEL) || !fuel.mayPlace(fuelStack))) {
            if (result.hasItem()) return collectFurnaceOutput(menu, result, own, output);
            int staged = Math.min(ingredient.getItem().getCount(), inputLoaded - Math.min(initialFurnaceInput, spec.limit()));
            if (staged > 0) {
                int moved = transfer(menu, List.of(ingredient), own, input, staged, false, true);
                if (moved > 0) {
                    inputLoaded -= moved;
                    return furnaceProgress();
                }
            }
            throw new IllegalStateException("This furnace has no usable fuel left.");
        }
        if (inputLoaded < spec.limit()) {
            int moved = transfer(menu, own, List.of(ingredient), input, spec.limit() - inputLoaded, false, false);
            if (moved > 0) {
                inputLoaded += moved;
                return furnaceProgress();
            }
            if (!ingredient.hasItem()) throw new IllegalStateException("Couldn't load the remaining furnace input " + input + ".");
        }
        if (++elapsed > 1_200) throw new IllegalStateException("The furnace stopped making progress with its stored fuel.");
        status = "Cooking " + output + " with stored fuel. " + ingredient.getItem().getCount() + " input remaining.";
        return prepareBackgroundCooking(menu, ingredient, fuel, result, input, null, output);
    }

    private Result prepareBackgroundCooking(AbstractFurnaceMenu menu, Slot ingredient, Slot fuel, Slot result,
                                            String inputId, String fuelId, String outputId) {
        if (detachedOnce || inputLoaded != spec.limit() || initialFurnaceInput > spec.limit() || !menu.isLit()
            || !(level.getBlockEntity(spec.target()) instanceof AbstractFurnaceBlockEntity furnace)) return Result.RUNNING;
        var data = ((FurnaceDataAccessor) menu).soprano$furnaceData();
        int duration = data.get(AbstractFurnaceBlockEntity.DATA_COOKING_TOTAL_TIME);
        int progress = data.get(AbstractFurnaceBlockEntity.DATA_COOKING_PROGRESS);
        // The server decrements the burn counter before deciding whether the active fuel can cook this tick.
        int litTicks = data.get(AbstractFurnaceBlockEntity.DATA_LIT_TIME) - 1;
        ItemStack input = ingredient.getItem(), output = result.getItem();
        int left = input.getCount(), cooked = spec.limit() - left;
        if (left < 1 || cooked < 0 || progress < 1 || progress >= duration || litTicks < 1) return Result.RUNNING;

        CookingRecipe recipe = cookingRecipe(inputId, outputId);
        int yield = furnaceYield > 0 ? furnaceYield : recipe == null ? 0 : recipe.result().getCount();
        if (yield < 1 && (spec.limit() != 1 || !output.isEmpty())) return Result.RUNNING;
        ItemStack sample = !output.isEmpty() ? output : recipe == null ? new ItemStack(InventoryJobs.item(outputId)) : recipe.result();
        long uncollected = (long) left * Math.max(1, yield) + output.getCount();
        if (uncollected > result.getMaxStackSize(sample)) return Result.RUNNING;

        long remaining = (long) duration * left - progress;
        if (remaining > litTicks) {
            ItemStack stocked = fuel.getItem();
            ItemStack nextFuel = stocked.isEmpty() ? carriedFuel(fuelId) : stocked;
            FutureFuel future = futureFuel(nextFuel, recipe);
            if (future == null || !fuel.mayPlace(nextFuel)) return Result.RUNNING;
            long activeProgress = (long) progress + litTicks;
            long leftover = activeProgress % duration;
            // The native furnace rounds carried progress up when switching speed. Rounding down here is conservative.
            long futureTicks = (left - activeProgress / duration) * future.cookDuration()
                - leftover * future.cookDuration() / duration;
            long refill = Math.ceilDiv(futureTicks, future.burnTicks()) - stocked.getCount();
            if (refill > 0) {
                if (refillFuel(menu, fuel, nextFuel, fuelId, refill)) return furnaceProgress();
                return Result.RUNNING;
            }
            remaining = litTicks + futureTicks;
        }
        readyToDetach = new CookWait(furnace.getBlockState().getBlock(), input.copy(), output.copy(), yield,
            (int) Math.min(Integer.MAX_VALUE, remaining + 2), (int) uncollected);
        status = "Cooking " + outputId + ". " + left + " input remaining.";
        return Result.COOKING;
    }

    private boolean refillFuel(AbstractFurnaceMenu menu, Slot fuel, ItemStack sample, String fuelId, long count) {
        if (fuelId == null || !sample.is(InventoryJobs.item(fuelId)) || count > fuel.getMaxStackSize(sample) - fuel.getItem().getCount()) {
            return false;
        }
        var supplies = InventoryJobs.playerSlots(menu, player).stream()
            .filter(slot -> ItemStack.isSameItemSameComponents(slot.getItem(), sample)).toList();
        long available = supplies.stream().mapToLong(slot -> slot.getItem().getCount()).sum();
        return available >= count && transfer(menu, supplies, List.of(fuel), fuelId, (int) count, false, false) > 0;
    }

    private ItemStack carriedFuel(String fuelId) {
        if (fuelId == null) return ItemStack.EMPTY;
        Item fuel = InventoryJobs.item(fuelId);
        return player.getInventory().getNonEquipmentItems().stream().filter(stack -> stack.is(fuel)).findFirst().orElse(ItemStack.EMPTY);
    }

    private FutureFuel futureFuel(ItemStack stack, CookingRecipe recipe) {
        if (recipe == null) return null;
        var estimate = GatherPlan.cookingFuel(stack);
        if (estimate == null) return null;
        var component = stack.get(DataComponents.COOKING_FUEL);
        var furnace = level.getBlockState(spec.target());
        boolean quickFurnace = furnace.is(Blocks.SMOKER) || furnace.is(Blocks.BLAST_FURNACE);
        float speed;
        if (component.speedMultiplier() instanceof ResolvableFloat.Constant constant) speed = constant.value();
        else if (component.speedMultiplier() instanceof ResolvableFloat.Reference reference) {
            speed = switch (reference.key().identifier().toString()) {
                case "minecraft:cooking/speed_default" -> quickFurnace ? 2 : 1;
                case "minecraft:cooking/fast_speed_multiplier" -> 2;
                case "minecraft:cooking/normal_speed_multiplier" -> 1;
                default -> 0;
            };
        } else return null;
        if (!(speed > 0) || !Float.isFinite(speed)) return null;
        int burn = estimate.burnTicks();
        if (quickFurnace && component.burnTime() instanceof ResolvableInt.Reference) burn /= 2;
        int duration = (int) Math.min(Integer.MAX_VALUE, Math.ceil(recipe.duration() / (double) speed));
        return burn > 0 && duration > 0 ? new FutureFuel(burn, duration) : null;
    }

    private CookingRecipe cookingRecipe(String input, String output) {
        if (checkedCookingRecipe) return cookingRecipe;
        checkedCookingRecipe = true;
        cookingRecipe = lookupCookingRecipe(input, output);
        return cookingRecipe;
    }

    private CookingRecipe lookupCookingRecipe(String input, String output) {
        CookingRecipe synced = findCookingRecipe(recipes().stream().map(RecipeDisplayEntry::display).toList(), input, output);
        return synced != null ? synced : findCookingRecipe(GatherPlan.gameRecipeDisplays(level, InventoryJobs.item(output)), input, output);
    }

    private CookingRecipe findCookingRecipe(List<RecipeDisplay> displays, String input, String output) {
        ContextMap context = SlotDisplayContext.fromLevel(level);
        Item station = level.getBlockState(spec.target()).getBlock().asItem();
        CookingRecipe match = null;
        for (RecipeDisplay display : displays) {
            if (!(display instanceof FurnaceRecipeDisplay recipe) || recipe.duration() <= 0) continue;
            ItemStack result = recipe.result().resolveForFirstStack(context);
            if (result.isEmpty() || !InventoryJobs.itemId(result).equals(output)
                || recipe.craftingStation().resolveForStacks(context).stream().noneMatch(stack -> stack.is(station))
                || recipe.ingredient().resolveForStacks(context).stream().noneMatch(stack -> InventoryJobs.itemId(stack).equals(input))) continue;
            if (match != null && (recipe.duration() != match.duration() || !ItemStack.matches(result, match.result()))) return null;
            match = new CookingRecipe(recipe.duration(), result.copy());
        }
        return match;
    }

    private boolean resumeFurnace(ItemStack input, ItemStack output, String outputId) {
        int cooked = detached.input().getCount() - input.getCount();
        int produced = output.getCount() - detached.output().getCount();
        int yield = detached.yield();
        if (cooked < 0 || !input.isEmpty() && !ItemStack.isSameItemSameComponents(detached.input(), input)
            || !output.isEmpty() && (!InventoryJobs.itemId(output).equals(outputId) || !detached.output().isEmpty()
                && !ItemStack.isSameItemSameComponents(detached.output(), output))) {
            throw new IllegalStateException("Furnace contents changed while Gather was away.");
        }
        boolean outputMatches = yield > 0 ? produced == (long) cooked * yield : cooked == 0 ? produced == 0 : produced >= cooked;
        if (!outputMatches || cooked > 0 && sync.furnaceInputsAhead()) {
            return awaitFurnaceSlots("Furnace contents changed while Gather was away.");
        }
        furnaceSyncWait = 0;
        detached = null;
        return true;
    }

    private boolean furnaceOutputReady(ItemStack input, ItemStack output, String outputId) {
        if (!output.isEmpty() && !output.is(InventoryJobs.item(outputId))) {
            throw new IllegalStateException("The furnace produced a different output.");
        }
        int cooked = Math.max(initialFurnaceInput, inputLoaded) - input.getCount();
        if (cooked < 0) throw new IllegalStateException("The furnace input was increased during the job.");
        int produced = done + output.getCount();
        int yield = coherentYield(cooked, produced, furnaceYield);
        if (yield < 0 || cooked > verifiedCooked && sync.furnaceInputsAhead()) {
            return awaitFurnaceSlots("The furnace input and output no longer match this batch.");
        }
        furnaceYield = yield;
        verifiedCooked = cooked;
        furnaceSyncWait = 0;
        return true;
    }

    static int coherentYield(int cooked, int produced, int knownYield) {
        if (cooked == 0) return produced == 0 ? knownYield : -1;
        if (knownYield > 0) return produced == (long) cooked * knownYield ? knownYield : -1;
        return produced >= cooked && produced % cooked == 0 ? produced / cooked : -1;
    }

    private boolean awaitFurnaceSlots(String failure) {
        if (++furnaceSyncWait > Math.max(10, networkDelay() * 2)) throw new IllegalStateException(failure);
        status = "Waiting for the furnace input and output to synchronize.";
        return false;
    }

    private void observeFurnace(AbstractFurnaceMenu menu, Slot ingredient) {
        float progress = menu.getBurnProgress();
        int count = ingredient.getItem().getCount();
        if (count != previousFurnaceInput || progress > bestCookProgress) elapsed = 0;
        previousFurnaceInput = count;
        bestCookProgress = progress;
    }

    private boolean shouldFlushFurnace(Slot result) {
        return elapsed >= FURNACE_OUTPUT_FLUSH_DELAY || result.getItem().getCount() >= result.getMaxStackSize(result.getItem());
    }

    private Result collectFurnaceOutput(AbstractFurnaceMenu menu, Slot result, List<Slot> own, String output) {
        int moved = transfer(menu, List.of(result), own, output, result.getItem().getCount(), true, true);
        if (moved == 0) throw new IllegalStateException("No inventory slot fits the furnace output stack.");
        done += moved;
        elapsed = 0;
        return furnaceProgress();
    }

    private Result furnaceProgress() {
        status = "Furnace loaded " + inputLoaded + " / " + spec.limit() + ", collected " + done + ".";
        return Result.RUNNING;
    }

    private int transfer(AbstractContainerMenu menu, List<Slot> from, List<Slot> to, String item, int limit,
                         boolean wholeStackOnly, boolean intoPlayer) {
        int before = InventoryJobs.count(player, item);
        if (intoPlayer && gathering) to = GatherInventory.hotbarFirst(to);
        int moved = InventoryJobs.move(mode, player, menu, from, to, item, limit, wholeStackOnly);
        if (moved > 0) {
            pending = new Pending(item, before + (intoPlayer ? moved : -moved));
            delay = networkDelay();
        }
        return moved;
    }

    private int networkDelay() {
        var info = player.connection.getPlayerInfo(player.getUUID());
        return info == null ? CLICK_DELAY : Math.clamp(Math.ceilDiv(info.getLatency(), 50) + CLICK_DELAY, CLICK_DELAY, 80);
    }

    void verifyFurnaceRecipe(String input, String output) {
        ContextMap context = SlotDisplayContext.fromLevel(level);
        BlockPos target = spec.target();
        var station = level.getBlockState(target).getBlock().asItem();
        for (RecipeDisplayEntry entry : recipes()) {
            if (!(entry.display() instanceof FurnaceRecipeDisplay recipe)) continue;
            if (recipe.craftingStation().resolveForStacks(context).stream().noneMatch(stack -> stack.is(station))) continue;
            if (recipe.ingredient().resolveForStacks(context).stream().noneMatch(stack -> InventoryJobs.itemId(stack).equals(input))) continue;
            if (recipe.result().resolveForStacks(context).stream().anyMatch(stack -> InventoryJobs.itemId(stack).equals(output))) return;
        }
        throw new IllegalStateException("No unlocked recipe confirms that this furnace turns " + input + " into " + output + ".");
    }

    private List<RecipeDisplayEntry> recipes() {
        return player.getRecipeBook().getCollections().stream().flatMap(collection -> collection.getRecipes().stream()).toList();
    }
}
