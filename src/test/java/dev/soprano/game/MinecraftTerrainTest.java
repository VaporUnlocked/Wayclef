package dev.soprano.game;

import dev.soprano.path.Goal;
import dev.soprano.path.IncrementalSearch;
import dev.soprano.path.Move;
import dev.soprano.path.Node;
import dev.soprano.path.RouteExclusions;
import dev.soprano.path.SafeReturnCheckpoints.Scope;
import dev.soprano.path.Step;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public final class MinecraftTerrainTest {
    private static int checks;

    public static void main(String[] args) throws IOException {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        slabsAndDecorativeBlocks();
        stairsWalkInBothDirections();
        verifiedWalkingTurns();
        descendingBetweenTreads();
        followsVerifiedGroundBelowMovingTargets();
        ladderRoute();
        bubbleElevatorRoute();
        verifiedOneBlockGap();
        geometryChangesBetweenSlices();
        permittedRouteEdits();
        doorProjectionKeepsCollisionChecks();
        woodenTrapdoorProjectionProtectsFooting();
        exclusionsAffectPlanningAndLiveSweeps();
        zonesAreScopedAndPersistent();
        System.out.println("Minecraft terrain passed " + checks + " checks.");
    }

    private static void slabsAndDecorativeBlocks() {
        TestWorld world = new TestWorld();
        world.put(0, 0, 0, Blocks.STONE.defaultBlockState());
        world.put(1, 1, 0, Blocks.STONE_SLAB.defaultBlockState());
        world.put(2, 0, 0, Blocks.STONE.defaultBlockState());
        world.put(2, 1, 0, Blocks.SHORT_GRASS.defaultBlockState());
        world.put(3, 0, 0, Blocks.STONE.defaultBlockState());
        world.put(3, 1, 0, Blocks.OAK_TRAPDOOR.defaultBlockState());
        MinecraftTerrain terrain = world.terrain();
        Node source = new Node(0, 16, 0), slab = new Node(1, 24, 0), grass = new Node(2, 16, 0);
        equal(terrain.standingNodeAt(new BlockPos(1, 1, 0)), slab, "a selected slab resolves to half-block feet height");
        equal(terrain.standingNodeAt(new BlockPos(2, 1, 0)), grass, "a selected plant resolves to footing below it");
        equal(terrain.standingNodeAt(new BlockPos(3, 1, 0)), new Node(3, 19, 0), "closed trapdoor adds three sixteenths");
        check(terrain.canTraverse(source, slab, Move.WALK), "walk up onto a bottom slab");
        check(terrain.canTraverse(slab, grass, Move.WALK), "walk down from a bottom slab");
        check(terrain.canTraverse(grass, new Node(3, 19, 0), Move.WALK), "walk onto a closed trapdoor");
        check(!terrain.canTraverse(grass, new Node(4, 16, 0), Move.WALK), "a missing surface cannot be walked");
        world.put(1, 3, 0, Blocks.STONE.defaultBlockState());
        check(!terrain.canTraverse(source, slab, Move.WALK), "a low ceiling blocks the step");
    }

    private static void stairsWalkInBothDirections() {
        TestWorld world = new TestWorld();
        world.put(0, 0, 0, Blocks.STONE.defaultBlockState());
        world.put(1, 1, 0, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
        world.put(2, 1, 0, Blocks.STONE.defaultBlockState());
        MinecraftTerrain terrain = world.terrain();
        Node source = new Node(0, 16, 0), stair = new Node(1, 32, 0);
        equal(terrain.standingNodeAt(new BlockPos(1, 1, 0)), stair, "a stair's high tread supports the center footprint");
        check(terrain.canTraverse(source, stair, Move.WALK), "two half steps between centers walk up");
        check(terrain.canTraverse(stair, source, Move.WALK), "two half steps between centers walk down");
        check(terrain.canApproachFrom(1.08, 1.5, .5, stair), "the lower tread can approach the center's upper footing");
        IncrementalSearch.Result route = route(terrain, source, new Node(2, 32, 0));
        equal(route.status(), IncrementalSearch.Status.FOUND, "a staircase works with jumping and drops disabled");
        check(route.path().stream().allMatch(step -> step.move() == Move.WALK), "the staircase route contains only walking");
        world.put(1, 1, 0, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST));
        check(!terrain.canTraverse(source, stair, Move.WALK), "the high side of a reverse stair is a full block");
    }

    private static void followsVerifiedGroundBelowMovingTargets() {
        var world = new TestWorld();
        world.put(0, 0, 0, Blocks.STONE.defaultBlockState());
        world.put(1, 1, 0, Blocks.STONE_SLAB.defaultBlockState());
        world.put(2, 1, 0, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
        MinecraftTerrain terrain = world.terrain();
        equal(terrain.followGroundBelow(.5, 2.25, .5), new Node(0, 16, 0),
            "a jump uses the floor beneath the target's feet");
        equal(terrain.followGroundBelow(1.5, 2.25, .5), new Node(1, 24, 0),
            "moving in the air updates the column and keeps a slab's exact standing height");
        equal(terrain.followGroundBelow(2.08, 1.5, .5), new Node(2, 32, 0),
            "a low stair tread resolves to the reachable standing center");
        world.put(0, 64, 0, Blocks.STONE.defaultBlockState());
        equal(terrain.followGroundBelow(.5, 66.25, .5), new Node(0, 1040, 0),
            "an upper platform takes precedence over a lower floor");
        world.put(0, 65, 0, Blocks.LAVA.defaultBlockState());
        equal(terrain.followGroundBelow(.5, 66.25, .5), null, "projection stops at lava rather than following into it");
        equal(terrain.followGroundBelow(3.5, 2.25, .5), null, "an empty column does not become a fabricated goal");
        var unloaded = new MinecraftTerrain(world, null, (x, z) -> false, .3, 1.8);
        equal(unloaded.followGroundBelow(1.5, 2.25, .5), null, "an unloaded column provides no projected route");
        Node targetFloor = new Node(1, 24, 0);
        equal(FollowController.closeEnoughToFloor(1.6, targetFloor), true, "follow holds near the same floor");
        equal(FollowController.closeEnoughToFloor(2, targetFloor), false, "follow keeps routing down a slab within the horizontal gap");
        equal(FollowController.closeEnoughToFloor(2.5, targetFloor), false, "follow does not hold one block above the target floor");
    }

    private static void ladderRoute() {
        TestWorld world = new TestWorld();
        BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH);
        for (int y = 1; y <= 3; y++) {
            world.put(0, y, 0, ladder);
            world.put(0, y, 1, Blocks.STONE.defaultBlockState());
        }
        world.put(1, 0, 0, Blocks.STONE.defaultBlockState());
        world.put(1, 3, 0, Blocks.STONE.defaultBlockState());
        MinecraftTerrain terrain = world.terrain();
        Node bottom = new Node(1, 16, 0), top = new Node(1, 64, 0);
        Node first = new Node(0, 16, 0), middle = new Node(0, 32, 0);
        check(terrain.climbNeighbors(bottom).contains(first), "a route can enter a ladder from the side");
        check(terrain.climbNeighbors(first).contains(middle), "consecutive rungs connect vertically");
        check(!terrain.canFinish(middle), "a suspended rung is an intermediate point");
        IncrementalSearch.Result ascent = route(terrain, bottom, top);
        equal(ascent.status(), IncrementalSearch.Status.FOUND, "the route can climb to the upper landing");
        check(ascent.path().stream().anyMatch(step -> step.move() == Move.CLIMB), "the route emits climb inputs");
        equal(route(terrain, top, bottom).status(), IncrementalSearch.Status.FOUND, "the ladder route descends to the lower landing");
        world.put(0, 2, 0, Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.OPEN, true)
            .setValue(TrapDoorBlock.FACING, Direction.NORTH));
        check(terrain.isClimbPoint(middle), "an aligned open trapdoor continues a ladder");
        world.put(0, 2, 0, Blocks.AIR.defaultBlockState());
        equal(route(terrain, bottom, top).status(), IncrementalSearch.Status.FAILED, "a missing rung breaks the vertical route");
    }

    private static void bubbleElevatorRoute() {
        var world = new TestWorld();
        var upperBank = new Node(1, 80, 0);
        var lowerBank = new Node(1, 16, 0);
        world.put(1, 0, 0, Blocks.STONE.defaultBlockState());
        world.put(1, 4, 0, Blocks.STONE.defaultBlockState());
        world.put(0, 0, 0, Blocks.SOUL_SAND.defaultBlockState());
        var up = Blocks.BUBBLE_COLUMN.defaultBlockState().setValue(BubbleColumnBlock.DRAG_DOWN, false);
        var down = up.setValue(BubbleColumnBlock.DRAG_DOWN, true);
        for (int y = 1; y <= 4; y++) world.put(0, y, 0, up);
        var terrain = world.terrain();
        var ascent = route(terrain, lowerBank, upperBank);
        equal(ascent.status(), IncrementalSearch.Status.FOUND, "a verified bubble lift connects the dry banks");
        check(ascent.path().size() == 6 && ascent.path().stream().allMatch(step -> step.move() == Move.BUBBLE),
            "a lift emits explicit entry, shaft, and exit edges");
        var bottom = new Node(0, 16, 0);
        check(!terrain.canFinish(bottom), "a point inside a bubble shaft is never a stopping position");
        check(!terrain.canTraverse(lowerBank, bottom, Move.WALK), "a bubble shaft remains unsafe for ordinary walking");
        equal(route(terrain, upperBank, lowerBank).status(), IncrementalSearch.Status.FAILED,
            "a rising shaft cannot be used in reverse");
        world.put(0, 3, 0, Blocks.WATER.defaultBlockState());
        equal(route(terrain, lowerBank, upperBank).status(), IncrementalSearch.Status.FAILED,
            "ordinary water interrupts a verified shaft");
        world.put(0, 0, 0, Blocks.MAGMA_BLOCK.defaultBlockState());
        for (int y = 1; y <= 4; y++) world.put(0, y, 0, down);
        equal(route(terrain, upperBank, lowerBank).status(), IncrementalSearch.Status.FOUND,
            "a downward shaft with a dry bottom bank connects in its own direction");
        world.put(1, 0, 0, Blocks.AIR.defaultBlockState());
        equal(route(terrain, upperBank, lowerBank).status(), IncrementalSearch.Status.FAILED,
            "a downward lift must have confirmed dry footing at the exit");
    }

    private static void verifiedWalkingTurns() {
        var world = new TestWorld();
        for (int x = 0; x <= 1; x++) for (int z = 0; z <= 1; z++) world.put(x, 0, z, Blocks.STONE.defaultBlockState());
        world.put(0, 1, 1, Blocks.STONE.defaultBlockState());
        var terrain = world.terrain();
        var first = new Node(0, 16, 0);
        var corner = new Node(1, 16, 0);
        var exit = new Node(1, 16, 1);
        var incoming = new Step(first, corner, Move.WALK);
        var outgoing = new Step(corner, exit, Move.WALK);
        Vec3 lookAhead = PathFollower.roundedWalkTarget(incoming, outgoing);
        check(lookAhead != null && lookAhead.x == 1.5 && lookAhead.z > .5 && lookAhead.z < 1.5,
            "a turn aims a short distance into the outgoing edge");
        var feet = new Vec3(1.10, 1, .5);
        check(terrain.canRoundWalk(feet, lookAhead), "walking through a clear corner preserves grounded footing");
        check(PathFollower.keepWalkingToTurn(terrain, incoming, outgoing, feet, new Vec3(.12, 0, 0), .1),
            "an approach that can safely reach a turn keeps moving instead of coasting to a stop");
        check(PathFollower.canContinueTurn(terrain, incoming, outgoing, new Vec3(1.31, 1, .5), new Vec3(.12, 0, 0), .1, .23),
            "a safe turn can start at walking speed without requiring a rounded shortcut");
        check(PathFollower.canContinueTurn(terrain, new Step(first, corner, Move.JUMP), outgoing,
            new Vec3(1.47, 1, .5), new Vec3(.12, 0, 0), .1, .31),
            "the outgoing walk is safe with momentum from a verified touchdown");
        world.put(2, 1, 0, Blocks.STONE.defaultBlockState());
        check(!PathFollower.canContinueTurn(terrain, incoming, outgoing, new Vec3(1.68, 1, .5), new Vec3(.26, 0, 0), .1, .23),
            "unsafe turn momentum still requires braking at a tight wall");
        world.put(2, 1, 0, Blocks.AIR.defaultBlockState());
        world.put(1, 1, 1, Blocks.STONE_SLAB.defaultBlockState());
        var raisedExit = new Step(corner, new Node(1, 24, 1), Move.WALK);
        check(PathFollower.canContinueTurn(terrain, incoming, raisedExit, new Vec3(1.48, 1, .5), new Vec3(.12, 0, 0), .1, .23),
            "a verified half-slab rise through a turn does not require stopping on the lower floor");
        world.put(1, 1, 1, Blocks.STONE.defaultBlockState());
        check(!terrain.canRoundWalk(feet, lookAhead), "a newly blocked bend cannot be rounded");
        check(!PathFollower.canContinueTurn(terrain, incoming, outgoing, new Vec3(1.48, 1, .5), new Vec3(.12, 0, 0), .1, .23),
            "a changed outgoing edge still blocks a continuous handoff");
        check(PathFollower.roundedWalkTarget(incoming, new Step(corner, first, Move.WALK)) == null,
            "a reversal retains a settled waypoint");
        check(PathFollower.roundedWalkTarget(incoming, new Step(corner, new Node(1, 8, 1), Move.WALK)) == null,
            "a turn cannot skip a descent on the next edge");

        var alternate = new TestWorld();
        alternate.put(0, 0, 0, Blocks.STONE.defaultBlockState());
        alternate.put(2, 0, 0, Blocks.STONE.defaultBlockState());
        check(!alternate.terrain().canRoundWalk(new Vec3(.8, 1, .5), new Vec3(2.2, 1, .5)),
            "a corner sweep refuses a gap even when both endpoints are supported");
        for (float yaw : new float[] {-170, -90, 0, 41, 179}) {
            var input = PathFollower.walkingDirection(1, 2, yaw);
            double angle = Math.toRadians(yaw);
            double wx = input.x * Math.cos(angle) - input.y * Math.sin(angle);
            double wz = input.x * Math.sin(angle) + input.y * Math.cos(angle);
            check(Math.abs(wx - 1 / Math.sqrt(5)) < 1e-6 && Math.abs(wz - 2 / Math.sqrt(5)) < 1e-6,
                "walking intent keeps its world heading while camera yaw changes");
        }
    }

    private static void descendingBetweenTreads() {
        var slab = new TestWorld();
        slab.put(0, 0, 0, Blocks.STONE.defaultBlockState());
        slab.put(0, 1, 0, Blocks.STONE_SLAB.defaultBlockState());
        slab.put(1, 0, 0, Blocks.STONE.defaultBlockState());
        var slabTerrain = slab.terrain();
        var lower = new Node(1, 16, 0);
        var falling = new Vec3(1.35, 1.32, .5);
        check(!slabTerrain.canApproachFrom(falling.x, falling.y, falling.z, lower), "airborne feet do not pass a normal grounded approach");
        check(slabTerrain.canFinishWalkingDescent(falling, lower), "a half-slab descent preserves its verified lower footing while falling");
        slab.put(1, 0, 0, Blocks.AIR.defaultBlockState());
        check(!slabTerrain.canFinishWalkingDescent(falling, lower), "removing the landing invalidates the descent");

        var stairs = new TestWorld();
        stairs.put(0, 0, 0, Blocks.STONE.defaultBlockState());
        stairs.put(1, 0, 0, Blocks.STONE.defaultBlockState());
        stairs.put(1, 1, 0, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
        var stairsTerrain = stairs.terrain();
        var betweenTreads = new Vec3(1.13, 1.72, .5);
        check(stairsTerrain.canFinishWalkingDescent(betweenTreads, new Node(0, 16, 0)),
            "the lower stair tread is revalidated before continuing down to the next block");
    }

    private static void geometryChangesBetweenSlices() {
        TestWorld world = new TestWorld();
        world.put(0, 0, 0, Blocks.STONE.defaultBlockState());
        world.put(1, 0, 0, Blocks.STONE.defaultBlockState());
        MinecraftTerrain terrain = world.terrain();
        Node first = new Node(0, 16, 0), second = new Node(1, 16, 0);
        terrain.beginSearchSlice();
        check(terrain.canTraverse(first, second, Move.WALK), "the first slice finds the floor");
        terrain.endSearchSlice();
        world.put(1, 0, 0, Blocks.AIR.defaultBlockState());
        terrain.beginSearchSlice();
        check(!terrain.canTraverse(first, second, Move.WALK), "the next slice sees the removed floor");
        terrain.endSearchSlice();
        check(!terrain.canTraverse(first, second, Move.WALK), "the movement follower sees live geometry");
    }

    private static void verifiedOneBlockGap() {
        TestWorld world = new TestWorld();
        world.put(0, 0, 0, Blocks.STONE.defaultBlockState());
        world.put(2, 0, 0, Blocks.STONE.defaultBlockState());
        Node from = new Node(0, 16, 0), to = new Node(2, 16, 0);
        var goal = new Goal(to.x(), to.y16(), to.z(), 0);
        var base = new IncrementalSearch.Config(false, true, 0, 8, 200, false);
        MinecraftTerrain terrain = world.terrain();
        equal(new IncrementalSearch(terrain, from, goal, base).advance(200).status(),
            IncrementalSearch.Status.FAILED, "ordinary jumps do not opt in to gaps");
        var optedIn = new IncrementalSearch(terrain, from, goal, base.withGapJumps(true)).advance(200);
        equal(optedIn.path(), List.of(new Step(from, to, Move.JUMP)), "a dry one-block gap has an opt-in jump edge");
        check(optedIn.path().getFirst().gapJump() && optedIn.path().getFirst().cost() == 245,
            "gap edges retain their identity and search cost");
        var takeoff = MinecraftTerrain.gapTakeoff(from, to);
        check(takeoff.x > .8 && takeoff.x < 1 && takeoff.z == .5, "launch is near the supported edge");
        world.put(1, 4, 0, Blocks.STONE.defaultBlockState());
        check(!terrain.canTraverse(from, to, Move.JUMP), "the raised headroom of the arc is verified");
        world.put(1, 4, 0, Blocks.AIR.defaultBlockState());
        world.put(1, 0, 0, Blocks.LAVA.defaultBlockState());
        check(!terrain.canTraverse(from, to, Move.JUMP), "a lava-filled gap is refused");
        world.put(1, 0, 0, Blocks.AIR.defaultBlockState());
        world.put(2, 0, 0, Blocks.STONE_SLAB.defaultBlockState());
        check(!terrain.canTraverse(from, to, Move.JUMP), "a changed landing block is refused");

        world.put(2, 0, 0, Blocks.STONE.defaultBlockState());
        var exclusion = new RouteExclusions.Exclusion(RouteExclusions.Kind.NO_GO_ZONE, "arc",
            new RouteExclusions.Bounds(1.4, 3.9, .25, 1.6, 4.2, .75));
        var excluded = new MinecraftTerrain(world, null, (x, z) -> true, .3, 1.8,
            () -> new RouteExclusions(List.of(exclusion)));
        check(!excluded.canTraverse(from, to, Move.JUMP), "an exclusion above the floor blocks the arc");
    }

    private static void permittedRouteEdits() {
        var floor = Blocks.STONE.defaultBlockState();
        var air = Blocks.AIR.defaultBlockState();
        var from = new Node(0, 16, 0);
        var to = new Node(1, 16, 0);
        var body = new RouteEdits.Change(new BlockPos(1, 1, 0), floor, air, null);
        var head = new RouteEdits.Change(body.target().above(), floor, air, null);
        java.util.function.Predicate<List<RouteEdits.Change>> opens = changes -> {
            var world = new TestWorld();
            world.put(0, 0, 0, floor);
            world.put(1, 0, 0, floor);
            world.put(1, 1, 0, floor);
            world.put(1, 2, 0, floor);
            for (var change : changes) {
                var target = change.target();
                world.put(target.getX(), target.getY(), target.getZ(), change.after());
            }
            return world.terrain().canTraverse(from, to, Move.WALK);
        };
        equal(RouteEdits.clearingSequence(head, body, 2, opens), List.of(head, body),
            "clearing a two-block obstruction verifies both breaks and mines from the top");
        equal(RouteEdits.clearingSequence(head, body, 1, opens), List.of(),
            "a one-break budget cannot start a two-block opening");
        equal(RouteEdits.clearingSequence(head, body, 2, changes -> changes.contains(body)), List.of(body),
            "a single sufficient break takes priority over a two-block opening");

        var bridge = new TestWorld();
        bridge.put(0, 0, 0, floor);
        Vec3 stance = RouteEdits.bridgeEdge(bridge.terrain(), new Vec3(.5, 1, .5), from, to);
        check(stance != null && stance.x > 1.0, "a supported sneak stance exposes the current floor's bridge face");
        bridge.put(1, 1, 0, floor);
        equal(RouteEdits.bridgeEdge(bridge.terrain(), new Vec3(.5, 1, .5), from, to), null,
            "a body obstruction prevents moving out to a bridge stance");
    }

    private static void doorProjectionKeepsCollisionChecks() {
        var world = new TestWorld();
        world.put(0, 0, 0, Blocks.STONE.defaultBlockState());
        world.put(1, 0, 0, Blocks.STONE.defaultBlockState());
        var door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
        world.put(1, 1, 0, door);
        world.put(1, 2, 0, door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        Node from = new Node(0, 16, 0), to = new Node(1, 16, 0);
        var enabled = new MinecraftTerrain(world, null, (x, z) -> true, .3, 1.8, () -> null,
            RoutePermissions.DEFAULT, Set.of());
        check(!world.terrain().canTraverse(from, to, Move.WALK), "a closed door blocks unpermitted routes");
        check(enabled.canTraverse(from, to, Move.WALK), "a hand-openable door may be planned when the open collision shape fits");
        equal(enabled.closedDoorOnStep(new Step(from, to, Move.WALK)), new BlockPos(1, 1, 0), "the executor sees the door before entering its edge");
        world.put(1, 2, 0, Blocks.STONE.defaultBlockState());
        check(!enabled.canTraverse(from, to, Move.WALK), "door handling keeps unrelated headroom collisions");
    }

    private static void woodenTrapdoorProjectionProtectsFooting() {
        var registry = BuiltInRegistries.BLOCK;
        Map<TagKey<Block>, List<Holder<Block>>> originalTags = new HashMap<>();
        registry.listTags().forEach(tag -> originalTags.put(tag.key(), tag.stream().toList()));
        var fixtureTags = new HashMap<>(originalTags);
        fixtureTags.put(BlockTags.WOODEN_TRAPDOORS, List.of(Blocks.OAK_TRAPDOOR.builtInRegistryHolder()));
        registry.prepareTagReload(new TagLoader.LoadResult<>(Registries.BLOCK, fixtureTags)).apply();
        try {
            var world = new TestWorld();
            world.put(0, 0, 0, Blocks.STONE.defaultBlockState());
            world.put(1, 0, 0, Blocks.STONE.defaultBlockState());
            var trapdoor = Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.FACING, Direction.NORTH);
            world.put(1, 1, 0, trapdoor);
            var enabled = new MinecraftTerrain(world, null, (x, z) -> true, .3, 1.8, () -> null,
                RoutePermissions.DEFAULT, Set.of());
            Node from = new Node(0, 16, 0), to = new Node(1, 16, 0);
            check(!world.terrain().canTraverse(from, to, Move.WALK), "a closed hatch blocks a route without door permission");
            check(enabled.canTraverse(from, to, Move.WALK), "a permitted wooden trapdoor can be opened when its open shape fits");
            equal(enabled.closedDoorOnStep(new Step(from, to, Move.WALK)), new BlockPos(1, 1, 0),
                "the route executor sees the closed wooden trapdoor ahead");
            equal(enabled.closedDoorOnStep(new Step(new Node(1, 19, 0), from, Move.WALK)), null,
                "a trapdoor supporting the route's feet remains closed");
            world.put(1, 1, 0, Blocks.IRON_TRAPDOOR.defaultBlockState());
            check(!enabled.canTraverse(from, to, Move.WALK), "iron trapdoors are never treated as hand-openable");

            world.put(1, 1, 0, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH));
            world.put(1, 2, 0, trapdoor);
            Node nextRung = new Node(1, 32, 0);
            check(enabled.isClimbPoint(nextRung), "a closed wooden hatch aligned to a ladder can continue that ladder");
            check(enabled.canTraverse(to, nextRung, Move.CLIMB), "opening the hatch clears the ladder's upward edge");
            equal(enabled.closedDoorOnStep(new Step(to, nextRung, Move.CLIMB)), new BlockPos(1, 2, 0),
                "a hatch is opened before climbing through it");
        } finally {
            registry.prepareTagReload(new TagLoader.LoadResult<>(Registries.BLOCK, originalTags)).apply();
        }
    }

    private static void exclusionsAffectPlanningAndLiveSweeps() {
        var world = new TestWorld();
        for (int x = 0; x <= 1; x++) for (int z = 0; z <= 1; z++) world.put(x, 0, z, Blocks.STONE.defaultBlockState());
        Node from = new Node(0, 16, 0), to = new Node(1, 16, 0);
        var zone = new RouteExclusions.Exclusion(RouteExclusions.Kind.NO_GO_ZONE, "strip",
            new RouteExclusions.Bounds(.9, 1, .25, 1.1, 3, .75));
        var exclusions = new AtomicReference<>(new RouteExclusions(List.of()));
        var terrain = new MinecraftTerrain(world, null, (x, z) -> true, .3, 1.8, exclusions::get);
        check(route(terrain, from, to).path().size() == 1, "the direct edge is initially clear");
        exclusions.set(new RouteExclusions(List.of(zone)));
        check(!terrain.canTraverse(from, to, Move.WALK), "the same swept edge is now blocked live");
        check(terrain.obstructionReason("unknown").contains("strip"), "a swept exclusion names the blocking zone");
        var detour = route(terrain, from, to);
        check(detour.status() == IncrementalSearch.Status.FOUND && detour.path().size() > 1, "the planner routes around a swept exclusion");
        var floorZone = new RouteExclusions.Exclusion(RouteExclusions.Kind.NO_GO_ZONE, "selected floor",
            RouteExclusions.Bounds.blocksInclusive(1, 0, 0, 1, 0, 0));
        exclusions.set(new RouteExclusions(List.of(floorZone)));
        check(route(terrain, from, to).status() == IncrementalSearch.Status.FAILED,
            "a map selection of a floor block excludes walking on its top face");
    }

    private static void zonesAreScopedAndPersistent() throws IOException {
        var root = Files.createTempDirectory("soprano-zones-test-");
        try {
            var world = new Scope("server", "world-a", "minecraft:overworld");
            var other = new Scope("server", "world-b", "minecraft:overworld");
            var nether = new Scope("server", "world-a", "minecraft:the_nether");
            var zone = new RouteExclusions.Exclusion(RouteExclusions.Kind.NO_GO_ZONE, "Base",
                RouteExclusions.Bounds.blocksInclusive(3, 1, 2, 1, 2, 3));
            new RouteZones(root).write(world, List.of(zone));
            var reread = new RouteZones(root);
            equal(reread.read(world), List.of(zone), "a no-go zone survives a store reopen");
            equal(reread.read(other), List.of(), "no-go zones do not leak between saves");
            equal(reread.read(nether), List.of(), "no-go zones do not leak between dimensions");
        } finally {
            try (var files = Files.newDirectoryStream(root)) {
                for (var file : files) Files.delete(file);
            }
            Files.delete(root);
        }
    }

    private static IncrementalSearch.Result route(MinecraftTerrain terrain, Node from, Node to) {
        var search = new IncrementalSearch(terrain, from, new Goal(to.x(), to.y16(), to.z(), 0),
            new IncrementalSearch.Config(true, false, 0, 8, 200, false));
        return search.advance(200);
    }

    private static void equal(Object actual, Object expected, String description) {
        check(Objects.equals(actual, expected), description + ": expected " + expected + ", got " + actual);
    }

    private static void check(boolean passed, String description) {
        if (!passed) throw new AssertionError(description);
        checks++;
    }

    private static final class TestWorld implements CollisionGetter {
        private final Map<BlockPos, BlockState> blocks = new HashMap<>();
        private final WorldBorder border = new WorldBorder();

        MinecraftTerrain terrain() { return new MinecraftTerrain(this, null, (x, z) -> true, .3, 1.8); }
        void put(int x, int y, int z, BlockState block) { blocks.put(new BlockPos(x, y, z), block); }

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
