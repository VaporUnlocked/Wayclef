package dev.soprano.map;

import dev.soprano.map.MapVolume.Box;
import dev.soprano.path.SafeReturnCheckpoints.Scope;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeMap;

/** Named A/B selections scoped to one server, world and dimension. */
public final class NamedRegions {
    private static final int MAGIC = 0x534f5231;
    private static final int MAX_REGIONS = 128;
    private static final int MAX_BYTES = 64 * 1024;

    public record Region(String name, Box box) {
        public Region {
            name = name == null ? "" : name.strip();
            if (name.isEmpty() || name.length() > 64 || name.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("A region needs a name of 1–64 characters.");
            }
            Objects.requireNonNull(box);
            checkCorner(box.x1(), box.y1(), box.z1());
            checkCorner(box.x2(), box.y2(), box.z2());
        }
    }

    private final Path root;
    private Scope scope;
    private TreeMap<String, Region> regions = new TreeMap<>();

    public NamedRegions(Path root) { this.root = root; }

    public List<Region> list() { return List.copyOf(regions.values()); }

    public void setScope(Scope next) {
        if (Objects.equals(scope, next)) return;
        scope = null;
        regions = new TreeMap<>();
        if (next == null) return;
        regions = read(file(next));
        scope = next;
    }

    public void save(Region region) {
        var updated = new TreeMap<>(regions);
        updated.put(key(region.name()), region);
        if (updated.size() > MAX_REGIONS) throw new IllegalArgumentException("A dimension can hold at most 128 named regions.");
        write(updated);
    }

    public void delete(Region region) {
        var updated = new TreeMap<>(regions);
        if (updated.remove(key(region.name())) != null) write(updated);
    }

    public static void checkCorner(int x, int y, int z) {
        if (x < -30_000_000 || x >= 30_000_000 || z < -30_000_000 || z >= 30_000_000
            || y < -4_096 || y > 4_096) throw new IllegalArgumentException("Selection coordinates are outside the supported range.");
    }

    private Path file(Scope scope) {
        return MapSnapshotFiles.scope(root, scope.server() + "\n" + scope.world() + "\n" + scope.dimension()).resolve("regions.dat");
    }

    private static TreeMap<String, Region> read(Path file) {
        var loaded = new TreeMap<String, Region>();
        try {
            if (Files.size(file) > MAX_BYTES) throw new IOException("Named region file is too large.");
            try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
                if (in.readInt() != MAGIC) throw new IOException("Unsupported named region format.");
                int count = in.readUnsignedShort();
                if (count > MAX_REGIONS) throw new IOException("Too many named regions.");
                for (int index = 0; index < count; index++) {
                    var region = new Region(in.readUTF(), new Box(in.readInt(), in.readInt(), in.readInt(),
                        in.readInt(), in.readInt(), in.readInt()));
                    if (loaded.putIfAbsent(key(region.name()), region) != null) throw new IOException("Duplicate region name.");
                }
                if (in.read() != -1) throw new IOException("Unexpected data after named regions.");
            }
        } catch (NoSuchFileException missing) {
            return loaded;
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalStateException("Could not read the named regions.", failure);
        }
        return loaded;
    }

    private void write(TreeMap<String, Region> updated) {
        if (scope == null) throw new IllegalStateException("Join the region's world first.");
        Path destination = file(scope);
        try {
            Files.createDirectories(destination.getParent());
            Path pending = Files.createTempFile(destination.getParent(), "regions-", ".tmp");
            try {
                try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(pending)))) {
                    out.writeInt(MAGIC);
                    out.writeShort(updated.size());
                    for (Region region : updated.values()) {
                        Box box = region.box();
                        out.writeUTF(region.name());
                        out.writeInt(box.x1()); out.writeInt(box.y1()); out.writeInt(box.z1());
                        out.writeInt(box.x2()); out.writeInt(box.y2()); out.writeInt(box.z2());
                    }
                }
                try {
                    Files.move(pending, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(pending, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(pending);
            }
            regions = updated;
        } catch (IOException failure) {
            throw new IllegalStateException("Could not save the named regions.", failure);
        }
    }

    private static String key(String name) { return name.toLowerCase(Locale.ROOT); }
}
