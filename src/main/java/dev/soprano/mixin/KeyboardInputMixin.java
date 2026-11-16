package dev.soprano.mixin;

import dev.soprano.game.Navigation;
import dev.soprano.game.NavigationInput;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = KeyboardInput.class, priority = 850, remap = false)
abstract class KeyboardInputMixin extends ClientInput implements NavigationInput {
    @Unique private boolean soprano$releaseSprint;

    @Inject(method = "tick()V", at = @At("TAIL"))
    private void soprano$afterVanillaControls(CallbackInfo callback) {
        soprano$releaseSprint = false;
        Navigation.get().applyControls((KeyboardInput) (Object) this);
    }

    @Override
    public void soprano$apply(Input keys, Vec2 direction) {
        keyPresses = keys;
        moveVector = direction;
        soprano$releaseSprint = !keys.sprint();
    }

    @Override
    public boolean soprano$releaseSprint() { return soprano$releaseSprint; }
}
