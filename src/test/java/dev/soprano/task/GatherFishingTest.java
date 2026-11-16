package dev.soprano.task;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biome;

public final class GatherFishingTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        ItemStack rod = ItemStack.EMPTY;
        var jungle = biome("jungle");
        var plains = biome("plains");
        assert GatherSources.canFish(Items.COD, rod, plains, false);
        assert GatherSources.canFish(Items.PUFFERFISH, rod, plains, false);
        assert GatherSources.canFish(Items.NAME_TAG, rod, plains, null);
        assert GatherSources.canFish(Items.NAME_TAG, rod, plains, true);
        assert !GatherSources.canFish(Items.NAME_TAG, rod, plains, false);
        assert GatherSources.canFish(Items.ENCHANTED_BOOK, rod, plains, true);
        assert !GatherSources.canFish(Items.BOOK, rod, plains, true);
        assert GatherSources.canFish(Items.BAMBOO, rod, jungle, false);
        assert !GatherSources.canFish(Items.BAMBOO, rod, plains, true);
        assert !GatherSources.canFish(Items.DIAMOND, rod, jungle, true);

        for (double drop : new double[] { .25, 1.7, 4.5 }) {
            for (double distance : new double[] { 2.0, 5.0, 10.0 }) {
                double pitch = GatherFishing.castPitch(distance, drop);
                assert Math.abs(GatherFishing.castLength(pitch, drop) - distance) < .005;
            }
        }
        assert Double.isNaN(GatherFishing.castPitch(18, 1.7));

        var pool = new BlockPos(4, 62, -8);
        var action = JobSpec.parse("fish minecraft:cod", null, pool, null);
        assert action.kind() == JobSpec.Kind.FISH && action.target().equals(pool);
        assert JobSpec.decode(action.encode()).equals(action);
        System.out.println("Soprano fishing loot, cast, and saved action checks passed.");
    }

    private static Holder<Biome> biome(String path) {
        var key = ResourceKey.create(Registries.BIOME, Identifier.withDefaultNamespace(path));
        return Holder.Reference.createStandAlone(null, key);
    }
}
