package dev.soprano.game;

import java.util.Objects;

/** Permissions and hard action caps captured when a route starts. */
public record RoutePermissions(int breakLimit, int placeLimit, boolean useDoors, boolean closeDoors,
                               RouteBlockProtection protectedBlocks, int materialReserve, int toolDurabilityReserve) {
    public static final RoutePermissions DEFAULT = new RoutePermissions(0, 0, true, false);
    public static final int MAX_MATERIAL_RESERVE = 4096;
    public static final int MAX_TOOL_RESERVE = 10_000;

    public RoutePermissions(int breakLimit, int placeLimit, boolean useDoors, boolean closeDoors) {
        this(breakLimit, placeLimit, useDoors, closeDoors, RouteBlockProtection.NONE, 0, 0);
    }

    public RoutePermissions {
        Objects.requireNonNull(protectedBlocks);
        if (breakLimit < 0 || breakLimit > 64 || placeLimit < 0 || placeLimit > 64) {
            throw new IllegalArgumentException("Navigation break and placement limits must be 0-64.");
        }
        if (materialReserve < 0 || materialReserve > MAX_MATERIAL_RESERVE
            || toolDurabilityReserve < 0 || toolDurabilityReserve > MAX_TOOL_RESERVE) {
            throw new IllegalArgumentException("Route edit reserves must be within their configured limits.");
        }
    }
}
