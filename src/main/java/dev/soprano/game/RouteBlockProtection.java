package dev.soprano.game;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Block selectors that route edits cannot mine. Tag membership comes from the live block state. */
public final class RouteBlockProtection {
    public static final RouteBlockProtection NONE = parse("");
    public static final int MAX_INPUT_LENGTH = 2048;
    private final Set<Identifier> blocks;
    private final List<TagKey<Block>> tags;
    private final String text;

    private RouteBlockProtection(Set<Identifier> blocks, List<TagKey<Block>> tags, String text) {
        this.blocks = Set.copyOf(blocks);
        this.tags = List.copyOf(tags);
        this.text = text;
    }

    public static RouteBlockProtection parse(String text) {
        if (text.length() > MAX_INPUT_LENGTH) throw new IllegalArgumentException("The protected block list is too long.");
        Set<Identifier> blocks = new LinkedHashSet<>();
        Set<TagKey<Block>> tags = new LinkedHashSet<>();
        Set<String> selectors = new LinkedHashSet<>();
        for (String token : text.strip().split("[,\\s]+")) {
            if (token.isEmpty()) continue;
            boolean isTag = token.startsWith("#");
            String name = isTag ? token.substring(1) : token;
            Identifier id = name.isEmpty() ? null : Identifier.tryParse(name);
            if (id == null || id.getPath().isEmpty()) {
                throw new IllegalArgumentException("Invalid block ID or tag: " + token);
            }
            if (isTag) tags.add(TagKey.create(Registries.BLOCK, id));
            else blocks.add(id);
            selectors.add((isTag ? "#" : "") + id);
        }
        String canonical = String.join(", ", selectors);
        if (canonical.length() > MAX_INPUT_LENGTH) throw new IllegalArgumentException("The protected block list is too long.");
        return new RouteBlockProtection(blocks, List.copyOf(tags), canonical);
    }

    public boolean contains(BlockState state) {
        if (!blocks.isEmpty() && blocks.contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()))) return true;
        for (TagKey<Block> tag : tags) {
            if (state.is(tag)) return true;
        }
        return false;
    }

    @Override
    public String toString() { return text; }

    @Override
    public boolean equals(Object other) {
        return other instanceof RouteBlockProtection rules && text.equals(rules.text);
    }

    @Override
    public int hashCode() { return text.hashCode(); }
}
