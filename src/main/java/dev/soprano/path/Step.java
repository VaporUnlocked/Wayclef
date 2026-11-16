package dev.soprano.path;

import java.util.Objects;

public record Step(Node from, Node to, Move move) {
    static final int GAP_JUMP_COST = 245;

    public Step {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(move, "move");
    }

    /** Base cost units used by the walking search, where a flat cardinal walk costs 100. */
    public int cost() {
        int elevation = Math.abs(to.y16() - from.y16());
        int base = from.x() != to.x() && from.z() != to.z() ? 142 : 100;
        return switch (move) {
            case WALK -> base + elevation * 2;
            case JUMP -> gapJump() ? GAP_JUMP_COST : base + 45 + elevation * 2;
            case DROP -> base + 35 + elevation * 3;
            case CLIMB, BUBBLE -> 150 + elevation * 4;
        };
    }

    public boolean gapJump() { return move == Move.JUMP && isGapSpan(from, to); }

    public static boolean isGapSpan(Node from, Node to) {
        int dx = Math.abs(to.x() - from.x()), dz = Math.abs(to.z() - from.z());
        return from.y16() == to.y16() && (dx == 2 && dz == 0 || dx == 0 && dz == 2);
    }
}
