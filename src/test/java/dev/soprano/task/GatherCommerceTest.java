package dev.soprano.task;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;

import java.util.UUID;

public final class GatherCommerceTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        assert GatherSources.canBarter(Items.GRAVEL);
        assert GatherSources.canBarter(Items.OBSIDIAN);
        assert GatherSources.canBarter(Items.QUARTZ);
        assert !GatherSources.canBarter(Items.DIAMOND);
        assert !GatherSources.canBarter(Items.GOLD_INGOT);

        UUID mob = UUID.fromString("0353a6be-4fdb-44a9-a4ef-e3714f04a634");
        var trade = JobSpec.parse("trade bell 1 " + mob, null, null, null);
        assert trade.kind() == JobSpec.Kind.TRADE && trade.entityId().equals(mob) && trade.limit() == 1;
        assert JobSpec.decode(trade.encode()).equals(trade);
        var barter = JobSpec.parse("barter gravel 1 " + mob, null, null, null);
        assert barter.kind() == JobSpec.Kind.BARTER && barter.entityId().equals(mob) && barter.limit() == 1;
        assert JobSpec.decode(barter.encode()).equals(barter);
        var breed = JobSpec.parse("breed cow 2", null, null, null);
        assert breed.kind() == JobSpec.Kind.BREED && breed.ids().getFirst().equals("minecraft:cow") && breed.limit() == 2;
        assert JobSpec.decode(breed.encode()).equals(breed);
        System.out.println("Soprano barter loot and merchant and breeding job checks passed.");
    }
}
