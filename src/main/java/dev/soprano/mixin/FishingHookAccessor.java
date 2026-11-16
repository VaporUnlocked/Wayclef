package dev.soprano.mixin;

import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = FishingHook.class, remap = false)
public interface FishingHookAccessor {
    @Accessor("biting")
    boolean soprano$biting();
}
