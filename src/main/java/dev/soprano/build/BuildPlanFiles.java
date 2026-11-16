package dev.soprano.build;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Reads and writes named Soprano blueprints as bounded, versioned UTF-8 text. */
public final class BuildPlanFiles {
    private static final String EXTENSION = ".sopplan";
    private static final String LEGACY_HEADER = "SOPRANO-PLAN 1";
    private static final String HEADER = "SOPRANO-PLAN 2";
    private static final int MAX_LINE = BuildPlan.MAX_BLOCK_STATE_LENGTH + 32;
    private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 _-]{0,47}");
    private static final Pattern WINDOWS_DEVICE = Pattern.compile("CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]");
    private static final Comparator<BuildPlan.Cell> CELL_ORDER = Comparator.comparingInt(BuildPlan.Cell::x)
        .thenComparingInt(BuildPlan.Cell::y).thenComparingInt(BuildPlan.Cell::z);

    private BuildPlanFiles() { }

    public static void save(Path directory, String name, Map<BuildPlan.Cell, String> blocks) throws IOException {
        Path destination = file(directory, name);
        if (blocks.size() > BuildPlan.MAX_BLOCKS) throw new IllegalArgumentException("Blueprint has too many blocks.");
        Map<BuildPlan.Cell, String> ordered = new TreeMap<>(CELL_ORDER);
        boolean containsStates = false;
        long bytes = 32;
        for (var entry : blocks.entrySet()) {
            String state = BuildPlan.normalizeBlockState(entry.getValue());
            containsStates |= state.indexOf('[') >= 0;
            ordered.put(Objects.requireNonNull(entry.getKey()), state);
            bytes += state.length() + 32;
        }
        if (bytes > MAX_FILE_BYTES) throw new IOException("Blueprint file exceeds the size limit.");

        Path parent = destination.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".soprano-plan-", ".tmp");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                writer.write(containsStates ? HEADER : LEGACY_HEADER);
                writer.newLine();
                for (var entry : ordered.entrySet()) {
                    BuildPlan.Cell cell = entry.getKey();
                    writer.write(cell.x() + " " + cell.y() + " " + cell.z() + " " + entry.getValue());
                    writer.newLine();
                }
            }

            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static Map<BuildPlan.Cell, String> load(Path directory, String name) throws IOException {
        Path source = file(directory, name);
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Blueprint file was not found.");
        if (Files.size(source) > MAX_FILE_BYTES) throw new IOException("Blueprint file exceeds the size limit.");

        Map<BuildPlan.Cell, String> blocks = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            boolean legacy = LEGACY_HEADER.equals(header);
            if (!legacy && !HEADER.equals(header)) throw new IOException("Blueprint format or version is unsupported.");
            String line;
            for (int row = 2; (line = reader.readLine()) != null; row++) {
                if (blocks.size() >= BuildPlan.MAX_BLOCKS) throw new IOException("Blueprint has too many blocks.");
                if (line.length() > MAX_LINE) throw new IOException("Blueprint entry is too long at line " + row + ".");
                String[] fields = line.split(" ", -1);
                if (fields.length != 4) throw new IOException("Invalid blueprint entry at line " + row + ".");
                try {
                    BuildPlan.Cell cell = new BuildPlan.Cell(Integer.parseInt(fields[0]), Integer.parseInt(fields[1]),
                        Integer.parseInt(fields[2]));
                    String state = legacy ? BuildPlan.normalizeBlockId(fields[3]) : BuildPlan.normalizeBlockState(fields[3]);
                    if (blocks.putIfAbsent(cell, state) != null)
                        throw new IOException("Duplicate blueprint position at line " + row + ".");
                } catch (IllegalArgumentException invalid) {
                    throw new IOException("Invalid blueprint block at line " + row + ".", invalid);
                }
            }
        }
        return Map.copyOf(blocks);
    }

    private static Path file(Path directory, String name) {
        String clean = Objects.requireNonNull(name, "blueprint name").strip();
        if (!NAME.matcher(clean).matches() || WINDOWS_DEVICE.matcher(clean.toUpperCase(Locale.ROOT)).matches())
            throw new IllegalArgumentException("Use 1–48 letters, numbers, spaces, underscores or hyphens for a blueprint name.");
        return directory.toAbsolutePath().normalize().resolve(clean + EXTENSION);
    }
}
