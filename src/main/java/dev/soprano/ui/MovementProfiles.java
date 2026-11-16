package dev.soprano.ui;

import dev.soprano.game.RouteBlockProtection;
import dev.soprano.path.SafeReturnCheckpoints.Scope;
import net.minecraft.client.Minecraft;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Local named movement presets and explicit defaults for a server and world. */
final class MovementProfiles {
    private static final int MAGIC = 0x534D5031;
    private static final int MAX_PRESETS = 64;
    private static final int MAX_SETTINGS = 64;
    private static final long MAX_FILE_BYTES = 1_048_576;

    private static final String CLIPBOARD_PREFIX = "SOPRANO-MOVEMENT-1:";
    private static final int MAX_CLIPBOARD_LENGTH = 64 * 1024;

    record Preset(String name, Map<String, String> settings) { }

    @FunctionalInterface
    private interface Read<T> { T read(DataInputStream in) throws IOException; }

    @FunctionalInterface
    private interface Write { void write(DataOutputStream out) throws IOException; }

    private final Path root;
    private TreeMap<String, Preset> presets;

    MovementProfiles(Path root) { this.root = root.toAbsolutePath().normalize(); }

    static MovementProfiles open() {
        Path path = Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize()
            .resolve("soprano").resolve("movement-profiles");
        return new MovementProfiles(path);
    }

    List<String> names() {
        return presets().values().stream().map(Preset::name).toList();
    }

    void savePreset(String name, Map<String, String> settings) {
        String label = presetName(name);
        var updated = new TreeMap<>(presets());
        updated.put(key(label), new Preset(label, checked(settings)));
        if (updated.size() > MAX_PRESETS) throw new IllegalArgumentException("At most 64 movement presets can be saved.");
        savePresets(updated);
    }

    Map<String, String> loadPreset(String name) {
        Preset preset = presets().get(key(presetName(name)));
        if (preset == null) throw new IllegalArgumentException("Movement preset not found.");
        return preset.settings();
    }


    static String encodePreset(String name, Map<String, String> settings) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                output.writeUTF(presetName(name));
                writeSettings(output, checked(settings));
            }
            return CLIPBOARD_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (IOException failure) {
            throw new IllegalStateException("Could not copy the movement preset.", failure);
        }
    }

    static Preset decodePreset(String clipboard) {
        if (clipboard == null || clipboard.length() > MAX_CLIPBOARD_LENGTH)
            throw new IllegalArgumentException("Paste a Wayclef movement preset.");
        String text = clipboard.strip();
        if (!text.startsWith(CLIPBOARD_PREFIX)) throw new IllegalArgumentException("Paste a Wayclef movement preset.");
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(text.substring(CLIPBOARD_PREFIX.length()));
            try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
                var result = new Preset(presetName(input.readUTF()), readSettings(input));
                if (input.read() != -1) throw new IOException("Unexpected clipboard data.");
                return result;
            }
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalArgumentException("The copied movement preset is invalid.", failure);
        }
    }

    boolean deletePreset(String name) {
        var updated = new TreeMap<>(presets());
        if (updated.remove(key(presetName(name))) == null) return false;
        savePresets(updated);
        return true;
    }

    Map<String, String> loadDefaults(Scope scope) {
        if (scope == null) return Map.of();
        return read(defaultsFile(scope), Map.of(), in -> {
            if (!in.readUTF().equals(scope.server()) || !in.readUTF().equals(scope.world())) {
                throw new IOException("The defaults belong to another server or world.");
            }
            return readSettings(in);
        });
    }

    void saveDefaults(Scope scope, Map<String, String> settings) {
        requireScope(scope);
        Map<String, String> values = checked(settings);
        if (values.isEmpty()) {
            clearDefaults(scope);
            return;
        }
        write(defaultsFile(scope), out -> {
            out.writeUTF(scope.server());
            out.writeUTF(scope.world());
            writeSettings(out, values);
        });
    }

    boolean clearDefaults(Scope scope) {
        requireScope(scope);
        try {
            return Files.deleteIfExists(defaultsFile(scope));
        } catch (IOException failure) {
            throw new IllegalStateException("Could not clear the movement defaults.", failure);
        }
    }

    private TreeMap<String, Preset> presets() {
        if (presets != null) return presets;
        presets = read(root.resolve("presets.dat"), new TreeMap<>(), in -> {
            int count = in.readInt();
            if (count < 0 || count > MAX_PRESETS) throw new IOException("Invalid movement preset count.");
            var loaded = new TreeMap<String, Preset>();
            for (int index = 0; index < count; index++) {
                String name = presetName(in.readUTF());
                var preset = new Preset(name, readSettings(in));
                if (loaded.putIfAbsent(key(name), preset) != null) throw new IOException("Duplicate movement preset name.");
            }
            return loaded;
        });
        return presets;
    }

    private void savePresets(TreeMap<String, Preset> updated) {
        write(root.resolve("presets.dat"), out -> {
            out.writeInt(updated.size());
            for (Preset preset : updated.values()) {
                out.writeUTF(preset.name());
                writeSettings(out, preset.settings());
            }
        });
        presets = updated;
    }

    private static Map<String, String> readSettings(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_SETTINGS) throw new IOException("Invalid movement settings count.");
        var values = new TreeMap<String, String>();
        for (int index = 0; index < count; index++) {
            if (values.putIfAbsent(in.readUTF(), in.readUTF()) != null) throw new IOException("Duplicate movement setting.");
        }
        return checked(values);
    }

    private static void writeSettings(DataOutputStream out, Map<String, String> settings) throws IOException {
        out.writeInt(settings.size());
        for (var setting : new TreeMap<>(settings).entrySet()) {
            out.writeUTF(setting.getKey());
            out.writeUTF(setting.getValue());
        }
    }

    private static Map<String, String> checked(Map<String, String> settings) {
        if (settings.size() > MAX_SETTINGS) throw new IllegalArgumentException("Too many movement settings.");
        for (var entry : settings.entrySet()) {
            String setting = entry.getKey(), value = entry.getValue();
            int maxLength = "protected-route-blocks".equals(setting) ? RouteBlockProtection.MAX_INPUT_LENGTH : 128;
            if (setting == null || !setting.matches("[a-z][a-z0-9-]{0,63}") || value == null || value.length() > maxLength
                || value.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Invalid movement setting.");
            }
        }
        return Map.copyOf(settings);
    }

    private static String presetName(String name) {
        String label = name == null ? "" : name.strip();
        if (label.isEmpty() || label.length() > 64 || label.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("A movement preset needs a name of 1–64 characters.");
        }
        return label;
    }

    private static String key(String label) { return label.toLowerCase(Locale.ROOT); }

    private static void requireScope(Scope scope) {
        if (scope == null) throw new IllegalStateException("Join a named world or server to save movement defaults.");
    }

    private Path defaultsFile(Scope scope) {
        String identity = scope.server().length() + ":" + scope.server() + scope.world();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            return root.resolve("defaults").resolve(HexFormat.of().formatHex(digest) + ".dat");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    private static <T> T read(Path file, T missing, Read<T> reader) {
        try {
            if (Files.size(file) > MAX_FILE_BYTES) throw new IOException("The movement profile file is too large.");
            try (var in = new DataInputStream(Files.newInputStream(file))) {
                if (in.readInt() != MAGIC) throw new IOException("Unknown movement profile format.");
                T value = reader.read(in);
                if (in.read() != -1) throw new IOException("Unexpected data in the movement profile file.");
                return value;
            }
        } catch (NoSuchFileException unavailable) {
            return missing;
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalStateException("Could not read the movement profiles.", failure);
        }
    }

    private static void write(Path file, Write writer) {
        try {
            Files.createDirectories(file.getParent());
            Path pending = Files.createTempFile(file.getParent(), "movement-", ".tmp");
            try {
                try (var out = new DataOutputStream(Files.newOutputStream(pending))) {
                    out.writeInt(MAGIC);
                    writer.write(out);
                }
                if (Files.size(pending) > MAX_FILE_BYTES) throw new IOException("The movement profile file is too large.");
                Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(pending);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Could not save the movement profiles.", failure);
        }
    }
}
