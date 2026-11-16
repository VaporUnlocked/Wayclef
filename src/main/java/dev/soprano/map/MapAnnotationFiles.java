package dev.soprano.map;

import dev.soprano.path.SafeReturnCheckpoints.Scope;
import net.minecraft.core.BlockPos;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

final class MapAnnotationFiles {
    static final int MAX_NOTES = 256;
    private static final int MAGIC = 0x534f4101;
    private static final int MAX_MARKERS = MAX_NOTES + 2;
    private static final int MAX_BYTES = 256 * 1024;

    private MapAnnotationFiles() { }

    static List<MapAnnotations.Marker> read(Path root, Scope scope) throws IOException {
        Path file = file(root, scope);
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return List.of();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES)
            throw new IOException("Invalid map marker file.");
        try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (input.readInt() != MAGIC) throw new IOException("Unsupported map marker format.");
            int count = input.readUnsignedShort();
            if (count > MAX_MARKERS) throw new IOException("Too many map markers.");
            List<MapAnnotations.Marker> markers = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                var kind = MapAnnotations.Kind.valueOf(input.readUTF());
                var position = new BlockPos(input.readInt(), input.readInt(), input.readInt());
                markers.add(new MapAnnotations.Marker(kind, position, input.readUTF()));
            }
            if (input.read() != -1) throw new IOException("Unexpected data after map markers.");
            checkMarkers(markers);
            return List.copyOf(markers);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Invalid map marker data.", malformed);
        }
    }

    static void write(Path root, Scope scope, List<MapAnnotations.Marker> markers) throws IOException {
        checkMarkers(markers);
        Path file = file(root, scope);
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".soprano-markers-", ".tmp");
        try {
            try (var output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                output.writeInt(MAGIC);
                output.writeShort(markers.size());
                for (var marker : markers) {
                    output.writeUTF(marker.kind().name());
                    output.writeInt(marker.position().getX());
                    output.writeInt(marker.position().getY());
                    output.writeInt(marker.position().getZ());
                    output.writeUTF(marker.text());
                }
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void checkMarkers(List<MapAnnotations.Marker> markers) throws IOException {
        var notePositions = new HashSet<BlockPos>();
        var tracked = new HashSet<MapAnnotations.Kind>();
        if (markers.size() > MAX_MARKERS) throw new IOException("Too many map markers.");
        for (var marker : markers) {
            if (marker.kind() == MapAnnotations.Kind.PORTAL) throw new IOException("Portal markers use the portal index.");
            boolean unique = marker.kind() == MapAnnotations.Kind.NOTE ? notePositions.add(marker.position())
                : tracked.add(marker.kind());
            if (!unique) throw new IOException("Duplicate map marker.");
        }
        if (notePositions.size() > MAX_NOTES) throw new IOException("Too many map notes.");
    }

    private static Path file(Path root, Scope scope) {
        String key = scope.server() + "\n" + scope.world() + "\n" + scope.dimension();
        return MapSnapshotFiles.scope(root, key).resolve("markers.dat");
    }
}
