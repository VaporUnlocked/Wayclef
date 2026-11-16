package dev.soprano.path;

import java.util.List;

/** A view of terrain the client can currently verify. Unknown terrain supplies no floors. */
public interface Terrain {
    /** Standing positions in this column, including both height bounds. */
    List<Node> floors(int x, int z, int minY16, int maxY16);

    /** Check the swept body clearance and movement-specific footing for this transition. */
    boolean canTraverse(Node from, Node to, Move move);

    /** Nonnegative preference cost. Traversal still requires verified terrain clearance. */
    default int transitionPenalty(Node from, Node to, Move move) { return 0; }

    /** Start and end a batch of stable, client-thread terrain queries. */
    default void beginSearchSlice() { }
    default void endSearchSlice() { }

    /** Walking can include multiple small rises or falls between neighboring centers. */
    default boolean supportsExtendedSteps() { return false; }

    default boolean supportsGapJumps() { return false; }

    default boolean supportsClimbing() { return false; }
    default List<Node> climbNeighbors(Node from) { return List.of(); }

    default List<Node> bubbleNeighbors(Node from) { return List.of(); }

    /** Unsupported footholds on ladders are intermediate positions, never stopping points. */
    default boolean canFinish(Node node) { return true; }

    default boolean hasLineOfSight(Node node, Goal goal) { return false; }

    default boolean isSkyExposed(Node node) { return false; }
}
