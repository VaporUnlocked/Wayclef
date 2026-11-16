package dev.soprano.build;

import dev.soprano.build.BuildPlan.Cell;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Orders blueprint targets into nonempty, single-height build stages. */
public final class BuildLayers {
    private static final Comparator<Cell> HORIZONTAL_ORDER =
        Comparator.comparingInt(Cell::x).thenComparingInt(Cell::z);

    private BuildLayers() { }

    public enum Direction { BOTTOM_UP, TOP_DOWN }

    public record Layer(int y, List<Cell> targets) {
        public Layer {
            targets = List.copyOf(targets);
            if (targets.isEmpty() || targets.stream().anyMatch(cell -> cell.y() != y)) {
                throw new IllegalArgumentException("A build layer needs targets at a single Y height.");
            }
        }
    }

    public static List<Layer> stages(Set<Cell> targets, Direction direction) {
        Objects.requireNonNull(targets, "targets");
        Objects.requireNonNull(direction, "direction");
        var byHeight = new TreeMap<Integer, List<Cell>>();
        for (Cell cell : targets) {
            byHeight.computeIfAbsent(cell.y(), height -> new ArrayList<>()).add(cell);
        }

        var ordered = direction == Direction.TOP_DOWN ? byHeight.descendingMap() : byHeight;
        List<Layer> layers = new ArrayList<>(byHeight.size());
        for (var entry : ordered.entrySet()) {
            List<Cell> cells = entry.getValue();
            cells.sort(HORIZONTAL_ORDER);
            layers.add(new Layer(entry.getKey(), cells));
        }
        return List.copyOf(layers);
    }
}
