package dev.soprano.game;

import dev.soprano.path.Goal;
import dev.soprano.path.IncrementalSearch;
import dev.soprano.path.Node;
import dev.soprano.path.Step;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.phys.AABB;

import java.util.List;

/** Proves an escape route and dependent-block survival with the owned scaffold removed. */
final class ScaffoldExit {
    private final ConstructionTerrain view;
    private final MinecraftTerrain terrain;
    private final LocalPlayer player;
    private final BlockPos scaffold;
    private final Node home;
    private final Node start;
    private final IncrementalSearch search;
    private List<Step> path = List.of();

    ScaffoldExit(ClientLevel level, LocalPlayer player, BlockPos scaffold, Node home) {
        this.player = player;
        this.scaffold = scaffold.immutable();
        this.home = home;
        view = new ConstructionTerrain(level, this.scaffold, Blocks.AIR.defaultBlockState());
        var standing = player.getDimensions(Pose.STANDING);
        terrain = new MinecraftTerrain(view, player, view::hasChunk,
            Math.max(standing.width(), player.getBbWidth()) / 2,
            Math.max(standing.height(), player.getBbHeight()), () -> Navigation.activeExclusions(false));
        start = terrain.startAtPlayer();
        search = start == null || !stableNeighbors() ? null
            : new IncrementalSearch(terrain, start, new Goal(home.x(), home.y16(), home.z(), 0), exitConfig(start, home));
    }

    private static IncrementalSearch.Config exitConfig(Node start, Node home) {
        int distance = Math.max(Math.abs(start.x() - home.x()), Math.abs(start.z() - home.z()));
        return new IncrementalSearch.Config(false, true, 1, Math.clamp(distance + 24, 96, 4_096), 8_192, false);
    }

    IncrementalSearch.Status advance() {
        if (search == null) return IncrementalSearch.Status.FAILED;
        IncrementalSearch.Result result = search.advance(96, 1_500_000);
        if (result.status() == IncrementalSearch.Status.FOUND) path = result.path();
        return result.status();
    }

    boolean remainsSafe() {
        if (start == null || !player.onGround() || !stableNeighbors() || !terrain.canApproach(start)
            || !terrain.canFinish(home)
            || player.getBoundingBox().expandTowards(0, -.12, 0).intersects(new AABB(scaffold))) return false;
        Node previous = start;
        terrain.beginSearchSlice();
        try {
            for (Step step : path) {
                if (!previous.equals(step.from()) || !terrain.canTraverse(previous, step.to(), step.move())) return false;
                previous = step.to();
            }
        } finally {
            terrain.endSearchSlice();
        }
        return previous.equals(home);
    }

    private boolean stableNeighbors() {
        for (Direction side : Direction.values()) {
            BlockPos adjacent = scaffold.relative(side);
            if (view.level().isOutsideBuildHeight(adjacent)) continue;
            if (!ConstructionPlacement.loaded(view.level(), adjacent)) return false;
            var state = view.getBlockState(adjacent);
            if (!state.getFluidState().isEmpty()
                || side == Direction.UP && state.getBlock() instanceof FallingBlock
                || !state.canSurvive(view, adjacent)) return false;
        }
        return true;
    }
}
