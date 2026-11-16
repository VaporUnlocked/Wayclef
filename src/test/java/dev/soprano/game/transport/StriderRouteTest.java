package dev.soprano.game.transport;

import dev.soprano.path.Goal;
import dev.soprano.path.IncrementalSearch;
import dev.soprano.path.Move;
import dev.soprano.path.Node;
import dev.soprano.path.RouteExclusions;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class StriderRouteTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var world = new TestWorld();
        for (int x = 0; x <= 1; x++) world.put(x, 0, 0, Blocks.LAVA.defaultBlockState());
        for (int x = 2; x <= 4; x++) {
            for (int z = -1; z <= 1; z++) world.put(x, 0, z, Blocks.STONE.defaultBlockState());
        }
        var exclusions = new AtomicReference<>(new RouteExclusions(List.of()));
        var terrain = new StriderRoute.LavaTerrain(world, (x, z) -> x == 0, null, 0, .46, 3.4, .6, exclusions::get);
        var start = new Node(0, 0, 0);
        var second = new Node(1, 0, 0);
        var landing = new Node(3, 0, 0);
        var config = new IncrementalSearch.Config(false, false, 0, 8, 200, false);
        var goal = new Goal(landing.x(), 0, landing.z(), 0);
        var route = new IncrementalSearch(terrain, start, goal, config).advance(200);
        assert route.status() == IncrementalSearch.Status.FOUND && route.path().size() == 3 : route;
        assert !terrain.canFinish(second) && terrain.canFinish(landing);
        world.put(16, 0, 0, Blocks.LAVA.defaultBlockState());
        assert !terrain.floors(16, 0, 0, 0).contains(new Node(16, 0, 0));
        world.put(1, 4, 0, Blocks.STONE.defaultBlockState());
        assert !terrain.canTraverse(start, second, Move.WALK);
        world.put(1, 4, 0, Blocks.AIR.defaultBlockState());
        world.put(1, 0, 0, Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL, 1));
        assert terrain.floors(1, 0, 0, 0).isEmpty();
        world.put(1, 0, 0, Blocks.LAVA.defaultBlockState());
        world.put(4, 0, 1, Blocks.AIR.defaultBlockState());
        assert !terrain.canFinish(landing);
        world.put(4, 0, 1, Blocks.STONE.defaultBlockState());
        exclusions.set(new RouteExclusions(List.of(new RouteExclusions.Exclusion(RouteExclusions.Kind.NO_GO_ZONE,
            "lava lane", RouteExclusions.Bounds.blocksInclusive(1, 0, 0, 1, 0, 0)))));
        assert new IncrementalSearch(terrain, start, goal, config).advance(200).status() == IncrementalSearch.Status.FAILED;
        System.out.println("Strider crossing geometry passed 7 checks.");
    }

    private static final class TestWorld implements CollisionGetter {
        private final Map<BlockPos, BlockState> blocks = new HashMap<>();
        private final WorldBorder border = new WorldBorder();

        void put(int x, int y, int z, BlockState state) { blocks.put(new BlockPos(x, y, z), state); }

        @Override public int getMinY() { return -64; }
        @Override public int getHeight() { return 384; }
        @Override public WorldBorder getWorldBorder() { return border; }
        @Override public BlockGetter getChunkForCollisions(int x, int z) { return this; }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) { return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public List<VoxelShape> getEntityCollisions(Entity entity, AABB box) { return List.of(); }
    }
}
