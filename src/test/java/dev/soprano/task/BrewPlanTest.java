package dev.soprano.task;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.predicates.PotionsPredicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.BrewingRecipe;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.PotionIngredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public final class BrewPlanTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.ITEM.forEach(item -> item.builtInRegistryHolder().bindComponents(DataComponentMap.EMPTY));
        var recipes = List.of(
            recipe(Potions.WATER, Items.NETHER_WART, Items.POTION, Potions.AWKWARD),
            recipe(Potions.AWKWARD, Items.SUGAR, Items.POTION, Potions.SWIFTNESS),
            recipe(Potions.SWIFTNESS, Items.REDSTONE, Items.POTION, Potions.LONG_SWIFTNESS),
            recipe(Potions.LONG_SWIFTNESS, Items.GUNPOWDER, Items.SPLASH_POTION, Potions.LONG_SWIFTNESS),
            recipe(Potions.AWKWARD, Items.BLAZE_POWDER, Items.POTION, Potions.STRENGTH)
        );
        var water = PotionContents.createItemStack(Items.POTION, Potions.WATER);
        var awkward = PotionContents.createItemStack(Items.POTION, Potions.AWKWARD);
        var splash = PotionContents.createItemStack(Items.SPLASH_POTION, Potions.LONG_SWIFTNESS);
        var batch = choose(recipes, List.of(water, water, water, awkward, new ItemStack(Items.NETHER_WART),
            new ItemStack(Items.SUGAR), new ItemStack(Items.REDSTONE), new ItemStack(Items.GUNPOWDER)),
            ItemStack.EMPTY, splash);
        assert batch != null && batch.count() == 3 && batch.steps().size() == 4;
        assert ItemStack.matches(batch.input(), water);
        assert ItemStack.matches(batch.steps().getLast().result(), splash);
        var powder = new ItemStack(Items.BLAZE_POWDER);
        var strength = PotionContents.createItemStack(Items.POTION, Potions.STRENGTH);
        assert choose(recipes, List.of(awkward, powder), powder, strength) == null;
        assert choose(recipes, List.of(awkward, powder, powder), powder, strength) != null;
        assert choose(recipes, List.of(water, powder, powder), powder, strength) == null;

        var custom = new BrewingRecipe(PotionIngredient.of(Items.STICK), PotionIngredient.of(Items.EMERALD),
            new ItemStackTemplate(Items.DIAMOND, DataComponentPatch.EMPTY));
        var customBatch = choose(List.of(custom), List.of(new ItemStack(Items.STICK, 3), new ItemStack(Items.EMERALD)),
            ItemStack.EMPTY, new ItemStack(Items.DIAMOND));
        assert customBatch != null && customBatch.count() == 3 && customBatch.steps().size() == 1;

        var spec = JobSpec.parse("brew long_swiftness 3 splash_potion", null, BlockPos.ZERO, null);
        assert spec.kind() == JobSpec.Kind.BREW && spec.limit() == 3;
        assert spec.ids().equals(List.of("minecraft:splash_potion", "minecraft:long_swiftness"));
        assert JobSpec.decode(spec.encode()).equals(spec);
        var customSpec = JobSpec.parse("brew item mod:crystal", null, BlockPos.ZERO, null);
        assert customSpec.ids().equals(List.of("mod:crystal")) && customSpec.limit() == 1;
        var enchant = JobSpec.parse("enchant mod:sword 2 mod:sharp", null, BlockPos.ZERO, null);
        assert enchant.kind() == JobSpec.Kind.ENCHANT && enchant.limit() == 2;
        assert enchant.ids().equals(List.of("mod:sword", "mod:sharp")) && JobSpec.decode(enchant.encode()).equals(enchant);
        installedRecipes();
        System.out.println("Soprano brewing, component matching and installed recipe compatibility checks passed.");
    }

    private static BrewPlan.Batch choose(List<BrewingRecipe> recipes, List<ItemStack> items, ItemStack fuel, ItemStack result) {
        Predicate<ItemStack> any = stack -> true;
        return BrewPlan.choose(recipes, items, fuel, 3, stack -> ItemStack.isSameItemSameComponents(stack, result), any, any);
    }

    private static BrewingRecipe recipe(Holder<Potion> input, Item reagent, Item form, Holder<Potion> outcome) {
        var output = new ItemStackTemplate(form,
            DataComponentPatch.builder().set(DataComponents.POTION_CONTENTS, new PotionContents(outcome)).build());
        return new BrewingRecipe(PotionIngredient.of(Items.POTION, PotionsPredicate.ofPotion(input)),
            PotionIngredient.of(reagent), output);
    }

    private static void installedRecipes() throws Exception {
        Path root = Files.createTempDirectory("soprano-brew-data-");
        Path vanilla = root.resolve("base");
        Path mod = root.resolve("mod");
        Path recipeFolder = Path.of("data", "fixture", "recipe");
        Path old = vanilla.resolve(recipeFolder).resolve("brew.json");
        Path overridden = mod.resolve(recipeFolder).resolve("brew.json");
        Path crafting = mod.resolve(recipeFolder).resolve("craft.json");
        try {
            Files.createDirectories(old.getParent());
            Files.createDirectories(overridden.getParent());
            Files.writeString(old, brewData("minecraft:iron_ingot"));
            Files.writeString(overridden, brewData("minecraft:diamond"));
            Files.writeString(crafting, """
                {"type":"minecraft:crafting_shapeless","ingredients":["minecraft:stick","minecraft:stick"],
                 "result":{"id":"minecraft:emerald"}}
                """);
            var resources = new PackLocationInfo("fixture", Component.literal("Recipe fixture"), PackSource.DEFAULT, Optional.empty());
            var decoded = new ArrayList<Recipe<?>>();
            try (var pack = new PathPackResources(resources, vanilla)) {
                InstalledRecipes.forEach(pack, List.of(mod), RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY), decoded::add);
            }
            var brewing = decoded.stream().filter(BrewingRecipe.class::isInstance).map(BrewingRecipe.class::cast).toList();
            assert brewing.size() == 1 && brewing.getFirst().getOutput().create().is(Items.DIAMOND);
            var crafted = decoded.stream().filter(ShapelessRecipe.class::isInstance).map(ShapelessRecipe.class::cast).findFirst().orElseThrow();
            var grid = CraftingInput.of(2, 1, List.of(new ItemStack(Items.STICK), new ItemStack(Items.STICK)));
            assert crafted.matches(grid, null) && crafted.assemble(grid).is(Items.EMERALD);
        } finally {
            Files.deleteIfExists(crafting);
            Files.deleteIfExists(overridden);
            Files.deleteIfExists(old);
            for (Path pack : List.of(mod, vanilla)) {
                for (Path folder = pack.resolve(recipeFolder); !folder.equals(root); folder = folder.getParent()) Files.deleteIfExists(folder);
            }
            Files.deleteIfExists(root);
        }
    }

    private static String brewData(String output) {
        return """
            {"type":"minecraft:brewing","input":{"item":"minecraft:stick"},"reagent":{"item":"minecraft:emerald"},
             "output":{"id":"%s"}}
            """.formatted(output);
    }
}
