package dev.soprano.mixin;

import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = AbstractFurnaceBlockEntity.class, remap = false)
public interface FurnaceBlockDataAccessor {
    @Accessor("dataAccess")
    ContainerData soprano$furnaceData();
}
