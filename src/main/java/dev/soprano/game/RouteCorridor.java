package dev.soprano.game;

import dev.soprano.path.RouteExclusions.Bounds;

import java.util.Objects;

/** A named route preference, scoped to the current world and dimension. */
public record RouteCorridor(String name, Bounds bounds) {
    public RouteCorridor {
        name = Objects.requireNonNull(name, "corridor name").strip();
        if (name.isEmpty() || name.length() > 64 || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("A corridor needs a name of 1-64 characters.");
        }
        Objects.requireNonNull(bounds, "corridor bounds");
    }
}
