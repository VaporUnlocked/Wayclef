package dev.soprano.task;

import dev.soprano.inventory.InventoryJobs;
import dev.soprano.inventory.StorageKnowledge;
import dev.soprano.map.MapVolume;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipePropertySet;
import net.minecraft.world.item.crafting.SelectableRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.StonecutterRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SmithingRecipeDisplay;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.SmithingTableBlock;
import net.minecraft.world.level.block.StonecutterBlock;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.loot.providers.number.floats.ResolvableFloat;
import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/** Resolves the next executable action from live stock, available recipes, and known sources. */
final class GatherPlan {
    @FunctionalInterface
    interface Source { JobSpec find(String itemId, int deficit); }

    @FunctionalInterface
    interface CookedSource { JobSpec find(Item raw, Item result, int deficit); }

    @FunctionalInterface
    interface Fluid { JobSpec find(Item result); }

    @FunctionalInterface
    interface Fishing { JobSpec find(Item result); }

    private static final int MAX_BATCH_ITEMS = 32;
    private static final int MAX_DEPTH = 16;
    private static final int MAX_BRANCHES = 4_096;
    private static final int MAX_SOURCE_LOOKUPS = 64;
    private static final int MAX_FURNACE_PROBES = 3;
    private static final int SOURCE_RADIUS = 24;
    private static final int STATION_RADIUS = 32;
    private static final Map<ClientLevel, RecipeIndex> GAME_RECIPES = new WeakHashMap<>();

    private record Requirement(List<Item> options) implements StackedContents.IngredientInfo<Item> {
        @Override public boolean acceptsItem(Item item) { return options.contains(item); }
    }

    private record Recipe(int yield, List<Requirement> requirements, Set<Item> stations, boolean inventoryGrid,
                          List<Item> fuels, int duration) {
        boolean furnace() { return duration > 0 && !campfire(); }
        boolean campfire() { return stations.contains(Items.CAMPFIRE); }
        boolean stonecut() { return stations.contains(Items.STONECUTTER); }
        boolean smithing() { return stations.contains(Items.SMITHING_TABLE); }

        RecipeKey key() {
            var ingredients = new HashMap<Set<Item>, Integer>();
            requirements.forEach(requirement -> ingredients.merge(Set.copyOf(requirement.options()), 1, Integer::sum));
            return new RecipeKey(yield, ingredients, stations, inventoryGrid, Set.copyOf(fuels), duration);
        }
    }

    private record RecipeKey(int yield, Map<Set<Item>, Integer> ingredients, Set<Item> stations, boolean inventoryGrid,
                             Set<Item> fuels, int duration) { }

    private record RecipeDefinition(RecipeDisplay display, List<Ingredient> ingredients, int yield) { }

    private static final class RecipeIndex {
        final RecipeManager manager;
        private Map<Item, List<RecipeDefinition>> definitions;

        RecipeIndex(RecipeManager manager, Map<Item, List<RecipeDefinition>> definitions) {
            this.manager = manager;
            this.definitions = definitions;
        }

        List<RecipeDefinition> find(ClientLevel level, Item item) {
            if (definitions == null) {
                definitions = new HashMap<>();
                ContextMap context = SlotDisplayContext.fromLevel(level);
                InstalledRecipes.forEach(level.registryAccess(), recipe -> addDefinitions(definitions, recipe, level, context));
            }
            return definitions.getOrDefault(item, List.of());
        }
    }

    private record Demand(Item item, int count) { }

    private record Station(Stock stock, JobSpec action) { }

    record Fuel(int burnTicks, float speed) {
        long ticksFor(int progress) { return (long) Math.min(Integer.MAX_VALUE, Math.ceil(progress / (double) speed)); }
    }

    private static final class Stock {
        final Map<Item, Integer> items;
        final Map<Item, Integer> anticipated;
        final Set<Item> plannedStations;
        JobSpec first;
        int waits;

        Stock(Map<Item, Integer> items, Map<Item, Integer> anticipated) {
            this(items, anticipated, Set.of(), null, 0);
        }

        private Stock(Map<Item, Integer> items, Map<Item, Integer> anticipated, Set<Item> plannedStations, JobSpec first, int waits) {
            this.items = new HashMap<>(items);
            this.anticipated = new HashMap<>(anticipated);
            this.plannedStations = new HashSet<>(plannedStations);
            this.first = first;
            this.waits = waits;
        }

        Stock copy() { return new Stock(items, anticipated, plannedStations, first, waits); }
        int held(Item item) { return items.getOrDefault(item, 0); }
        int count(Item item) { return held(item) + anticipated.getOrDefault(item, 0); }

        void consume(Item item, int count) {
            if (count <= 0) return;
            int held = held(item);
            int used = Math.min(held, count);
            if (used > 0) items.put(item, held - used);
            if (used < count) {
                anticipated.put(item, anticipated.getOrDefault(item, 0) - (count - used));
                waits++;
            }
        }

        void produce(Item item, int count, JobSpec step) {
            produce(item, count, step, waits);
        }

        void produce(Item item, int count, JobSpec step, int priorWaits) {
            boolean executable = waits == priorWaits;
            if (count > 0) (executable ? items : anticipated).merge(item, count, Integer::sum);
            if (executable && first == null && step != null) first = step;
        }

        boolean waitingSince(Stock before) { return first == null && waits > before.waits; }
    }

    private final ClientLevel level;
    private final LocalPlayer player;
    private final BlockPos origin;
    private final Item wanted;
    private final String itemId;
    private final int amount;
    private final Source source;
    private final Function<JobSpec, List<Item>> miningTools;
    private final BiFunction<Item, Integer, List<GatherTrading.Opportunity>> trades;
    private final Consumer<GatherTrading.Opportunity> rememberTrade;
    private final Source unknownStorage;
    private final Fluid fluid;
    private final CookedSource cookedSource;
    private final Fishing fishing;
    private final Set<JobSpec> rejected = new HashSet<>();
    private final Set<BlockPos> probedFurnaces = new HashSet<>();
    private final Map<Item, List<Recipe>> packagedHints = new HashMap<>();
    private List<Item> genericFuelOptions;
    private Map<Item, List<Recipe>> worldRecipes;
    private Map<Item, List<Recipe>> syncedStonecutting = Map.of();
    private SelectableRecipe.SingleInputSet<StonecutterRecipe> stonecutting;
    private RecipeIndex parsedIndex;

    GatherPlan(ClientLevel level, LocalPlayer player, BlockPos origin, String itemId, int amount,
               Source source, Function<JobSpec, List<Item>> miningTools, Source unknownStorage, Fluid fluid,
               CookedSource cookedSource, Fishing fishing, BiFunction<Item, Integer, List<GatherTrading.Opportunity>> trades,
               Consumer<GatherTrading.Opportunity> rememberTrade) {
        this.level = level;
        this.player = player;
        this.origin = origin.immutable();
        this.wanted = InventoryJobs.item(itemId);
        this.itemId = itemId;
        this.amount = amount;
        this.source = source;
        this.miningTools = miningTools;
        this.unknownStorage = unknownStorage;
        this.fluid = fluid;
        this.cookedSource = cookedSource;
        this.fishing = fishing;
        this.trades = trades;
        this.rememberTrade = rememberTrade;
    }

    void reject(JobSpec step) { rejected.add(step); }

    JobSpec next() {
        int missing = amount - InventoryJobs.count(player, itemId);
        if (missing <= 0) return null;

        Map<Item, Integer> items = heldIngredients();
        int batch = Math.min(missing, MAX_BATCH_ITEMS);
        Map<Item, List<Recipe>> recipes = planningRecipes();
        var planner = new Planner(recipes, false, Set.of());
        Stock plan = planner.supply(wanted, batch, new Stock(items, Map.of()), new HashSet<>());
        if (plan == null && batch > 1) {
            plan = planner.readyPartial(wanted, batch - 1, items);
            if (plan == null) {
                planner = new Planner(recipes, false, Set.of());
                plan = planner.supply(wanted, 1, new Stock(items, Map.of()), new HashSet<>());
            }
        }
        if ((plan == null || plan.first == null) && unknownStorage != null) {
            // Inspect an unknown inventory for the requested item before speculating about its ingredients.
            JobSpec direct = unknownStorage.find(itemId, Math.min(batch, 8));
            if (direct != null && !rejected.contains(direct)) return direct;
            planner = new Planner(recipes, true, Set.of());
            plan = planner.supply(wanted, Math.min(batch, 8), new Stock(items, Map.of()), new HashSet<>());
        }
        if (plan != null && plan.first != null) return recordFurnaceProbe(plan.first);
        throw new IllegalStateException("Need " + missing + " more " + itemId + ". " + planner.problem);
    }

    /** Finds work for the current order that can start before the tracked furnace outputs arrive. */
    JobSpec nextWhileSmelting(Map<String, Integer> pendingOutput, Set<BlockPos> occupiedFurnaces) {
        int missing = amount - InventoryJobs.count(player, itemId) - pendingOutput.getOrDefault(itemId, 0);
        if (missing <= 0) return null;
        Map<Item, Integer> anticipated = new HashMap<>();
        pendingOutput.forEach((id, count) -> {
            Item item = InventoryJobs.item(id);
            if (item != wanted && count > 0) anticipated.merge(item, count, Integer::sum);
        });
        var stock = new Stock(heldIngredients(), anticipated);
        Map<Item, List<Recipe>> recipes = planningRecipes();
        var planner = new Planner(recipes, false, occupiedFurnaces);
        int batch = Math.min(missing, MAX_BATCH_ITEMS);
        Stock plan = planner.supply(wanted, batch, stock.copy(), new HashSet<>());
        if (plan == null && batch > 1) {
            planner = new Planner(recipes, false, occupiedFurnaces);
            plan = planner.supply(wanted, 1, stock, new HashSet<>());
        }
        return plan == null ? null : plan.first;
    }

    private JobSpec recordFurnaceProbe(JobSpec step) {
        if (step != null && step.kind() == JobSpec.Kind.FURNACE && step.ids().get(1).equals("minecraft:air")
            && StorageKnowledge.furnace(level, step.target()) == null) probedFurnaces.add(step.target());
        return step;
    }

    private Map<Item, Integer> heldIngredients() {
        Map<Item, Integer> items = new HashMap<>();
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (!stack.isEmpty() && !stack.is(wanted) && Inventory.isUsableForCrafting(stack)) {
                items.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return items;
    }

    private final class Planner {
        private final Map<Item, List<Recipe>> recipes;
        private final Map<Demand, JobSpec> foundSources = new HashMap<>();
        private final Set<Item> absentSources = new HashSet<>();
        private final Map<Item, Optional<JobSpec>> bucketActions = new HashMap<>();
        private final Map<Item, Optional<JobSpec>> fishingActions = new HashMap<>();
        private final Map<Item, List<Recipe>> packagedFallback = new HashMap<>();
        private final Set<BlockPos> occupiedFurnaces;
        private Map<Item, List<BlockPos>> stations;
        private Set<Item> nearbyItems;
        private int branches;
        private int sourceLookups;
        private final boolean allowUnknownStorage;
        private String problem = "No reachable source or available recipe was found.";

        Planner(Map<Item, List<Recipe>> recipes, boolean allowUnknownStorage, Set<BlockPos> occupiedFurnaces) {
            this.recipes = recipes;
            this.allowUnknownStorage = allowUnknownStorage;
            this.occupiedFurnaces = Set.copyOf(occupiedFurnaces);
        }

        private Stock readyPartial(Item item, int maximum, Map<Item, Integer> items) {
            List<Recipe> choices = new ArrayList<>(primaryRecipes(item));
            choices.addAll(fallbackRecipes(item));
            // An exact furnace job may have been rejected while a smaller or larger quantity still works.
            for (int count = maximum; count >= 1 && !choices.isEmpty(); count--) {
                Stock ready = readyRecipe(item, count, choices, new Stock(items, Map.of()), new HashSet<>());
                if (ready != null) return ready;
            }
            return null;
        }

        private Stock readyRecipe(Item item, int deficit, List<Recipe> choices, Stock stock, Set<Item> chain) {
            Stock waiting = null;
            for (Recipe recipe : choices) {
                Stock ready = useRecipe(item, deficit, recipe, stock, chain, false);
                if (ready == null) continue;
                if (!ready.waitingSince(stock)) return ready;
                if (waiting == null) waiting = ready;
            }
            return waiting;
        }

        Stock supply(Item item, int count, Stock stock, Set<Item> chain) {
            int held = Math.min(count, stock.count(item));
            stock.consume(item, held);
            int deficit = count - held;
            if (deficit == 0) return stock;
            if (branches++ >= MAX_BRANCHES || chain.size() >= MAX_DEPTH || !chain.add(item)) return null;
            try {
                if (item == Items.BUCKET) {
                    Stock nearbyBucket = useSource(item, deficit, stock, chain);
                    if (nearbyBucket != null) return nearbyBucket;
                }
                List<Recipe> choices = primaryRecipes(item);
                Stock ready = readyRecipe(item, deficit, choices, stock, chain);
                if (ready != null) return ready;

                Stock nearby = useSource(item, deficit, stock, chain);
                if (nearby != null) return nearby;
                if (parsedIndex.manager == null && !stationPositions(CampfireBatch.STATIONS).isEmpty()) {
                    var campfireRecipes = fallbackRecipes(item).stream().filter(Recipe::campfire).toList();
                    Stock campfire = readyRecipe(item, deficit, campfireRecipes, stock, chain);
                    if (campfire != null) return campfire;
                }
                Item captureBucket = GatherHunt.captureIngredient(item);
                Item bucket = GatherFluid.supports(item) ? Items.BUCKET : captureBucket;
                if (bucket != null) {
                    Stock bucketed = bucket(item, bucket, deficit, stock, chain);
                    if (bucketed != null) return bucketed;
                } else if (choices.isEmpty()) {
                    problem = "No loaded source or available recipe for " + InventoryJobs.itemId(item) + ".";
                }
                for (Recipe recipe : orderRecipes(choices, stock, deficit)) {
                    Stock assembled = useRecipe(item, deficit, recipe, stock, chain, true);
                    if (assembled != null) return assembled;
                }
                for (Recipe recipe : orderRecipes(fallbackRecipes(item), stock, deficit)) {
                    Stock assembled = useRecipe(item, deficit, recipe, stock, chain, true);
                    if (assembled != null) return assembled;
                }
                if (item != Items.FISHING_ROD) {
                    JobSpec cast = fishingActions.computeIfAbsent(item, key -> Optional.ofNullable(fishing.find(key))).orElse(null);
                    if (cast != null && !rejected.contains(cast)) {
                        if (!GatherFishing.availableRod(player).isEmpty()) {
                            stock.produce(item, 0, cast);
                            return stock;
                        }
                        Stock rod = supply(Items.FISHING_ROD, 1, stock.copy(), chain);
                        if (rod != null && rod.first != null) return rod;
                    }
                }
                Stock trade = trade(item, deficit, stock, chain);
                if (trade != null) return trade;
                if (allowUnknownStorage) {
                    JobSpec candidate = unknownStorage.find(InventoryJobs.itemId(item), deficit);
                    if (candidate != null && !rejected.contains(candidate)) {
                        stock.produce(item, 0, candidate);
                        return stock;
                    }
                }
                return null;
            } finally {
                chain.remove(item);
            }
        }

        private List<Recipe> primaryRecipes(Item item) {
            return parsedIndex.manager == null ? recipes.getOrDefault(item, List.of())
                : recipes.computeIfAbsent(item, GatherPlan.this::readIndexedRecipes);
        }

        private List<Recipe> fallbackRecipes(Item item) {
            if (parsedIndex.manager != null) return List.of();
            return packagedFallback.computeIfAbsent(item, key -> {
                List<Recipe> installed = packagedHints.computeIfAbsent(key, GatherPlan.this::readIndexedRecipes);
                List<Recipe> synced = recipes.getOrDefault(key, List.of());
                if (installed.isEmpty()) return installed;
                Set<RecipeKey> used = new HashSet<>();
                synced.forEach(recipe -> used.add(recipe.key()));
                return installed.stream().filter(recipe -> !recipe.stonecut() && used.add(recipe.key())).toList();
            });
        }

        private Stock bucket(Item result, Item container, int count, Stock stock, Set<Item> chain) {
            var action = bucketActions.computeIfAbsent(result, item -> Optional.ofNullable(fluid.find(item))
                .filter(step -> !rejected.contains(step)));
            if (action.isEmpty()) {
                if (result == Items.WATER_BUCKET) problem = "No reachable loaded water source could be filled.";
                else if (result == Items.LAVA_BUCKET) problem = "No reachable loaded lava source could be filled.";
                else problem = "No reachable loaded mob can provide " + InventoryJobs.itemId(result) + ".";
                return null;
            }
            Stock supplied = supply(container, count, stock.copy(), chain);
            if (supplied == null) {
                problem = "Need " + count + " " + InventoryJobs.itemId(container) + " for " + InventoryJobs.itemId(result)
                    + ". " + problem;
                return null;
            }
            supplied.produce(result, 0, action.get(), stock.waits);
            return supplied;
        }

        private JobSpec source(Item item, int deficit) {
            Demand key = new Demand(item, deficit);
            JobSpec found = foundSources.get(key);
            if (found != null || absentSources.contains(item)) return found;
            if (sourceLookups++ >= MAX_SOURCE_LOOKUPS) return null;
            JobSpec step = source.find(InventoryJobs.itemId(item), deficit);
            if (step == null) {
                absentSources.add(item);
                return null;
            }
            if (rejected.contains(step) || conflictsWithFurnace(step)) return null;
            foundSources.put(key, step);
            return step;
        }

        private Stock useSource(Item item, int deficit, Stock stock, Set<Item> chain) {
            JobSpec step = source(item, deficit);
            if (step == null) return null;
            List<Item> tools = miningTools.apply(step);
            if (tools.isEmpty()) {
                stock.produce(item, 0, step);
                return stock;
            }
            for (Item tool : tools) {
                // A carried stack of this item was filtered out as unusable by the source search.
                int needed = Math.max(stock.count(tool), InventoryJobs.count(player, InventoryJobs.itemId(tool))) + 1;
                Stock supplied = supply(tool, needed, stock.copy(), chain);
                if (supplied != null && (supplied.first != null || supplied.waitingSince(stock))) return supplied;
            }
            problem = "A compatible mining tool is needed for " + InventoryJobs.itemId(item) + ".";
            return null;
        }

        private Stock trade(Item item, int deficit, Stock stock, Set<Item> chain) {
            for (var opportunity : trades.apply(item, deficit)) {
                JobSpec next = opportunity.action();
                if (rejected.contains(next)) continue;
                Stock supplied = stock.copy();
                for (var cost : opportunity.payments().entrySet()) {
                    supplied = supply(cost.getKey(), cost.getValue(), supplied, chain);
                    if (supplied == null) break;
                }
                if (supplied == null) continue;
                rememberTrade.accept(opportunity);
                supplied.produce(item, 0, next, stock.waits);
                return supplied;
            }
            return null;
        }

        private boolean conflictsWithFurnace(JobSpec step) {
            if (step.target() != null && occupiedFurnaces.contains(step.target())) return true;
            var bounds = step.region();
            if (step.kind() != JobSpec.Kind.MINE || bounds == null) return false;
            for (BlockPos at : occupiedFurnaces) {
                if (at.getX() < bounds.minX() || at.getX() > bounds.maxX() || at.getY() < bounds.minY() || at.getY() > bounds.maxY()
                    || at.getZ() < bounds.minZ() || at.getZ() > bounds.maxZ()) continue;
                if (step.ids().contains(BuiltInRegistries.BLOCK.getKey(level.getBlockState(at).getBlock()).toString())) return true;
            }
            return false;
        }

        private List<Recipe> orderRecipes(List<Recipe> choices, Stock stock, int deficit) {
            if (choices.size() < 2) return choices;
            return choices.stream().sorted(Comparator.comparingLong((Recipe recipe) -> missingIngredients(recipe, stock, deficit))
                .thenComparingInt(recipe -> recipe.requirements().stream()
                    .mapToInt(requirement -> requirement.options().stream().mapToInt(item -> directPreference(item, stock)).min().orElse(2))
                    .sum())).toList();
        }

        private long missingIngredients(Recipe recipe, Stock stock, int deficit) {
            long crafts = Math.ceilDiv(deficit, recipe.yield());
            var requirements = new HashMap<Requirement, Long>();
            recipe.requirements().forEach(requirement -> requirements.merge(requirement, crafts, Long::sum));
            long missing = 0;
            for (var requirement : requirements.entrySet()) {
                var counts = requirement.getKey().options().stream().mapToLong(stock::count);
                long held = recipe.furnace() || recipe.campfire() || recipe.stonecut() ? counts.max().orElse(0) : counts.sum();
                missing += Math.max(0, requirement.getValue() - held);
            }
            return missing;
        }

        private List<Item> orderIngredients(List<Item> options, Stock stock) {
            if (options.size() < 2) return options;
            return options.stream().sorted(Comparator.comparingInt(stock::held).reversed()
                .thenComparing(Comparator.comparingInt(stock::count).reversed())
                .thenComparingInt(item -> ingredientPreference(item, stock)).thenComparing(InventoryJobs::itemId)).toList();
        }

        private int ingredientPreference(Item item, Stock stock) {
            int direct = directPreference(item, stock);
            if (direct < 2) return direct;
            if (primaryRecipes(item).stream().anyMatch(recipe -> hasLocalInputs(recipe, stock))
                || fallbackRecipes(item).stream().anyMatch(recipe -> hasLocalInputs(recipe, stock))) return 2;
            return 3;
        }

        private boolean hasLocalInputs(Recipe recipe, Stock stock) {
            return recipe.requirements().stream().allMatch(requirement -> requirement.options().stream()
                .anyMatch(item -> directPreference(item, stock) < 2));
        }

        private int directPreference(Item item, Stock stock) {
            if (stock.count(item) > 0) return 0;
            if (nearbyItems == null) nearbyItems = scanNearbyItems();
            return nearbyItems.contains(item) ? 1 : 2;
        }

        private Stock useRecipe(Item item, int deficit, Recipe recipe, Stock stock, Set<Item> chain, boolean gather) {
            int crafts = Math.ceilDiv(deficit, recipe.yield());
            if (recipe.furnace()) return cook(item, deficit, crafts, recipe, stock, chain, gather);
            if (recipe.campfire()) return cookAtCampfire(item, crafts, recipe, stock, chain, gather);
            if (recipe.stonecut()) return cut(item, deficit, crafts, recipe, stock, chain, gather);
            int produced = crafts * recipe.yield();
            String command = (recipe.smithing() ? "smith " : "craft ") + InventoryJobs.itemId(item) + " " + produced;

            Stock result = stock.copy();
            List<Item> selected = new ArrayList<>();
            var contents = new StackedContents<Item>();
            stock.items.forEach(contents::account);
            boolean picked = contents.tryPick(recipe.requirements(), crafts, selected::add);
            if (!picked && !stock.anticipated.isEmpty()) {
                selected.clear();
                stock.anticipated.forEach(contents::account);
                picked = contents.tryPick(recipe.requirements(), crafts, selected::add);
            }
            if (picked) {
                for (Item ingredient : selected) result.consume(ingredient, crafts);
            } else if (gather) {
                Map<Requirement, Integer> requirements = new LinkedHashMap<>();
                recipe.requirements().forEach(requirement -> requirements.merge(requirement, crafts, Integer::sum));
                var groups = new ArrayList<>(requirements.entrySet());
                groups.sort(Comparator.comparingInt(entry -> entry.getKey().options().size()));
                result = ingredients(groups, 0, result, chain);
                if (result == null) return null;
            } else return null;

            return finishRecipe(item, produced - deficit, recipe, command, result, chain, gather, stock.waits);
        }

        private Stock cookAtCampfire(Item result, int crafts, Recipe recipe, Stock stock, Set<Item> chain, boolean gather) {
            crafts = Math.min(crafts, CampfireBatch.MAX_INPUTS);
            var positions = stationPositions(recipe.stations());
            if (positions.isEmpty()) return null;
            var propertySet = level.recipeAccess().propertySet(RecipePropertySet.CAMPFIRE_INPUT);
            for (Item input : orderIngredients(recipe.requirements().getFirst().options(), stock)) {
                if (!propertySet.test(input.getDefaultInstance()) || !gather && stock.count(input) < crafts) continue;
                JobSpec step = stationStep("campfire " + InventoryJobs.itemId(input) + " " + InventoryJobs.itemId(result)
                    + " " + crafts, positions);
                if (step == null) continue;
                Stock supplied = supply(input, crafts, stock.copy(), chain);
                if (supplied == null) continue;
                supplied.produce(result, 0, step, stock.waits);
                return supplied;
            }
            return null;
        }

        private Stock cut(Item result, int deficit, int crafts, Recipe recipe, Stock stock, Set<Item> chain,
                          boolean gather) {
            for (Item input : orderIngredients(recipe.requirements().getFirst().options(), stock)) {
                if (!gather && stock.count(input) < crafts) continue;
                Stock supplied = supply(input, crafts, stock.copy(), chain);
                if (supplied == null) continue;
                String command = "stonecut " + InventoryJobs.itemId(input) + " " + InventoryJobs.itemId(result) + " " + crafts;
                Stock step = finishRecipe(result, crafts * recipe.yield() - deficit, recipe, command, supplied,
                    chain, gather, stock.waits);
                if (step != null) return step;
            }
            return null;
        }

        private Stock ingredients(List<Map.Entry<Requirement, Integer>> groups, int index, Stock stock, Set<Item> chain) {
            if (index == groups.size()) return stock;
            if (branches++ >= MAX_BRANCHES) return null;
            var group = groups.get(index);
            List<Item> choices = orderIngredients(group.getKey().options(), stock);
            int count = group.getValue();

            // Split tags across types already held. Since one batch may fill each slot from a
            // different type, this allocation is only used by the dependency search.
            Stock mixed = stock.copy();
            int missing = count;
            for (Item ingredient : choices) {
                int used = Math.min(missing, mixed.count(ingredient));
                mixed.consume(ingredient, used);
                missing -= used;
                if (missing == 0) break;
            }
            if (missing == 0) {
                Stock next = ingredients(groups, index + 1, mixed, chain);
                if (next != null) return next;
            } else if (missing < count) {
                for (Item ingredient : choices) {
                    Stock supplied = supply(ingredient, missing, mixed.copy(), chain);
                    if (supplied == null) continue;
                    Stock next = ingredients(groups, index + 1, supplied, chain);
                    if (next != null) return next;
                }
            }

            for (Item ingredient : choices) {
                Stock supplied = supply(ingredient, count, stock.copy(), chain);
                if (supplied == null) continue;
                Stock next = ingredients(groups, index + 1, supplied, chain);
                if (next != null) return next;
            }
            return null;
        }

        private Stock cook(Item item, int deficit, int crafts, Recipe recipe, Stock stock, Set<Item> chain, boolean gather) {
            Stock stocked = useStockedFurnace(item, deficit, crafts, recipe, stock, chain, gather);
            Stock waiting = null;
            if (stocked != null) {
                if (!stocked.waitingSince(stock)) return stocked;
                waiting = stocked;
            }
            List<Item> fuels = byStock(gather ? recipe.fuels() : recipe.fuels().stream().filter(fuel -> stock.count(fuel) > 0).toList(), stock);
            List<Item> inputs = byStock(recipe.requirements().getFirst().options(), stock);
            boolean inspect = gather && occupiedFurnaces.isEmpty() && probedFurnaces.size() < MAX_FURNACE_PROBES
                && (inputs.stream().noneMatch(input -> stock.held(input) >= crafts)
                    || !canFuelFromInventory(item, crafts, recipe, stock, inputs));
            Stock feasibleInput = null;
            for (Item input : inputs) {
                if (stock.count(input) < crafts) {
                    if (!gather) continue;
                    JobSpec hunt = cookedSource.find(input, item, deficit);
                    if (hunt != null && !rejected.contains(hunt)) {
                        Stock result = stock.copy();
                        result.produce(item, 0, hunt);
                        return result;
                    }
                }
                if (inspect) {
                    Stock probe = probeFurnace(item, input, crafts, recipe, stock);
                    if (probe != null) return probe;
                    inspect = false;
                }
                Stock loaded = supply(input, crafts, stock.copy(), chain);
                if (loaded == null) continue;
                if (feasibleInput == null) feasibleInput = loaded;
                for (Item fuel : fuels) {
                    if (fuel == item) continue;
                    int fuelNeeded = fuelNeeded(fuel, crafts, recipe.duration());
                    if (fuelNeeded <= 0) continue;
                    if (!gather && loaded.count(fuel) < fuelNeeded) continue;
                    String command = "furnace " + InventoryJobs.itemId(input) + " " + InventoryJobs.itemId(fuel) + " "
                        + InventoryJobs.itemId(item) + " " + crafts;
                    Stock result = supply(fuel, fuelNeeded, loaded.copy(), chain);
                    if (result == null) continue;
                    result = finishRecipe(item, crafts * recipe.yield() - deficit, recipe, command, result, chain, gather, stock.waits);
                    if (result == null) continue;
                    if (!result.waitingSince(stock)) return result;
                    if (waiting == null) waiting = result;
                }
            }
            if (waiting != null) return waiting;
            if (gather && feasibleInput != null && occupiedStation(recipe.stations())) {
                // Prepare raw inputs even if the currently occupied station's future fuel isn't known yet.
                feasibleInput.waits++;
                feasibleInput.produce(item, crafts * recipe.yield() - deficit, null, stock.waits);
                return feasibleInput;
            }
            return null;
        }

        private boolean canFuelFromInventory(Item item, int crafts, Recipe recipe, Stock stock, List<Item> inputs) {
            for (Item fuel : recipe.fuels()) {
                if (fuel == item || stock.held(fuel) == 0) continue;
                int count = fuelNeeded(fuel, crafts, recipe.duration());
                if (count > 0 && inputs.stream().anyMatch(input -> stock.held(fuel) - (input == fuel ? crafts : 0) >= count)) {
                    return true;
                }
            }
            return false;
        }

        private Stock probeFurnace(Item item, Item input, int crafts, Recipe recipe, Stock stock) {
            for (BlockPos at : stationPositions(recipe.stations())) {
                if (probedFurnaces.contains(at) || StorageKnowledge.furnace(level, at) != null) continue;
                JobSpec step = stockedFurnaceStep(item, input, crafts, at);
                if (rejected.contains(step)) continue;
                // Learn the actual slots first. No additional input is staged unless the full job can use it.
                Stock result = stock.copy();
                result.produce(item, 0, step);
                return result;
            }
            return null;
        }

        private Stock useStockedFurnace(Item item, int deficit, int crafts, Recipe recipe, Stock stock, Set<Item> chain,
                                       boolean gather) {
            Stock waiting = null;
            for (BlockPos at : stationPositions(recipe.stations())) {
                var known = StorageKnowledge.furnace(level, at);
                if (known == null) continue;
                boolean ready = known.output().is(item);
                if (!ready && (!known.output().isEmpty() || !canCookWithStoredFuel(known, crafts, recipe.duration()))) continue;
                for (Item input : byStock(recipe.requirements().getFirst().options(), stock)) {
                    int loaded = known.input().is(input) ? known.input().getCount() : 0;
                    if (!ready && !known.input().isEmpty() && loaded == 0) continue;
                    int count = ready ? 1 : crafts;
                    int need = ready ? 0 : Math.max(0, count - loaded);
                    JobSpec step = stockedFurnaceStep(item, input, count, at);
                    if (rejected.contains(step)) continue;
                    if (!gather && stock.count(input) < need) continue;
                    Stock result = need == 0 ? stock.copy() : supply(input, need, stock.copy(), chain);
                    if (result == null) continue;
                    // Ready output is collected and measured before planning the rest of the batch.
                    result.produce(item, ready ? 0 : crafts * recipe.yield() - deficit, step, stock.waits);
                    if (!result.waitingSince(stock)) return result;
                    if (waiting == null) waiting = result;
                }
            }
            return waiting;
        }

        private JobSpec stockedFurnaceStep(Item item, Item input, int count, BlockPos at) {
            return JobSpec.parse("furnace " + InventoryJobs.itemId(input) + " minecraft:air "
                + InventoryJobs.itemId(item) + " " + count, null, at, origin);
        }

        private boolean canCookWithStoredFuel(StorageKnowledge.FurnaceStock stock, int crafts, int duration) {
            int cooked = stock.input().isEmpty() ? 0 : Math.min(stock.cookTicks(), duration - 1);
            long work = (long) crafts * duration - cooked - stock.burnTicks();
            if (work <= 0) return true;
            Fuel fuel = cookingFuel(stock.fuel());
            if (fuel == null) return false;
            long fullCrafts = (work - 1) / duration;
            int partialWork = (int) (work - fullCrafts * duration);
            long ticks = fullCrafts * fuel.ticksFor(duration) + fuel.ticksFor(partialWork);
            return ticks <= (long) fuel.burnTicks() * stock.fuel().getCount();
        }

        private Stock finishRecipe(Item item, int extra, Recipe recipe, String command, Stock stock, Set<Item> chain,
                                   boolean gather, int priorWaits) {
            Station station = prepareStation(recipe, command, stock, chain, gather);
            if (station == null) {
                String stationName = recipe.furnace() ? "furnace" : recipe.stonecut() ? "stonecutter"
                    : recipe.smithing() ? "smithing table" : "crafting table";
                problem = "No reachable compatible " + stationName + " for "
                    + InventoryJobs.itemId(item) + ".";
                return null;
            }
            station.stock().produce(item, extra, station.action(), priorWaits);
            return station.stock();
        }

        private Station prepareStation(Recipe recipe, String command, Stock stock, Set<Item> chain, boolean gather) {
            if (recipe.inventoryGrid() && !player.isCreative()) {
                JobSpec inventory = JobSpec.parse(command, null, null, origin);
                if (!rejected.contains(inventory)) return new Station(stock, inventory);
            }
            JobSpec nearby = stationStep(command, stationPositions(recipe.stations()));
            if (nearby != null) return new Station(stock, nearby);
            if (recipe.stations().stream().anyMatch(stock.plannedStations::contains)) {
                if (stock.first == null) stock.waits++;
                return new Station(stock, null);
            }
            if (!gather) return null;
            if (occupiedStation(recipe.stations())) {
                stock.waits++;
                return new Station(stock, null);
            }
            Stock arranged = arrangeStation(recipe, stock, chain);
            return arranged == null ? null : new Station(arranged, null);
        }

        private Stock arrangeStation(Recipe recipe, Stock stock, Set<Item> chain) {
            for (Item station : byStock(recipe.stations().stream().filter(GatherPlan::canSetUp).toList(), stock)) {
                JobSpec setup = JobSpec.parse("setup " + InventoryJobs.itemId(station), null, null, origin);
                if (rejected.contains(setup)) continue;
                Stock supplied = supply(station, 1, stock.copy(), chain);
                if (supplied == null) continue;
                supplied.plannedStations.add(station);
                supplied.produce(station, 0, setup, stock.waits);
                return supplied;
            }
            return null;
        }

        private JobSpec stationStep(String command, List<BlockPos> locations) {
            for (BlockPos position : locations) {
                JobSpec step = JobSpec.parse(command, null, position, origin);
                if (!rejected.contains(step)) return step;
            }
            return null;
        }

        private List<BlockPos> stationPositions(Set<Item> types) {
            if (types.isEmpty()) return List.of();
            if (stations == null) stations = scanStations();
            return types.stream().flatMap(item -> stations.getOrDefault(item, List.of()).stream())
                .filter(at -> !occupiedFurnaces.contains(at))
                .sorted(Comparator.comparingDouble((BlockPos at) -> at.distSqr(player.blockPosition())).thenComparing(at -> at))
                .toList();
        }

        private boolean occupiedStation(Set<Item> types) {
            return occupiedFurnaces.stream().anyMatch(at -> WorldJob.loaded(level, at) && types.contains(level.getBlockState(at).getBlock().asItem()));
        }
    }

    private Map<Item, List<Recipe>> planningRecipes() {
        RecipeIndex index = recipeIndex(level);
        if (parsedIndex != index) {
            parsedIndex = index;
            genericFuelOptions = null;
            packagedHints.clear();
            worldRecipes = index.manager == null ? null : new HashMap<>();
        }
        return worldRecipes != null ? worldRecipes : recipeBook();
    }

    private Map<Item, List<Recipe>> recipeBook() {
        Map<Item, List<Recipe>> recipes = new HashMap<>();
        ContextMap context = SlotDisplayContext.fromLevel(level);
        for (var collection : player.getRecipeBook().getCollections()) {
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                addRecipe(recipes, entry.display(), entry.craftingRequirements().orElse(List.of()), context);
            }
        }
        var synced = level.recipeAccess().stonecutterRecipes();
        if (synced != stonecutting) {
            stonecutting = synced;
            syncedStonecutting = new HashMap<>();
            for (var entry : synced.entries()) {
                var display = new StonecutterRecipeDisplay(entry.input().display(), entry.recipe().optionDisplay(),
                    new SlotDisplay.ItemSlotDisplay(Items.STONECUTTER));
                addRecipe(syncedStonecutting, display, List.of(entry.input()), context);
            }
        }
        syncedStonecutting.forEach((item, cuts) -> recipes.computeIfAbsent(item, key -> new ArrayList<>()).addAll(cuts));
        return recipes;
    }

    static List<RecipeDisplay> gameRecipeDisplays(ClientLevel level, Item item) {
        return recipeIndex(level).find(level, item).stream().map(RecipeDefinition::display).toList();
    }

    private static RecipeIndex recipeIndex(ClientLevel level) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        RecipeManager manager = server == null ? null : server.getRecipeManager();
        RecipeIndex existing = GAME_RECIPES.get(level);
        if (existing != null && existing.manager == manager) return existing;
        Map<Item, List<RecipeDefinition>> definitions = null;
        if (manager != null) {
            definitions = new HashMap<>();
            ContextMap context = SlotDisplayContext.fromLevel(level);
            for (var holder : manager.getRecipes()) addDefinitions(definitions, holder.value(), level, context);
        }
        RecipeIndex index = new RecipeIndex(manager, definitions);
        GAME_RECIPES.put(level, index);
        return index;
    }

    private List<Recipe> readIndexedRecipes(Item item) {
        return readDefinitions(parsedIndex.find(level, item), SlotDisplayContext.fromLevel(level));
    }

    private List<Recipe> readDefinitions(List<RecipeDefinition> definitions, ContextMap context) {
        List<Recipe> recipes = new ArrayList<>();
        Set<RecipeKey> keys = new HashSet<>();
        for (var definition : definitions) {
            Recipe recipe = readRecipe(definition.display(), definition.ingredients(), definition.yield(), context);
            if (recipe != null && keys.add(recipe.key())) recipes.add(recipe);
        }
        return List.copyOf(recipes);
    }

    private static void addDefinitions(Map<Item, List<RecipeDefinition>> definitions, net.minecraft.world.item.crafting.Recipe<?> recipe,
                                       ClientLevel level, ContextMap context) {
        for (RecipeDisplay display : recipe.display()) {
            if (!supported(display) || !display.isEnabled(level.enabledFeatures())) continue;
            ItemStack output = display.result().resolveForFirstStack(context);
            if (output.isEmpty()) continue;
            var definition = new RecipeDefinition(display, recipe.placementInfo().ingredients(), output.getCount());
            definitions.computeIfAbsent(output.getItem(), key -> new ArrayList<>()).add(definition);
        }
    }

    private void addRecipe(Map<Item, List<Recipe>> recipes, RecipeDisplay display, List<Ingredient> ingredients, ContextMap context) {
        if (!supported(display)) return;
        ItemStack output = display.result().resolveForFirstStack(context);
        if (output.isEmpty()) return;
        Recipe recipe = readRecipe(display, ingredients, output.getCount(), context);
        if (recipe != null) recipes.computeIfAbsent(output.getItem(), key -> new ArrayList<>()).add(recipe);
    }

    private static boolean supported(RecipeDisplay display) {
        return display instanceof FurnaceRecipeDisplay || display instanceof StonecutterRecipeDisplay
            || display instanceof ShapedCraftingRecipeDisplay || display instanceof ShapelessCraftingRecipeDisplay
            || display instanceof SmithingRecipeDisplay smithing && smithing.result() instanceof SlotDisplay.ItemStackSlotDisplay;
    }

    private Recipe readRecipe(RecipeDisplay display, List<Ingredient> ingredients, int yield, ContextMap context) {
        Set<Item> stations = Set.copyOf(items(display.craftingStation().resolveForStacks(context)));
        if (display instanceof StonecutterRecipeDisplay cutting && stations.contains(Items.STONECUTTER)) {
            var input = items(cutting.input().resolveForStacks(context));
            return input.isEmpty() ? null : new Recipe(yield, List.of(new Requirement(input)), stations, false, List.of(), 0);
        }
        if (display instanceof SmithingRecipeDisplay smithing && stations.contains(Items.SMITHING_TABLE)) {
            List<Requirement> requirements = smithingSlots(smithing).stream().filter(slot -> slot != SlotDisplay.Empty.INSTANCE)
                .map(slot -> new Requirement(items(slot.resolveForStacks(context)))).toList();
            return requirements.isEmpty() || requirements.stream().anyMatch(requirement -> requirement.options().isEmpty())
                ? null : new Recipe(yield, requirements, stations, false, List.of(), 0);
        }
        if (display instanceof FurnaceRecipeDisplay cooking) {
            var input = items(cooking.ingredient().resolveForStacks(context));
            if (stations.contains(Items.CAMPFIRE)) {
                return input.isEmpty() || cooking.duration() <= 0 ? null
                    : new Recipe(yield, List.of(new Requirement(input)), CampfireBatch.STATIONS, false, List.of(), cooking.duration());
            }
            List<Item> fuel;
            if (cooking.fuel() instanceof SlotDisplay.AnyFuel) {
                if (genericFuelOptions == null) genericFuelOptions = items(cooking.fuel().resolveForStacks(context));
                fuel = genericFuelOptions;
            } else fuel = items(cooking.fuel().resolveForStacks(context));
            if (input.isEmpty() || fuel.isEmpty() || cooking.duration() <= 0 || stations.isEmpty()) return null;
            return new Recipe(yield, List.of(new Requirement(input)), stations, false, fuel, cooking.duration());
        }
        boolean fitsInventory;
        if (display instanceof ShapedCraftingRecipeDisplay shaped && shaped.width() <= 3 && shaped.height() <= 3) {
            fitsInventory = shaped.width() <= 2 && shaped.height() <= 2;
        } else if (display instanceof ShapelessCraftingRecipeDisplay shapeless && shapeless.ingredients().size() <= 9) {
            fitsInventory = shapeless.ingredients().size() <= 4;
        } else return null;

        List<Requirement> requirements = ingredients.stream()
            .map(Ingredient::items).map(options -> new Requirement(options.map(holder -> holder.value()).distinct().toList())).toList();
        if (requirements.isEmpty() || requirements.stream().anyMatch(requirement -> requirement.options().isEmpty())) return null;
        fitsInventory &= stations.contains(Items.CRAFTING_TABLE);
        return new Recipe(yield, requirements, stations, fitsInventory, List.of(), 0);
    }

    private Map<Item, List<BlockPos>> scanStations() {
        Map<Item, List<BlockPos>> stations = new HashMap<>();
        MapVolume.Box area = WorldJob.nearbyArea(player.blockPosition(), STATION_RADIUS, level.getMinY(), level.getMaxY());
        for (int cx = area.minX() >> 4; cx <= area.maxX() >> 4; cx++) {
            for (int cz = area.minZ() >> 4; cz <= area.maxZ() >> 4; cz++) {
                var chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                for (int cy = area.minY() >> 4; cy <= area.maxY() >> 4; cy++) {
                    var section = chunk.getSections()[cy - (level.getMinY() >> 4)];
                    if (!section.maybeHas(state -> stationBlock(state.getBlock()))) continue;
                    for (int y = Math.max(area.minY(), cy << 4); y <= Math.min(area.maxY(), (cy << 4) + 15); y++) {
                        for (int z = Math.max(area.minZ(), cz << 4); z <= Math.min(area.maxZ(), (cz << 4) + 15); z++) {
                            for (int x = Math.max(area.minX(), cx << 4); x <= Math.min(area.maxX(), (cx << 4) + 15); x++) {
                                var block = section.getBlockState(x & 15, y & 15, z & 15).getBlock();
                                if (!stationBlock(block)) continue;
                                var at = new BlockPos(x, y, z);
                                if (block instanceof CampfireBlock && !CampfireBatch.available(level, at)) continue;
                                stations.computeIfAbsent(block.asItem(), item -> new ArrayList<>()).add(at);
                            }
                        }
                    }
                }
            }
        }
        return stations;
    }

    /** Source hints from loaded palettes keep a local log ahead of absent wood variants in a tag. */
    private Set<Item> scanNearbyItems() {
        Set<Item> present = new HashSet<>();
        MapVolume.Box area = WorldJob.nearbyArea(origin, SOURCE_RADIUS, level.getMinY(), level.getMaxY());
        for (int cx = area.minX() >> 4; cx <= area.maxX() >> 4; cx++) {
            for (int cz = area.minZ() >> 4; cz <= area.maxZ() >> 4; cz++) {
                var chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                for (int cy = area.minY() >> 4; cy <= area.maxY() >> 4; cy++) {
                    var section = chunk.getSections()[cy - (level.getMinY() >> 4)];
                    if (!section.hasOnlyAir()) {
                        section.getStates().count((state, count) -> {
                            if (count > 0 && !state.isAir()) present.add(state.getBlock().asItem());
                        });
                    }
                }
            }
        }
        var bounds = new AABB(area.minX(), area.minY(), area.minZ(), area.maxX() + 1.0, area.maxY() + 1.0, area.maxZ() + 1.0);
        for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, bounds, ItemEntity::isAlive)) {
            if (!drop.getItem().isEmpty()) present.add(drop.getItem().getItem());
        }
        return present;
    }

    private static boolean stationBlock(net.minecraft.world.level.block.Block block) {
        return block instanceof CraftingTableBlock || block instanceof AbstractFurnaceBlock || block instanceof StonecutterBlock
            || block instanceof SmithingTableBlock || block instanceof CampfireBlock;
    }

    private static boolean canSetUp(Item item) {
        return item == Items.CRAFTING_TABLE || item == Items.STONECUTTER || item == Items.SMITHING_TABLE
            || item == Items.FURNACE || item == Items.SMOKER
            || item == Items.BLAST_FURNACE;
    }

    static List<SlotDisplay> smithingSlots(SmithingRecipeDisplay smithing) {
        return List.of(smithing.template(), smithing.base(), smithing.addition());
    }

    private static List<Item> items(List<ItemStack> stacks) {
        return stacks.stream().filter(stack -> !stack.isEmpty()).map(ItemStack::getItem).distinct().toList();
    }

    private static List<Item> byStock(List<Item> items, Stock stock) {
        return items.stream().sorted(Comparator.comparingInt(stock::held).reversed()
            .thenComparing(Comparator.comparingInt(stock::count).reversed()).thenComparing(InventoryJobs::itemId)).toList();
    }

    private static int fuelNeeded(Item item, int crafts, int duration) {
        Fuel fuel = cookingFuel(item.getDefaultInstance());
        if (fuel == null) return 0;
        long total = fuel.ticksFor(duration) * crafts;
        return (int) Math.min(Integer.MAX_VALUE, Math.ceilDiv(total, fuel.burnTicks()));
    }

    static Fuel cookingFuel(ItemStack stack) {
        var fuel = stack.get(DataComponents.COOKING_FUEL);
        if (fuel == null) return null;
        int ticks;
        if (fuel.burnTime() instanceof ResolvableInt.Constant value) ticks = value.value();
        else if (fuel.burnTime() instanceof ResolvableInt.Reference reference) {
            // The client receives the reference, while the server resolves the provider.
            // These are conservative estimates for the corresponding vanilla providers.
            ticks = switch (reference.key().identifier().toString()) {
                case "minecraft:cooking/time_coal" -> 1_600;
                case "minecraft:cooking/time_coal_block" -> 16_000;
                case "minecraft:cooking/time_lava_bucket" -> 20_000;
                case "minecraft:cooking/time_dried_kelp_block" -> 4_000;
                case "minecraft:cooking/time_blaze_rod" -> 2_400;
                case "minecraft:cooking/time_boats" -> 1_200;
                case "minecraft:cooking/time_hanging_signs" -> 800;
                case "minecraft:cooking/time_wood_blocks", "minecraft:cooking/time_wood_items_small", "minecraft:cooking/time_roots" -> 300;
                case "minecraft:cooking/time_wood_items_large" -> 200;
                case "minecraft:cooking/time_wood_slabs" -> 150;
                case "minecraft:cooking/time_wood_items_extra_small", "minecraft:cooking/time_wool", "minecraft:cooking/time_dry_plants" -> 100;
                case "minecraft:cooking/time_wool_carpets" -> 67;
                case "minecraft:cooking/time_bamboo", "minecraft:cooking/time_wool_slabs" -> 50;
                default -> 0;
            };
        } else return null;
        if (ticks <= 0) return null;
        float speed = fuel.speedMultiplier() instanceof ResolvableFloat.Constant constant ? constant.value() : 1;
        return !Float.isFinite(speed) || speed <= 0 ? null : new Fuel(ticks, speed);
    }
}
