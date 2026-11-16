package dev.soprano.game.transport;

import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** A route specific to a vanilla transport, serviced on the client thread. */
public interface TransportRoute {
    enum Status { PLANNING, MOVING, ARRIVED, FAILED }

    void advance(int expansions, long budgetNanos);
    Input input();
    Status status();
    String detail();
    List<Vec3> waypoints();
    default Vec2 walkDirection() { return null; }
    default boolean allowsDismounted() { return false; }
    default void close() { }
}
