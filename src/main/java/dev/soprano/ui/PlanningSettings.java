package dev.soprano.ui;

import dev.soprano.SopranoClient;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/** Client preferences shared by the standalone and Meteor planners. */
public final class PlanningSettings {
    public record Control(String name, String description, Supplier<String> value, Runnable decrease, Runnable increase) { }

    static final class Store {
        private final Path file;
        private final Map<String, Value<?>> values = new LinkedHashMap<>();
        private Map<String, String> global = new LinkedHashMap<>();
        private Map<String, String> inherited = Map.of();
        private Map<String, String> scoped = Map.of();
        private Consumer<Map<String, String>> scopeWriter;
        private boolean editingScope;

        Store(Path file) {
            this.file = file;
            if (!Files.isRegularFile(file)) return;
            Properties properties = new Properties();
            try (var input = Files.newInputStream(file)) {
                properties.load(input);
                for (String key : properties.stringPropertyNames()) global.put(key, properties.getProperty(key));
            } catch (IOException | IllegalArgumentException problem) {
                SopranoClient.LOG.warn("Could not open Soprano preferences", problem);
            }
        }

        Value<Boolean> toggle(String name, String description, boolean initial) {
            return register(new Value<>(this, name, description, initial, value -> switch (value.toLowerCase(Locale.ROOT)) {
                case "true" -> true;
                case "false" -> false;
                default -> throw new IllegalArgumentException("Invalid boolean preference");
            }));
        }

        <T> Value<T> parsed(String name, String description, T initial, Function<String, T> parse) {
            return register(new Value<>(this, name, description, initial, parse));
        }

        Number number(String name, String description, int initial, int min, int max) {
            return register(new Number(this, name, description, initial, min, max));
        }

        <T extends Enum<T>> Value<T> choice(String name, String description, T initial) {
            return register(new Value<>(this, name, description, initial, text -> {
                for (T item : initial.getDeclaringClass().getEnumConstants()) {
                    if (item.name().equalsIgnoreCase(text) || item.toString().equalsIgnoreCase(text)) return item;
                }
                throw new IllegalArgumentException("Unknown preference choice");
            }));
        }

        private <T extends Value<?>> T register(T value) {
            values.put(value.name, value);
            return value;
        }

        void openScope(Map<String, String> overrides, Consumer<Map<String, String>> writer) {
            openScope(Map.of(), overrides, writer);
        }

        void openScope(Map<String, String> parent, Map<String, String> overrides, Consumer<Map<String, String>> writer) {
            inherited = validated(parent, values.keySet());
            scoped = validated(overrides, values.keySet());
            scopeWriter = writer;
            if (writer == null) editingScope = false;
        }

        boolean hasScope() { return scopeWriter != null; }
        boolean editingScope() { return editingScope; }
        void editScope(boolean enabled) { editingScope = enabled && hasScope(); }

        Map<String, String> snapshot(Set<String> keys) {
            Map<String, String> result = new LinkedHashMap<>();
            for (String key : keys) {
                Value<?> value = values.get(key);
                if (value != null) result.put(key, value.get().toString());
            }
            return Map.copyOf(result);
        }

        void apply(Map<String, String> incoming, Set<String> allowed) {
            Map<String, String> checked = validated(incoming, allowed);
            if (editingScope) {
                Map<String, String> updated = new LinkedHashMap<>(scoped);
                for (var entry : checked.entrySet()) {
                    Value<?> setting = values.get(entry.getKey());
                    if (setting.inherited().toString().equals(entry.getValue())) updated.remove(entry.getKey());
                    else updated.put(entry.getKey(), entry.getValue());
                }
                scopeWriter.accept(updated);
                scoped = Map.copyOf(updated);
            } else {
                applyGlobal(checked);
            }
        }

        private void applyGlobal(Map<String, String> checked) {
            Map<String, String> updated = new LinkedHashMap<>(global);
            updated.putAll(checked);
            write(updated);
            global = updated;
        }

        void importLegacy(Map<String, String> incoming) {
            if (Files.exists(file)) return;
            Map<String, String> checked = validated(incoming, values.keySet());
            if (checked.isEmpty()) return;
            write(checked);
            global = new LinkedHashMap<>(checked);
        }

        Map<String, String> validated(Map<String, String> incoming, Set<String> allowed) {
            Map<String, String> result = new LinkedHashMap<>();
            for (var entry : incoming.entrySet()) {
                Value<?> value = values.get(entry.getKey());
                if (value == null || !allowed.contains(entry.getKey())) continue;
                try {
                    result.put(entry.getKey(), value.parse.apply(entry.getValue()).toString());
                } catch (IllegalArgumentException invalid) {
                    SopranoClient.LOG.debug("Ignoring invalid Soprano preference {}", entry.getKey());
                }
            }
            return result;
        }

        private void write(Map<String, String> updated) {
            try {
                Files.createDirectories(file.getParent());
                Path scratch = Files.createTempFile(file.getParent(), "soprano-settings-", ".tmp");
                try {
                    Properties properties = new Properties();
                    properties.putAll(updated);
                    try (var output = Files.newOutputStream(scratch)) { properties.store(output, "Soprano client preferences"); }
                    try {
                        Files.move(scratch, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException unsupported) {
                        Files.move(scratch, file, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(scratch);
                }
            } catch (IOException problem) {
                throw new IllegalStateException("Could not save Wayclef preferences.", problem);
            }
        }
    }

    static class Value<T> {
        final Store store;
        final String name, title, description;
        final T initial;
        final Function<String, T> parse;

        Value(Store store, String name, String description, T initial, Function<String, T> parse) {
            this.store = store;
            this.name = name;
            this.title = Character.toUpperCase(name.charAt(0)) + name.substring(1).replace('-', ' ');
            this.description = description;
            this.initial = initial;
            this.parse = parse;
        }

        T get() { return read(store.scoped.get(name), inherited()); }
        T editable() { return store.editingScope ? get() : global(); }
        T inherited() { return read(store.inherited.get(name), global()); }
        T global() { return read(store.global.get(name), initial); }
        void set(T next) { store.apply(Map.of(name, next.toString()), Set.of(name)); }
        void setGlobal(T next) { store.applyGlobal(Map.of(name, parse.apply(next.toString()).toString())); }

        private T read(String text, T fallback) {
            if (text == null) return fallback;
            try { return parse.apply(text); }
            catch (IllegalArgumentException invalid) { return fallback; }
        }
    }

    static final class Number extends Value<Integer> {
        final int min, max;

        Number(Store store, String name, String description, int initial, int min, int max) {
            super(store, name, description, initial, value -> Math.clamp(Integer.parseInt(value), min, max));
            this.min = min;
            this.max = max;
        }
    }

    private PlanningSettings() { }

    static Control toggle(Value<Boolean> setting) {
        Runnable toggle = () -> setting.set(!setting.editable());
        return new Control(setting.title, setting.description, () -> onOff(setting.editable()), toggle, toggle);
    }

    static Control number(Number setting, int step) { return number(setting, step, Integer::toString); }

    static Control number(Number setting, int step, IntFunction<String> format) {
        Runnable decrease = setting.editable() > setting.min ? () -> setting.set(Math.max(setting.min, setting.editable() - step)) : null;
        Runnable increase = setting.editable() < setting.max ? () -> setting.set(Math.min(setting.max, setting.editable() + step)) : null;
        return new Control(setting.title, setting.description, () -> format.apply(setting.editable()), decrease, increase);
    }

    static <T extends Enum<T>> Control cycle(Value<T> setting, T[] values) {
        return new Control(setting.title, setting.description, () -> enumLabel(setting.editable().name()),
            () -> cycle(setting, values, -1), () -> cycle(setting, values, 1));
    }

    private static <T extends Enum<T>> void cycle(Value<T> setting, T[] values, int offset) {
        setting.set(values[Math.floorMod(setting.editable().ordinal() + offset, values.length)]);
    }

    static Control readOnly(Value<?> setting, String value) { return new Control(setting.title, setting.description, () -> value, null, null); }

    private static String enumLabel(String name) {
        if (!name.equals(name.toUpperCase(Locale.ROOT))) return name;
        return name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    static String onOff(boolean enabled) { return enabled ? "On" : "Off"; }
}
