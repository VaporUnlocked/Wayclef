package dev.soprano.task;

import dev.soprano.inventory.InventoryJobs;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.BrewingRecipe;

import java.util.List;
import java.util.function.Predicate;

/** Drives the ordinary brewing menu and uses the server's bottle contents and progress as the result. */
final class BrewJob {
    private static final int INGREDIENT_SLOT = 3;
    private static final int FUEL_SLOT = 4;
    private static final int SYNC_TIMEOUT = 200;

    private enum Phase { PLAN, LOAD, START, BREWING, COLLECT }
    private record Transfer(ItemStack item, int count, int deadline) { }

    private final LocalPlayer player;
    private final MultiPlayerGameMode mode;
    private final int requested;
    private final Predicate<ItemStack> target;
    private final List<BrewingRecipe> recipes;
    private Phase phase = Phase.PLAN;
    private BrewPlan.Batch batch;
    private Transfer transfer;
    private int tick, brewTick, previousProgress, loaded, stage, collected, done;
    private String status = "Preparing the brewing stand.";

    BrewJob(JobSpec job, ClientLevel level, LocalPlayer player, MultiPlayerGameMode mode) {
        this.player = player;
        this.mode = mode;
        this.requested = job.limit();
        target = BrewPlan.target(level, job.ids());
        recipes = BrewPlan.recipes(level).stream().filter(recipe -> recipe.getOutput().create().getItem()
            .isEnabled(level.enabledFeatures())).toList();
        if (recipes.isEmpty()) throw new IllegalStateException("No brewing recipes are available to this client.");
    }

    String status() { return status; }

    StationJob.Result tick(BrewingStandMenu menu, int delay) {
        tick++;
        var own = InventoryJobs.playerSlots(menu, player);
        if (transfer != null) {
            if (tick < transfer.deadline()) return running("Waiting for the brewing stand's inventory update.");
            if (count(own, transfer.item()) != transfer.count()) {
                throw new IllegalStateException("The inventory changed during a brewing transfer.");
            }
            transfer = null;
        }
        return switch (phase) {
            case PLAN -> plan(menu, own);
            case LOAD -> load(menu, own, delay);
            case START -> start(menu, own, delay);
            case BREWING -> brew(menu, own, delay);
            case COLLECT -> collect(menu, own, delay);
        };
    }

    private StationJob.Result plan(BrewingStandMenu menu, List<Slot> own) {
        if (done == requested) {
            status = "Brewed and collected " + done + " items.";
            return StationJob.Result.FINISHED;
        }
        if (menu.getBrewingTicks() != 0 || menu.getSlot(INGREDIENT_SLOT).hasItem()) {
            throw new IllegalStateException("Empty the stand's ingredient and bottle slots before brewing.");
        }
        for (int i = 0; i < BrewPlan.BOTTLES; i++) {
            if (menu.getSlot(i).hasItem()) throw new IllegalStateException("Empty the stand's bottle slots before brewing.");
        }
        ItemStack reserve = needsFuel(menu) ? fuel(own) : ItemStack.EMPTY;
        if (needsFuel(menu) && reserve.isEmpty()) {
            throw new IllegalStateException("The stand needs a usable brewing fuel such as blaze powder.");
        }
        batch = BrewPlan.choose(recipes, own.stream().map(Slot::getItem).toList(), reserve, requested - done,
            target, menu.getSlot(0)::mayPlace, menu.getSlot(INGREDIENT_SLOT)::mayPlace);
        if (batch == null) throw new IllegalStateException("No known brewing sequence can make the requested output with the carried supplies.");
        loaded = stage = collected = 0;
        phase = Phase.LOAD;
        return running("Preparing " + batch.count() + " brewing slots.");
    }

    private StationJob.Result load(BrewingStandMenu menu, List<Slot> own, int delay) {
        requireBottles(menu, loaded, batch.input());
        if (menu.getSlot(INGREDIENT_SLOT).hasItem()) throw new IllegalStateException("Someone added a brewing ingredient.");
        if (loaded == batch.count()) {
            phase = Phase.START;
            return start(menu, own, delay);
        }
        move(menu, exact(own, batch.input()), List.of(menu.getSlot(loaded)), own, batch.input(), 1, false, delay);
        loaded++;
        return running("Loaded " + loaded + " of " + batch.count() + " brewing slots.");
    }

    private StationJob.Result start(BrewingStandMenu menu, List<Slot> own, int delay) {
        ItemStack input = stage == 0 ? batch.input() : batch.steps().get(stage - 1).result();
        requireBottles(menu, batch.count(), input);
        if (menu.getBrewingTicks() != 0 || menu.getSlot(INGREDIENT_SLOT).hasItem()) {
            throw new IllegalStateException("The brewing stand changed before the next ingredient.");
        }
        if (needsFuel(menu)) {
            ItemStack fuel = fuel(own);
            if (fuel.isEmpty()) throw new IllegalStateException("The stand ran out of brewing fuel.");
            move(menu, exact(own, fuel), List.of(menu.getSlot(FUEL_SLOT)), own, fuel, 1, false, delay);
            return running("Loading brewing fuel.");
        }
        ItemStack reagent = batch.steps().get(stage).reagent();
        move(menu, exact(own, reagent), List.of(menu.getSlot(INGREDIENT_SLOT)), own, reagent, 1, false, delay);
        brewTick = tick;
        previousProgress = Integer.MAX_VALUE;
        phase = Phase.BREWING;
        return running("Started brewing step " + (stage + 1) + " of " + batch.steps().size() + ".");
    }

    private StationJob.Result brew(BrewingStandMenu menu, List<Slot> own, int delay) {
        var step = batch.steps().get(stage);
        int progress = menu.getBrewingTicks();
        if (progress == 0 && matchesBottles(menu, batch.count(), step.result())) {
            Slot ingredient = menu.getSlot(INGREDIENT_SLOT);
            if (ingredient.hasItem()) {
                var template = step.reagent().getItem().getCraftingRemainder();
                if (template == null || !ItemStack.matches(template.create(), ingredient.getItem())) {
                    if (tick - brewTick < SYNC_TIMEOUT) return running("Waiting for the consumed brewing ingredient to update.");
                    throw new IllegalStateException("An unexpected item remained in the ingredient slot.");
                }
                ItemStack remainder = ingredient.getItem().copy();
                move(menu, List.of(ingredient), GatherInventory.hotbarFirst(own), own,
                    remainder, remainder.getCount(), true, delay);
            }
            stage++;
            phase = stage == batch.steps().size() ? Phase.COLLECT : Phase.START;
            return running("Confirmed brewing step " + stage + " of " + batch.steps().size() + ".");
        }
        if (progress > 0 && progress < previousProgress) {
            brewTick = tick;
            previousProgress = progress;
        }
        if (tick - brewTick >= SYNC_TIMEOUT) {
            throw new IllegalStateException("The stand did not produce the expected brewing result. Check the stand contents.");
        }
        return running("Brewing step " + (stage + 1) + " of " + batch.steps().size()
            + (progress > 0 ? " (" + Math.ceilDiv(progress, 20) + " seconds)." : "."));
    }

    private StationJob.Result collect(BrewingStandMenu menu, List<Slot> own, int delay) {
        ItemStack output = batch.steps().getLast().result();
        for (int i = 0; i < BrewPlan.BOTTLES; i++) {
            ItemStack expected = i >= collected && i < batch.count() ? output : ItemStack.EMPTY;
            if (!ItemStack.matches(expected, menu.getSlot(i).getItem())) {
                throw new IllegalStateException("The cooked bottles changed before collection.");
            }
        }
        if (collected == batch.count()) {
            phase = Phase.PLAN;
            return plan(menu, own);
        }
        move(menu, List.of(menu.getSlot(collected)), GatherInventory.hotbarFirst(own), own,
            output, output.getCount(), true, delay);
        collected++;
        done += output.getCount();
        return running("Collected " + done + " of " + requested + " brewed items.");
    }

    private void requireBottles(BrewingStandMenu menu, int count, ItemStack expected) {
        if (!matchesBottles(menu, count, expected)) throw new IllegalStateException("The stand's bottle contents changed.");
    }

    private boolean matchesBottles(BrewingStandMenu menu, int count, ItemStack expected) {
        for (int i = 0; i < BrewPlan.BOTTLES; i++) {
            if (!ItemStack.matches(i < count ? expected : ItemStack.EMPTY, menu.getSlot(i).getItem())) return false;
        }
        return true;
    }

    private static boolean needsFuel(BrewingStandMenu menu) {
        return menu.getFuel() == 0 && !menu.getSlot(FUEL_SLOT).getItem().has(DataComponents.BREWING_FUEL);
    }

    private static ItemStack fuel(List<Slot> own) {
        return own.stream().map(Slot::getItem).filter(stack -> stack.has(DataComponents.BREWING_FUEL))
            .findFirst().orElse(ItemStack.EMPTY);
    }

    private void move(BrewingStandMenu menu, List<Slot> from, List<Slot> to, List<Slot> own,
                      ItemStack stack, int amount, boolean collect, int delay) {
        int available = count(own, stack);
        int moved = InventoryJobs.move(mode, player, menu, from, to, InventoryJobs.itemId(stack), amount, collect);
        if (moved != amount) throw new IllegalStateException(collect ? "No inventory slot can hold the brewed result or container."
            : "Cannot load " + stack.getHoverName().getString() + " into the brewing stand.");
        transfer = new Transfer(stack.copyWithCount(1), available + (collect ? amount : -amount), tick + delay);
    }

    private static List<Slot> exact(List<Slot> slots, ItemStack stack) {
        return slots.stream().filter(slot -> ItemStack.isSameItemSameComponents(slot.getItem(), stack)).toList();
    }

    private static int count(List<Slot> slots, ItemStack stack) {
        return exact(slots, stack).stream().mapToInt(slot -> slot.getItem().getCount()).sum();
    }

    private StationJob.Result running(String message) {
        status = message;
        return StationJob.Result.RUNNING;
    }
}
