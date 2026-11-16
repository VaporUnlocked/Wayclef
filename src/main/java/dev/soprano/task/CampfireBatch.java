package dev.soprano.task;

import dev.soprano.inventory.InventoryJobs;
import dev.soprano.map.MapVolume;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipePropertySet;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;

import java.util.Set;
import java.util.stream.Stream;

final class CampfireBatch {
    static final int MAX_INPUTS = 4;
    static final Set<Item> STATIONS = Set.of(Items.CAMPFIRE, Items.SOUL_CAMPFIRE);
    private static final int DROP_RADIUS = 4;
    private static final int WAIT_MARGIN = 400;
    private final ClientLevel level;
    private final LocalPlayer player;
    private final JobSpec job;
    private final Item ingredient;
    private final int duration;
    private final WorldJob pickup;
    private int staged;
    private int priorCount;
    private int priorSlots;
    private long lastPlacement;
    private boolean awaitingSlot;

    CampfireBatch(JobSpec job, ClientLevel level, LocalPlayer player) {
        this.job = job;
        this.level = level;
        this.player = player;
        ingredient = InventoryJobs.item(job.ids().getFirst());
        Item result = InventoryJobs.item(job.ids().getLast());
        if (!available(level, job.target())) throw new IllegalStateException("The campfire is unavailable or already has food.");
        if (countInput() < job.limit()) throw new IllegalStateException("The campfire batch inputs are missing.");
        if (!level.recipeAccess().propertySet(RecipePropertySet.CAMPFIRE_INPUT).test(ingredient.getDefaultInstance())) {
            throw new IllegalStateException("The server does not accept " + job.ids().getFirst() + " at a campfire.");
        }
        var context = SlotDisplayContext.fromLevel(level);
        var fromBook = player.getRecipeBook().getCollections().stream().flatMap(collection -> collection.getRecipes().stream())
            .map(RecipeDisplayEntry::display);
        var recipe = Stream.concat(fromBook, GatherPlan.gameRecipeDisplays(level, result).stream())
            .filter(FurnaceRecipeDisplay.class::isInstance).map(FurnaceRecipeDisplay.class::cast)
            .filter(display -> display.craftingStation().resolveForStacks(context).stream().anyMatch(stack -> stack.is(Items.CAMPFIRE)))
            .filter(display -> display.ingredient().resolveForStacks(context).stream().anyMatch(stack -> stack.is(ingredient)))
            .filter(display -> display.result().resolveForFirstStack(context).is(result))
            .findFirst().orElseThrow(() -> new IllegalStateException("No campfire recipe produces " + job.ids().getLast() + " with this input."));
        duration = recipe.duration();
        int count = job.limit() * recipe.result().resolveForFirstStack(context).getCount();
        BlockPos at = job.target();
        var area = new MapVolume.Box(at.getX() - DROP_RADIUS, Math.max(level.getMinY(), at.getY() - 2), at.getZ() - DROP_RADIUS,
            at.getX() + DROP_RADIUS, Math.min(level.getMaxY(), at.getY() + 3), at.getZ() + DROP_RADIUS);
        pickup = new WorldJob(JobSpec.parse("collect " + job.ids().getLast() + " " + count, area, null, null), level, player);
    }

    static boolean available(ClientLevel level, BlockPos at) {
        var fire = litCampfire(level, at);
        return fire != null && fire.getItems().stream().allMatch(stack -> stack.isEmpty());
    }

    private static CampfireBlockEntity litCampfire(ClientLevel level, BlockPos at) {
        return WorldJob.loaded(level, at) && CampfireBlock.isLitCampfire(level.getBlockState(at))
            && level.getBlockEntity(at) instanceof CampfireBlockEntity fire ? fire : null;
    }

    Item ingredient() { return ingredient; }
    WorldJob pickup() { return pickup; }

    void submitInput() {
        priorSlots = filledSlots();
        if (priorSlots > staged || priorSlots >= MAX_INPUTS) throw new IllegalStateException("The campfire contents changed.");
        priorCount = countInput();
        if (priorCount == 0) throw new IllegalStateException("The requested campfire ingredient is missing.");
        lastPlacement = level.getGameTime();
        awaitingSlot = true;
    }

    boolean stagingComplete() { return staged >= job.limit(); }

    boolean inputConfirmed() {
        if (!awaitingSlot) return true;
        int occupied = filledSlots();
        int used = priorCount - countInput();
        if (occupied > staged + 1 || used > 1) throw new IllegalStateException("The campfire contents or ingredients changed.");
        if (used != 1 || occupied <= priorSlots && !outputArrived()) return false;
        staged++;
        awaitingSlot = false;
        return true;
    }

    private boolean outputArrived() {
        return pickup.collected() > 0 || pickup.nearestDrop(pickup.collectBox(), Set.of()) != null;
    }

    boolean timedOut() { return level.getGameTime() - lastPlacement > duration + WAIT_MARGIN; }
    boolean cooked() { return stagingComplete() && filledSlots() == 0; }

    private int filledSlots() {
        var fire = litCampfire(level, job.target());
        if (fire == null) throw new IllegalStateException("The campfire went out or is no longer loaded.");
        int count = 0;
        for (var stack : fire.getItems()) {
            if (stack.isEmpty()) continue;
            if (!stack.is(ingredient)) throw new IllegalStateException("Someone put different food on the campfire.");
            count++;
        }
        return count;
    }

    private int countInput() { return InventoryJobs.count(player, job.ids().getFirst()); }
}
