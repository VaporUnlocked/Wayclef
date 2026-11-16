package dev.soprano.build;

import dev.soprano.build.BuildLayers.Direction;
import dev.soprano.build.BuildLayers.Layer;
import dev.soprano.build.BuildPlan.Cell;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class BuildLayersTest {
    private BuildLayersTest() { }

    public static void main(String[] args) {
        Cell low = new Cell(0, -4, 0);
        Cell highA = new Cell(-1, 7, 6);
        Cell highB = new Cell(1, 7, 0);
        Set<Cell> blueprint = new HashSet<>(Set.of(highB, low, highA));
        var lowerLayer = new Layer(-4, List.of(low));
        var upperLayer = new Layer(7, List.of(highA, highB));
        List<Layer> bottomUp = BuildLayers.stages(blueprint, Direction.BOTTOM_UP);
        check(bottomUp.equals(List.of(lowerLayer, upperLayer)), "bottom up groups by exact Y");
        check(BuildLayers.stages(blueprint, Direction.TOP_DOWN).equals(List.of(upperLayer, lowerLayer)),
            "top down reverses layer order and keeps stable horizontal ordering");
        blueprint.clear();
        check(bottomUp.equals(List.of(lowerLayer, upperLayer)), "stages are an independent snapshot");
        check(BuildLayers.stages(blueprint, Direction.BOTTOM_UP).isEmpty(), "an empty blueprint has no stages");
        System.out.println("Soprano build layers passed 4 checks.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
