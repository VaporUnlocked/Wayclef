package dev.soprano.game;

import dev.soprano.path.RouteExclusions;
import dev.soprano.path.RouteExclusions.Bounds;
import dev.soprano.path.RouteExclusions.Exclusion;
import dev.soprano.path.SafeReturnCheckpoints.Scope;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** Bounded, atomic storage for one server, save and dimension at a time. */
final class RouteZones {
    static final int MAX_ZONES = 128;
    private static final int MAGIC = 0x535A4E31;
    private static final long MAX_BYTES = 128 * 1024;
    private final Path root;

    RouteZones(Path root) { this.root = root; }

    static String name(String name) {
        String value = name.strip();
        if (value.isEmpty() || value.length() > 64 || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("A no-go zone needs a name of 1-64 characters.");
        }
        return value;
    }

    List<Exclusion> read(Scope scope) throws IOException {
        Path file = file(scope);
        try {
            if (Files.size(file) > MAX_BYTES) throw new IOException("The no-go zone file is too large.");
            try (var in = new DataInputStream(Files.newInputStream(file))) {
                if (in.readInt() != MAGIC || !in.readUTF().equals(scope.server()) || !in.readUTF().equals(scope.world())
                    || !in.readUTF().equals(scope.dimension())) throw new IOException("The no-go zone file has a different world scope.");
                int count = in.readInt();
                if (count < 0 || count > MAX_ZONES) throw new IOException("The no-go zone count is invalid.");
                var result = new ArrayList<Exclusion>(count);
                var names = new HashSet<String>();
                for (int index = 0; index < count; index++) {
                    String label = name(in.readUTF());
                    if (!names.add(label.toLowerCase(Locale.ROOT))) throw new IOException("Duplicate no-go zone name.");
                    Bounds bounds = new Bounds(in.readDouble(), in.readDouble(), in.readDouble(),
                        in.readDouble(), in.readDouble(), in.readDouble());
                    result.add(new Exclusion(RouteExclusions.Kind.NO_GO_ZONE, label, bounds));
                }
                if (in.read() != -1) throw new IOException("Unexpected data in the no-go zone file.");
                return List.copyOf(result);
            }
        } catch (NoSuchFileException missing) {
            return List.of();
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid no-go zone data.", invalid);
        }
    }

    void write(Scope scope, List<Exclusion> zones) throws IOException {
        if (zones.size() > MAX_ZONES) throw new IllegalArgumentException("A dimension can hold at most 128 no-go zones.");
        Files.createDirectories(root);
        Path pending = Files.createTempFile(root, "no-go-", ".tmp");
        try {
            try (var out = new DataOutputStream(Files.newOutputStream(pending))) {
                out.writeInt(MAGIC);
                out.writeUTF(scope.server());
                out.writeUTF(scope.world());
                out.writeUTF(scope.dimension());
                out.writeInt(zones.size());
                for (Exclusion zone : zones) {
                    Bounds b = zone.bounds();
                    out.writeUTF(zone.name());
                    out.writeDouble(b.minX()); out.writeDouble(b.minY()); out.writeDouble(b.minZ());
                    out.writeDouble(b.maxX()); out.writeDouble(b.maxY()); out.writeDouble(b.maxZ());
                }
            }
            Files.move(pending, file(scope), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    private Path file(Scope scope) {
        String key = scope.server() + "\0" + scope.world() + "\0" + scope.dimension();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return root.resolve(HexFormat.of().formatHex(digest) + ".zones");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }
}
