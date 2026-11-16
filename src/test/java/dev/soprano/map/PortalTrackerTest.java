package dev.soprano.map;

import dev.soprano.map.PortalAperture.Cell;
import dev.soprano.map.PortalAperture.Kind;
import dev.soprano.map.PortalAperture.Portal;
import dev.soprano.path.SafeReturnCheckpoints.Scope;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class PortalTrackerTest {
    public static void main(String[] args) throws IOException {
        crossedChunk();
        differentPlanes();
        endPortals();
        boundedComponents();
        scopedFiles();
        System.out.println("Soprano portal tracking passed.");
    }

    private static void crossedChunk() {
        var world = new HashMap<BlockPos, Cell>();
        for (int x = -1; x <= 1; x++) {
            for (int y = 60; y <= 64; y++) world.put(new BlockPos(x, y, 3), new Cell(true, Kind.NETHER_X));
        }
        var anchor = new BlockPos(-1, 60, 3);
        var secondChunk = new BlockPos(0, 61, 3);
        var incomplete = PortalAperture.scan(secondChunk, Kind.NETHER_X,
            at -> at.getX() < 0 ? Cell.UNLOADED : read(world, at));
        equal(incomplete.portal(), null, "cannot mark an aperture from a partially loaded chunk boundary");
        equal(incomplete.cells().size(), 10, "loaded part of the aperture is identified");
        var complete = PortalAperture.scan(secondChunk, Kind.NETHER_X, at -> read(world, at));
        equal(complete.portal(), new Portal(anchor, Kind.NETHER_X), "the full aperture anchors at its lower corner");
        equal(complete.cells().size(), 15, "the two chunks contribute a single aperture");
        equal(PortalAperture.scan(anchor, Kind.NETHER_X, at -> read(world, at)).portal(), complete.portal(),
            "discovery from either side selects the same marker");
    }

    private static void differentPlanes() {
        var world = new HashMap<BlockPos, Cell>();
        var first = new BlockPos(14, 50, -1);
        var parallel = first.south();
        var perpendicular = parallel.east();
        world.put(first, new Cell(true, Kind.NETHER_X));
        world.put(first.east(), new Cell(true, Kind.NETHER_X));
        world.put(parallel, new Cell(true, Kind.NETHER_X));
        world.put(perpendicular, new Cell(true, Kind.NETHER_Z));
        world.put(perpendicular.south(), new Cell(true, Kind.NETHER_Z));
        equal(PortalAperture.scan(first, Kind.NETHER_X, at -> read(world, at)).cells().size(), 2,
            "nearby parallel and perpendicular Nether apertures are independent");
        equal(PortalAperture.scan(perpendicular, Kind.NETHER_Z, at -> read(world, at)).cells().size(), 2,
            "the other Nether axis remains discoverable");
    }

    private static void endPortals() {
        var world = new HashMap<BlockPos, Cell>();
        var anchor = new BlockPos(15, 80, 15);
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) world.put(anchor.offset(x, 0, z), new Cell(true, Kind.END));
        }
        var end = PortalAperture.scan(anchor.offset(2, 0, 2), Kind.END, at -> read(world, at));
        equal(end.portal(), new Portal(anchor, Kind.END), "a horizontal End aperture spanning four chunks has one marker");
        equal(end.cells().size(), 9, "connected End aperture contains its nine active blocks");
        var gateway = anchor.above();
        var single = PortalAperture.scan(gateway, Kind.GATEWAY, ignored -> Cell.UNLOADED);
        equal(single.portal(), new Portal(gateway, Kind.GATEWAY), "an End gateway marks its own active block");
        equal(single.cells().size(), 1, "an End gateway has no multi-block aperture");
    }

    private static void boundedComponents() {
        var seed = new BlockPos(0, 40, 0);
        var bounds = new Cell(true, Kind.NETHER_X);
        var legal = PortalAperture.scan(seed, Kind.NETHER_X,
            at -> at.getX() >= 0 && at.getX() < 21 && at.getY() >= 40 && at.getY() < 61 ? bounds : Cell.OTHER);
        equal(legal.cells().size(), 441, "maximum-size vanilla Nether aperture is discoverable");
        equal(legal.portal(), new Portal(seed, Kind.NETHER_X), "largest aperture gets a stable anchor");
        var unbounded = PortalAperture.scan(seed, Kind.NETHER_X, at -> bounds);
        equal(unbounded.portal(), null, "invalid oversized components do not produce arbitrary partial markers");
        equal(unbounded.cells().size(), 442, "the component traversal has a strict ceiling");
    }

    private static void scopedFiles() throws IOException {
        Path directory = Files.createTempDirectory("soprano-portals-test-");
        try {
            var first = new Scope("server.example:25565", "named/one", "minecraft:overworld");
            var otherWorld = new Scope(first.server(), "named/two", first.dimension());
            var otherDimension = new Scope(first.server(), first.world(), "minecraft:the_nether");
            var otherServer = new Scope("server.example:25566", first.world(), first.dimension());
            var portal = new Portal(new BlockPos(-17, 63, 16), Kind.NETHER_Z);
            var gateway = new Portal(new BlockPos(-100, 120, -200), Kind.GATEWAY);
            PortalFiles.write(directory, first, List.of(portal));
            PortalFiles.write(directory, otherDimension, List.of(gateway));
            equal(PortalFiles.read(directory, first), List.of(portal), "exact portal position and type survive reopening");
            equal(PortalFiles.read(directory, otherDimension), List.of(gateway), "dimension observations stay isolated");
            equal(PortalFiles.read(directory, otherWorld), List.of(), "named worlds on one address stay isolated");
            equal(PortalFiles.read(directory, otherServer), List.of(), "server addresses stay isolated");
            PortalFiles.write(directory, first, List.of());
            equal(PortalFiles.read(directory, first), List.of(), "authoritative removal persists");
            equal(PortalFiles.read(directory, otherDimension), List.of(gateway), "cleanup affects only its original scope");
        } finally {
            try (var files = Files.walk(directory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }

    private static Cell read(Map<BlockPos, Cell> world, BlockPos at) { return world.getOrDefault(at, Cell.OTHER); }

    private static void equal(Object actual, Object expected, String purpose) {
        if (!Objects.equals(actual, expected)) throw new AssertionError(purpose + ". Expected " + expected + ", got " + actual);
    }
}
