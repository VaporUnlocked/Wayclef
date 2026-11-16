package dev.soprano.map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** A portal aperture is connected within the plane of its active blocks. */
final class PortalAperture {
    private static final int MAX_CELLS = 21 * 21;
    private static final List<Direction> VERTICAL_X = List.of(Direction.WEST, Direction.EAST, Direction.DOWN, Direction.UP);
    private static final List<Direction> VERTICAL_Z = List.of(Direction.NORTH, Direction.SOUTH, Direction.DOWN, Direction.UP);
    private static final List<Direction> HORIZONTAL = List.of(Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH);
    static final Comparator<BlockPos> ANCHOR_ORDER =
        Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ);

    enum Kind {
        NETHER_X("Nether portal", VERTICAL_X),
        NETHER_Z("Nether portal", VERTICAL_Z),
        END("End portal", HORIZONTAL),
        GATEWAY("End gateway", List.of());

        private final String label;
        private final List<Direction> plane;

        Kind(String label, List<Direction> plane) {
            this.label = label;
            this.plane = plane;
        }

        String label() { return label; }
    }

    record Portal(BlockPos anchor, Kind kind) {
        Portal {
            anchor = Objects.requireNonNull(anchor, "portal position").immutable();
            Objects.requireNonNull(kind, "portal kind");
            if (Math.abs((long) anchor.getX()) > 30_002_000 || Math.abs((long) anchor.getZ()) > 30_002_000
                || Math.abs((long) anchor.getY()) > 32_768) throw new IllegalArgumentException("Portal position is outside the supported world.");
        }
    }

    /** The lookup distinguishes missing chunks from confirmed non-portal blocks. */
    record Cell(boolean loaded, Kind kind) {
        static final Cell UNLOADED = new Cell(false, null);
        static final Cell OTHER = new Cell(true, null);

        private static final Cell NETHER_X = new Cell(true, Kind.NETHER_X);
        private static final Cell NETHER_Z = new Cell(true, Kind.NETHER_Z);
        private static final Cell END = new Cell(true, Kind.END);
        private static final Cell GATEWAY = new Cell(true, Kind.GATEWAY);

        static Cell of(BlockState state) {
            if (state.is(Blocks.NETHER_PORTAL)) {
                return state.getValue(BlockStateProperties.HORIZONTAL_AXIS) == Direction.Axis.X ? NETHER_X : NETHER_Z;
            }
            if (state.is(Blocks.END_PORTAL)) return END;
            if (state.is(Blocks.END_GATEWAY)) return GATEWAY;
            return OTHER;
        }
    }

    record Scan(Portal portal, Set<BlockPos> cells) { }

    private PortalAperture() { }

    static boolean isPortal(BlockState state) { return Cell.of(state).kind() != null; }

    /** An unloaded frontier may hide the canonical anchor, so it cannot produce a new marker. */
    static Scan scan(BlockPos start, Kind kind, Function<BlockPos, Cell> lookup) {
        var cells = new HashSet<BlockPos>();
        var pending = new ArrayDeque<BlockPos>();
        cells.add(start);
        pending.add(start);
        BlockPos anchor = start;
        boolean complete = true;
        while (!pending.isEmpty()) {
            BlockPos at = pending.removeFirst();
            if (ANCHOR_ORDER.compare(at, anchor) < 0) anchor = at;
            for (Direction direction : kind.plane) {
                BlockPos neighbor = at.relative(direction);
                if (cells.contains(neighbor)) continue;
                Cell cell = lookup.apply(neighbor);
                if (!cell.loaded()) complete = false;
                if (cell.kind() != kind) continue;
                cells.add(neighbor);
                if (cells.size() > MAX_CELLS) return new Scan(null, cells);
                pending.add(neighbor);
            }
        }
        return new Scan(complete ? new Portal(anchor, kind) : null, cells);
    }
}
