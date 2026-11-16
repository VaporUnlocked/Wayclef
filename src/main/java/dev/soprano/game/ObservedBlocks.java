package dev.soprano.game;

import dev.soprano.path.Goal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded records of blocks the crosshair or a live map pick actually observed. */
final class ObservedBlocks {
    private static final int MAX_WORLDS = 8;
    private static final int MAX_POSITIONS = 4_096;

    private final Map<Object, LinkedHashMap<BlockPos, Identifier>> worlds = new LinkedHashMap<>(8, .75f, true);

    void observeCrosshair(Minecraft mc) {
        if (mc.gui.screen() == null && mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            observe(mc, hit.getBlockPos());
        }
    }

    void observe(Minecraft mc, BlockPos position) {
        ClientLevel level = mc.level;
        if (level == null || !loaded(level, position)) return;
        var state = level.getBlockState(position);
        var seen = current(level);
        if (state.isAir()) {
            seen.remove(position);
            return;
        }
        seen.put(position.immutable(), BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        if (seen.size() > MAX_POSITIONS) seen.pollFirstEntry();
    }

    Goal nearest(Minecraft mc, String blockName) {
        if (mc.level == null || mc.player == null) throw new IllegalStateException("Join a world first.");
        Identifier id = Identifier.tryParse(blockName.strip());
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) throw new IllegalArgumentException("Enter a registered block ID.");
        observeCrosshair(mc);
        var seen = current(mc.level);
        BlockPos nearest = null;
        double distance = Double.POSITIVE_INFINITY;
        for (var entries = seen.entrySet().iterator(); entries.hasNext();) {
            var entry = entries.next();
            if (!id.equals(entry.getValue())) continue;
            BlockPos position = entry.getKey();
            if (loaded(mc.level, position) && !hasId(mc.level.getBlockState(position), id.toString())) {
                entries.remove();
                continue;
            }
            double candidate = mc.player.position().distanceToSqr(position.getX() + .5, position.getY() + .5, position.getZ() + .5);
            if (candidate < distance) {
                nearest = position;
                distance = candidate;
            }
        }
        if (nearest == null) throw new IllegalStateException("No " + id + " has been observed in this dimension. Aim at one or pick one on the live map first.");
        return Goal.observedBlock(nearest.getX(), nearest.getY(), nearest.getZ(), id.toString());
    }

    static boolean targetStillPossible(ClientLevel level, Goal goal) {
        if (goal.observedBlockId() == null) return true;
        var position = new BlockPos(goal.x(), goal.sightY(), goal.z());
        if (position.getY() < level.getMinY() || position.getY() > level.getMaxY()) return false;
        return !loaded(level, position) || hasId(level.getBlockState(position), goal.observedBlockId());
    }

    private LinkedHashMap<BlockPos, Identifier> current(ClientLevel level) {
        Object scope = Navigation.currentScope();
        if (scope == null) scope = level;
        var result = worlds.computeIfAbsent(scope, key -> new LinkedHashMap<>(64, .75f, true));
        if (worlds.size() > MAX_WORLDS) worlds.remove(worlds.keySet().iterator().next());
        return result;
    }

    private static boolean loaded(ClientLevel level, BlockPos position) {
        return position.getY() >= level.getMinY() && position.getY() <= level.getMaxY()
            && level.hasChunk(position.getX() >> 4, position.getZ() >> 4);
    }

    static boolean hasId(BlockState state, String id) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(id);
    }
}
