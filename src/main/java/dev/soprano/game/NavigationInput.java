package dev.soprano.game;

import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/** Implemented on the vanilla keyboard input instance by Soprano's client mixin. */
public interface NavigationInput {
    void soprano$apply(Input keys, Vec2 direction);
    boolean soprano$releaseSprint();
}
