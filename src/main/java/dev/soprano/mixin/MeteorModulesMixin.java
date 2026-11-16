package dev.soprano.mixin;

import dev.soprano.host.MeteorSopranoModule;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Restores Meteor settings saved before the Wayclef module was renamed. */
@Pseudo
@Mixin(targets = "meteordevelopment.meteorclient.systems.modules.Modules", remap = false)
abstract class MeteorModulesMixin {
    @Redirect(method = "fromTag(Lnet/minecraft/nbt/CompoundTag;)Lmeteordevelopment/meteorclient/systems/modules/Modules;",
        at = @At(value = "INVOKE", target = "Lmeteordevelopment/meteorclient/systems/modules/Modules;get(Ljava/lang/String;)Lmeteordevelopment/meteorclient/systems/modules/Module;"))
    private Module wayclef$loadLegacyModule(Modules modules, String name) {
        Module current = modules.get(name);
        return current == null && name.equals("soprano-navigation") ? modules.get(MeteorSopranoModule.class) : current;
    }
}
