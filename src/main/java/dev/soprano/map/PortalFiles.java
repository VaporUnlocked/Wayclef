package dev.soprano.map;

import dev.soprano.map.PortalAperture.Kind;
import dev.soprano.map.PortalAperture.Portal;
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

/** Persistent portal observations for one server, world and dimension. */
final class PortalFiles {
    static final int MAX_PORTALS = 4096;
    private static final int MAGIC = 0x534f5001;
    private static final int MAX_BYTES = 6 + 13 * MAX_PORTALS;
    private static final Kind[] KINDS = Kind.values();

    private PortalFiles() { }

    static List<Portal> read(Path root, Scope scope) throws IOException {
        Path file = destination(root, scope);
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return List.of();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_BYTES)
            throw new IOException("Invalid portal observations file.");
        try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (input.readInt() != MAGIC) throw new IOException("Unsupported portal observations format.");
            int count = input.readUnsignedShort();
            if (count > MAX_PORTALS) throw new IOException("Too many saved portals.");
            var portals = new ArrayList<Portal>(count);
            var anchors = new HashSet<BlockPos>();
            for (int i = 0; i < count; i++) {
                int kind = input.readUnsignedByte();
                if (kind >= KINDS.length) throw new IOException("Unknown portal type.");
                var anchor = new BlockPos(input.readInt(), input.readInt(), input.readInt());
                if (!anchors.add(anchor)) throw new IOException("Duplicate portal position.");
                portals.add(new Portal(anchor, KINDS[kind]));
            }
            if (input.read() != -1) throw new IOException("Unexpected data after portal observations.");
            return List.copyOf(portals);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Invalid portal observation.", malformed);
        }
    }

    static void write(Path root, Scope scope, List<Portal> portals) throws IOException {
        if (portals.size() > MAX_PORTALS) throw new IOException("Too many saved portals.");
        Path file = destination(root, scope);
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".soprano-portals-", ".tmp");
        try {
            try (var output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                output.writeInt(MAGIC);
                output.writeShort(portals.size());
                for (Portal portal : portals) {
                    BlockPos at = portal.anchor();
                    output.writeByte(portal.kind().ordinal());
                    output.writeInt(at.getX());
                    output.writeInt(at.getY());
                    output.writeInt(at.getZ());
                }
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException fallback) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path destination(Path root, Scope scope) {
        String key = scope.server() + "\n" + scope.world() + "\n" + scope.dimension();
        return MapSnapshotFiles.scope(root, key).resolve("portals.dat");
    }
}
