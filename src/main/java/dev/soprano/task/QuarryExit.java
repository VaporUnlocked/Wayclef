package dev.soprano.task;

import dev.soprano.game.MinecraftTerrain;
import dev.soprano.path.Goal;
import dev.soprano.path.IncrementalSearch;
import dev.soprano.path.Move;
import dev.soprano.path.Node;
import dev.soprano.path.Step;
import dev.soprano.path.Terrain;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.List;

/** Finds a path out without relying on the block about to be removed. */
final class QuarryExit {
    private static final IncrementalSearch.Config CONFIG = new IncrementalSearch.Config(
        true, true, 1, 128, 8_000, false, IncrementalSearch.SearchMode.QUICK);

    private final MinecraftTerrain terrain;
    private final Terrain conservative;
    private final BlockPos removing;
    private final Node start;
    private final Node exit;
    private final IncrementalSearch search;

    QuarryExit(ClientLevel level, LocalPlayer player, BlockPos removing, Node exit) {
        this.removing = removing;
        this.exit = exit;
        terrain = new MinecraftTerrain(level, player);
        start = terrain.startAtPlayer();
        conservative = new Terrain() {
            @Override public List<Node> floors(int x, int z, int minY16, int maxY16) {
                return terrain.floors(x, z, minY16, maxY16).stream().filter(node -> !JobQueue.supportAt(node, removing)).toList();
            }

            @Override public boolean canTraverse(Node from, Node to, Move move) {
                if (couldUseSupport(from, to)) return false;
                return terrain.canTraverse(from, to, move);
            }

            @Override public void beginSearchSlice() { terrain.beginSearchSlice(); }
            @Override public void endSearchSlice() { terrain.endSearchSlice(); }
            @Override public boolean canFinish(Node node) { return !JobQueue.supportAt(node, removing) && terrain.canFinish(node); }
            @Override public boolean supportsExtendedSteps() { return terrain.supportsExtendedSteps(); }
        };
        search = start == null ? null : new IncrementalSearch(conservative, start, new Goal(exit.x(), exit.y16(), exit.z(), 0), CONFIG);
    }

    IncrementalSearch.Result advance() {
        if (search == null) return new IncrementalSearch.Result(IncrementalSearch.Status.FAILED, List.of(), 0, "The work stance changed.");
        return search.advance(64, 2_000_000);
    }

    boolean confirm(IncrementalSearch.Result result) {
        if (!terrain.canFinish(exit) || !start.equals(terrain.startAtPlayer())) return false;
        Node previous = start;
        for (Step step : result.path()) {
            if (!step.from().equals(previous) || !conservative.canTraverse(step.from(), step.to(), step.move())) return false;
            previous = step.to();
        }
        return previous.equals(exit);
    }

    private boolean couldUseSupport(Node from, Node to) {
        if (Math.max(from.y(), to.y()) < removing.getY() + .01
            || Math.min(from.y(), to.y()) > removing.getY() + 1.25) return false;
        return removing.getX() >= Math.min(from.x(), to.x()) && removing.getX() <= Math.max(from.x(), to.x())
            && removing.getZ() >= Math.min(from.z(), to.z()) && removing.getZ() <= Math.max(from.z(), to.z());
    }
}
