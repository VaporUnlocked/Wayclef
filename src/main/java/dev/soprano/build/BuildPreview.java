package dev.soprano.build;

import dev.soprano.build.BuildPlan.Cell;
import dev.soprano.build.BuildPlan.Clipboard;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/** Immutable request whose bounded geometry can be built by the map renderer. */
public record BuildPreview(Kind kind, Cell first, Cell second, String material, Clipboard clipboard,
                           Map<Cell, String> replacements) {
    public enum Kind { BLOCK, LINE, BOX, SHELL, CYLINDER, TUBE, PASTE, REPLACEMENTS }

    public BuildPreview(Kind kind, Cell first, Cell second, String material, Clipboard clipboard) {
        this(kind, first, second, material, clipboard, Map.of());
    }

    public BuildPreview {
        Objects.requireNonNull(kind);
        if (kind != Kind.REPLACEMENTS) Objects.requireNonNull(first);
        switch (kind) {
            case BLOCK -> Objects.requireNonNull(material);
            case PASTE -> Objects.requireNonNull(clipboard);
            case REPLACEMENTS -> { }
            default -> {
                Objects.requireNonNull(second);
                Objects.requireNonNull(material);
            }
        }
        replacements = Map.copyOf(replacements);
    }

    public static BuildPreview block(Cell position, String material) {
        return new BuildPreview(Kind.BLOCK, position, null, material, null);
    }

    public static BuildPreview shape(Kind kind, Cell first, Cell second, String material) {
        if (kind == Kind.BLOCK || kind == Kind.PASTE || kind == Kind.REPLACEMENTS) {
            throw new IllegalArgumentException("Choose a shape tool.");
        }
        return new BuildPreview(kind, first, second, material, null);
    }

    public static BuildPreview paste(Cell origin, Clipboard clipboard) {
        return new BuildPreview(Kind.PASTE, origin, null, null, clipboard);
    }

    static BuildPreview replacements(Map<Cell, String> blocks) {
        return new BuildPreview(Kind.REPLACEMENTS, null, null, null, null, blocks);
    }

    public Map<Cell, String> blocks() {
        return Collections.unmodifiableMap(switch (kind) {
            case BLOCK -> Map.of(first, BuildPlan.normalizeBlockState(material));
            case LINE -> BuildPlan.lineBlocks(first, second, material);
            case BOX, SHELL -> BuildPlan.boxBlocks(first, second, material, kind == Kind.SHELL);
            case CYLINDER, TUBE -> BuildPlan.cylinderBlocks(first, second, material, kind == Kind.TUBE);
            case PASTE -> BuildPlan.pasteBlocks(first, clipboard);
            case REPLACEMENTS -> replacements;
        });
    }
}
