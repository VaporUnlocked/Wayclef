package dev.soprano.build;

import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/** Converts saved blueprint states to and from the client's registered states. */
public final class BlueprintBlockStates {
    private static final int LOWERCASE_STATE_TAGS = 5006;

    private BlueprintBlockStates() { }

    public static String blockId(String state) { return BuildPlan.blockId(state); }

    public static BlockState resolve(String state) {
        try {
            return resolve(SchematicReader.parseState(state));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static BlockState resolve(SchematicReader.BlockState specification) {
        Identifier id = Identifier.tryParse(specification.blockId());
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return null;
        Block block = BuiltInRegistries.BLOCK.getValue(id);
        BlockState state = block.defaultBlockState();
        for (var entry : specification.properties().entrySet()) {
            Property<?> property = block.getStateDefinition().getProperty(entry.getKey());
            if (property == null) return null;
            state = withValue(state, property, entry.getValue());
            if (state == null) return null;
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String name) {
        return property.getValue(name).map(value -> state.setValue(property, value)).orElse(null);
    }

    public static String encode(BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        Map<String, String> properties = new HashMap<>();
        for (Property<?> property : state.getProperties()) {
            properties.put(property.getName(), propertyValue(state, property));
        }
        return new SchematicReader.BlockState(id, properties).canonical();
    }

    private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    record Replacement(String state, List<String> attention) { }

    static Replacement replaceMaterial(String original, BlockState target) {
        var source = SchematicReader.parseState(original);
        BlockState knownSource = resolve(source);
        List<String> attention = new ArrayList<>();
        if (knownSource == null) attention.add("source state is unavailable");
        else source = SchematicReader.parseState(encode(knownSource));
        BlockState replacement = target;
        for (var entry : source.properties().entrySet()) {
            Property<?> property = target.getBlock().getStateDefinition().getProperty(entry.getKey());
            BlockState withProperty = property == null ? null : withValue(replacement, property, entry.getValue());
            if (withProperty == null) attention.add(entry.getKey() + "=" + entry.getValue());
            else replacement = withProperty;
        }
        return new Replacement(encode(replacement), List.copyOf(attention));
    }

    record Imported(String canonical, boolean available) { }

    static Imported migrate(SchematicReader.BlockState original, OptionalInt sourceVersion) {
        SchematicReader.BlockState specification = original;
        int currentVersion = SharedConstants.getCurrentVersion().dataVersion().version();
        if (sourceVersion.isPresent() && sourceVersion.getAsInt() < currentVersion
            && original.blockId().startsWith("minecraft:")) {
            int version = sourceVersion.getAsInt();
            boolean lowercase = version >= LOWERCASE_STATE_TAGS;
            CompoundTag state = new CompoundTag();
            state.putString(lowercase ? "id" : "Name", original.blockId());
            if (!original.properties().isEmpty()) {
                CompoundTag properties = new CompoundTag();
                original.properties().forEach(properties::putString);
                state.put(lowercase ? "properties" : "Properties", properties);
            }
            try {
                Dynamic<Tag> fixed = DataFixers.getDataFixer().update(References.BLOCK_STATE,
                    new Dynamic<>(NbtOps.INSTANCE, state), version, currentVersion);
                if (fixed.getValue() instanceof CompoundTag updated) {
                    CompoundTag properties = updated.getCompound("properties").orElseGet(() -> updated.getCompoundOrEmpty("Properties"));
                    Map<String, String> mapped = new HashMap<>();
                    for (String key : properties.keySet()) mapped.put(key, properties.getStringOr(key, ""));
                    String name = updated.getString("id").orElseGet(() -> updated.getStringOr("Name", original.blockId()));
                    specification = new SchematicReader.BlockState(name, mapped);
                }
            } catch (RuntimeException unavailable) {
                // Preserve the original state if a legacy fix cannot be applied.
            }
        }
        BlockState state = resolve(specification);
        if (state == null && specification != original) state = resolve(original);
        return state == null || state.isAir() && !original.isAir() ? new Imported(original.canonical(), false)
            : new Imported(encode(state), true);
    }
}
