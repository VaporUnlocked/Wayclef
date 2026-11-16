package dev.soprano.mixin;

import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = AbstractFurnaceMenu.class, remap = false)
public interface FurnaceDataAccessor {
    @Accessor("data")
    ContainerData soprano$furnaceData();
}
