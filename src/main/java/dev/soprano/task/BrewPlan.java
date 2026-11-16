package dev.soprano.task;

import dev.soprano.inventory.InventoryJobs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.crafting.BrewingInput;
import net.minecraft.world.item.crafting.BrewingRecipe;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/** Plans batches from the actual potion components and reagents the player has. */
final class BrewPlan {
    static final int BOTTLES = 3;
    private static final int MAX_STEPS = 8;
    private static final int MAX_STATES = 2_048;
    private static final Map<ClientLevel, List<BrewingRecipe>> INSTALLED = new WeakHashMap<>();

    record Batch(ItemStack input, int count, List<Step> steps) { }
    record Step(ItemStack reagent, ItemStack result) { }
    private record Variant(ItemStack sample) {
        @Override public boolean equals(Object other) {
            return other instanceof Variant variant && ItemStack.isSameItemSameComponents(sample, variant.sample);
        }
        @Override public int hashCode() { return ItemStack.hashItemAndComponents(sample); }
    }
    private record Search(Variant input, Variant current, List<Step> steps, Map<Variant, Integer> used) { }
    private record Visited(Variant input, Variant current, Map<Variant, Integer> used) { }

    private BrewPlan() { }

    static Predicate<ItemStack> target(ClientLevel level, List<String> ids) {
        Item output = InventoryJobs.item(ids.getFirst());
        if (ids.size() == 1) return stack -> stack.is(output);
        Identifier id = Identifier.parse(ids.get(1));
        Holder<Potion> potion = level.registryAccess().lookupOrThrow(Registries.POTION).get(id)
            .orElseThrow(() -> new IllegalArgumentException("Unknown potion " + id + "."));
        return stack -> {
            var contents = stack.get(DataComponents.POTION_CONTENTS);
            return stack.is(output) && contents != null && contents.potion().filter(potion::equals).isPresent();
        };
    }

    static List<BrewingRecipe> recipes(ClientLevel level) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null) return server.getRecipeManager().getRecipes().stream().map(holder -> holder.value())
            .filter(BrewingRecipe.class::isInstance).map(BrewingRecipe.class::cast).toList();
        return INSTALLED.computeIfAbsent(level, world -> {
            var recipes = new ArrayList<BrewingRecipe>();
            InstalledRecipes.forEach(world.registryAccess(), recipe -> {
                if (recipe instanceof BrewingRecipe brew) recipes.add(brew);
            });
            return List.copyOf(recipes);
        });
    }

    static Batch choose(List<BrewingRecipe> recipes, List<ItemStack> inventory, ItemStack reservedFuel, int remaining,
                        Predicate<ItemStack> target, Predicate<ItemStack> canInput, Predicate<ItemStack> canReagent) {
        var quantities = new LinkedHashMap<Variant, Integer>();
        for (ItemStack stack : inventory) {
            if (!stack.isEmpty()) quantities.merge(new Variant(stack.copyWithCount(1)), stack.getCount(), Integer::sum);
        }
        if (!reservedFuel.isEmpty()) quantities.computeIfPresent(new Variant(reservedFuel), (key, count) -> count - 1);
        Map<Item, List<BrewingRecipe>> byInput = new HashMap<>();
        for (BrewingRecipe recipe : recipes) {
            recipe.getInput().ingredient().items().forEach(item ->
                byInput.computeIfAbsent(item.value(), key -> new ArrayList<>()).add(recipe));
        }
        var reagents = quantities.keySet().stream().filter(variant -> quantities.get(variant) > 0)
            .filter(variant -> canReagent.test(variant.sample())).toList();
        var available = quantities.keySet().stream().filter(variant -> canInput.test(variant.sample()))
            .filter(variant -> byInput.containsKey(variant.sample().getItem()) && !target.test(variant.sample())).toList();
        for (int count = Math.min(remaining, BOTTLES); count > 0; count--) {
            var result = search(byInput, quantities, available, reagents, count, remaining, target);
            if (result != null) return result;
        }
        return null;
    }

    private static Batch search(Map<Item, List<BrewingRecipe>> byInput, Map<Variant, Integer> quantities,
                                List<Variant> available, List<Variant> reagents, int count, int remaining,
                                Predicate<ItemStack> target) {
        var pending = new ArrayDeque<Search>();
        var visited = new HashSet<Visited>();
        for (Variant input : available) {
            if (quantities.get(input) < count) continue;
            pending.add(new Search(input, input, List.of(), Map.of()));
        }
        while (!pending.isEmpty() && visited.size() < MAX_STATES) {
            Search node = pending.removeFirst();
            if (!visited.add(new Visited(node.input(), node.current(), node.used()))) continue;
            if (node.steps().size() >= MAX_STEPS) continue;
            for (BrewingRecipe recipe : byInput.getOrDefault(node.current().sample().getItem(), List.of())) {
                if (!recipe.getInput().test(node.current().sample())) continue;
                for (Variant reagent : reagents) {
                    int used = node.used().getOrDefault(reagent, 0);
                    if (quantities.get(reagent) <= used + (reagent.equals(node.input()) ? count : 0)
                        || !recipe.getReagent().test(reagent.sample())) continue;
                    ItemStack result = recipe.assemble(new BrewingInput(node.current().sample(), reagent.sample()));
                    if (result.isEmpty() || ItemStack.isSameItemSameComponents(result, node.current().sample())) continue;
                    boolean complete = target.test(result);
                    if (complete ? (long) result.getCount() * count > remaining : result.getCount() != 1) continue;
                    var steps = new ArrayList<>(node.steps());
                    steps.add(new Step(reagent.sample(), result));
                    if (complete) return new Batch(node.input().sample(), count, List.copyOf(steps));
                    var supply = new HashMap<>(node.used());
                    supply.put(reagent, used + 1);
                    pending.addLast(new Search(node.input(), new Variant(result), List.copyOf(steps), Map.copyOf(supply)));
                    if (pending.size() >= MAX_STATES) break;
                }
                if (pending.size() >= MAX_STATES) break;
            }
        }
        return null;
    }
}
