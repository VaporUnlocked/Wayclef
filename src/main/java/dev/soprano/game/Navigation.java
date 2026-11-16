package dev.soprano.game;

import dev.soprano.SopranoClient;
import dev.soprano.host.ClientEvents;
import dev.soprano.game.transport.BoatRoute;
import dev.soprano.game.transport.CushionRoute;
import dev.soprano.game.transport.ElytraReservePolicy;
import dev.soprano.game.transport.ElytraRoute;
import dev.soprano.game.transport.GroundTransfer;
import dev.soprano.game.transport.MountRoute;
import dev.soprano.game.transport.RailRoute;
import dev.soprano.game.transport.StriderRoute;
import dev.soprano.game.transport.SwimRoute;
import dev.soprano.game.transport.TransportMode;
import dev.soprano.game.transport.TransportRoute;
import dev.soprano.ui.PlanningScreen;
import dev.soprano.ui.PlanningViewport;
import dev.soprano.path.Goal;
import dev.soprano.path.FollowSplice;
import dev.soprano.path.IncrementalSearch;
import dev.soprano.path.Move;
import dev.soprano.path.Node;
import dev.soprano.path.RouteExclusions;
import dev.soprano.path.RoutePolicy;
import dev.soprano.path.SafeReturnCheckpoints;
import dev.soprano.path.Step;
import dev.soprano.task.JobQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.Connection;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.decoration.Cushion;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Owns one local navigation job and advances all world queries on the client thread. */
public final class Navigation implements ClientEvents.Listener {
    public record Options(IncrementalSearch.Config search, int expansionsPerTick, boolean steerView, boolean autoReplan,
                          int planningBudgetMillis, int replanLimit, int partialSegmentLimit, TransportMode transportMode,
                          ElytraReservePolicy.Reserves elytraReserves, boolean avoidMobs, RoutePermissions permissions,
                          VitalityPolicy.Minimums vitality, boolean adaptivePlanning) {
        public Options(IncrementalSearch.Config search, int expansionsPerTick, boolean steerView, boolean autoReplan) {
            this(search, expansionsPerTick, steerView, autoReplan, 2, 3, 32, TransportMode.WALK);
        }

        public Options(IncrementalSearch.Config search, int expansionsPerTick, boolean steerView, boolean autoReplan,
                       int planningBudgetMillis, int replanLimit, int partialSegmentLimit) {
            this(search, expansionsPerTick, steerView, autoReplan, planningBudgetMillis, replanLimit, partialSegmentLimit, TransportMode.WALK);
        }

        public Options(IncrementalSearch.Config search, int expansionsPerTick, boolean steerView, boolean autoReplan,
                       int planningBudgetMillis, int replanLimit, int partialSegmentLimit, TransportMode transportMode) {
            this(search, expansionsPerTick, steerView, autoReplan, planningBudgetMillis, replanLimit, partialSegmentLimit,
                transportMode, ElytraReservePolicy.DEFAULT_RESERVES);
        }

        public Options(IncrementalSearch.Config search, int expansionsPerTick, boolean steerView, boolean autoReplan,
                       int planningBudgetMillis, int replanLimit, int partialSegmentLimit, TransportMode transportMode,
                       ElytraReservePolicy.Reserves elytraReserves) {
            this(search, expansionsPerTick, steerView, autoReplan, planningBudgetMillis, replanLimit, partialSegmentLimit,
                transportMode, elytraReserves, false, RoutePermissions.DEFAULT);
        }

        public Options(IncrementalSearch.Config search, int expansionsPerTick, boolean steerView, boolean autoReplan,
                       int planningBudgetMillis, int replanLimit, int partialSegmentLimit, TransportMode transportMode,
                       ElytraReservePolicy.Reserves elytraReserves, boolean avoidMobs, RoutePermissions permissions) {
            this(search, expansionsPerTick, steerView, autoReplan, planningBudgetMillis, replanLimit, partialSegmentLimit,
                transportMode, elytraReserves, avoidMobs, permissions, VitalityPolicy.Minimums.DISABLED, false);
        }

        public Options {
            Objects.requireNonNull(search, "search settings");
            Objects.requireNonNull(transportMode, "transport mode");
            Objects.requireNonNull(elytraReserves, "Elytra reserves");
            Objects.requireNonNull(permissions, "world action permissions");
            Objects.requireNonNull(vitality, "vitality minimums");
            if (expansionsPerTick < 4 || expansionsPerTick > 512) throw new IllegalArgumentException("Planning work must be 4–512 nodes per tick.");
            if (planningBudgetMillis < 1 || planningBudgetMillis > 8) throw new IllegalArgumentException("Planning time must be 1–8 ms per tick.");
            if (replanLimit < 0 || replanLimit > 12) throw new IllegalArgumentException("Replan attempts must be 0–12.");
            if (partialSegmentLimit < 1 || partialSegmentLimit > 96) throw new IllegalArgumentException("Partial segments must be 1–96.");
        }

        public Options withTransport(TransportMode mode) {
            return new Options(search, expansionsPerTick, steerView, autoReplan, planningBudgetMillis, replanLimit,
                partialSegmentLimit, mode, elytraReserves, avoidMobs, permissions, vitality, adaptivePlanning);
        }

        Options withoutBlockEdits() {
            var walking = new RoutePermissions(0, 0, permissions.useDoors(), permissions.closeDoors(),
                permissions.protectedBlocks(), permissions.materialReserve(), permissions.toolDurabilityReserve());
            return new Options(search, expansionsPerTick, steerView, autoReplan, planningBudgetMillis, replanLimit,
                partialSegmentLimit, transportMode, elytraReserves, avoidMobs, walking, vitality, adaptivePlanning);
        }
    }

    public record StepInspection(Step step, int cost, List<String> alternatives) {
        public StepInspection { alternatives = List.copyOf(alternatives); }
    }

    public record TravelProgress(String instruction, double remainingBlocks, double etaSeconds, boolean partial,
                                 Goal intendedGoal) { }

    private enum State { IDLE, PLANNING, PREVIEW, WALKING, EDITING, RECOVERING, WAITING_TERRAIN, PAUSED, ARRIVED, STOPPED, FAILED }
    private static final Object LOCAL = new Object();
    private static final int FOLLOW_LOOKAHEAD_STEPS = 5;
    private static final int FOLLOW_RETRY_TICKS = 5;
    private static final int PORTAL_APPROACH_RADIUS = 4;
    private static final Navigation INSTANCE = new Navigation();
    private static final NavigationSafety SAFETY = new NavigationSafety();
    private static final RouteCorridors CORRIDORS = new RouteCorridors();
    private final ObservedBlocks observedBlocks = new ObservedBlocks();
    private final SafeReturnCheckpoints checkpoints = new SafeReturnCheckpoints();
    private final AdaptivePlanning planningWork = new AdaptivePlanning();
    private final RouteDoors doors = new RouteDoors();
    private final RouteEdits edits = new RouteEdits();
    private LocalPlayer sampledPlayer;
    private long sampleTick;

    private State state = State.IDLE;
    private String detail = "Set a destination to begin.";
    private Object owner;
    private BooleanSupplier externalMovement = () -> false;
    private ClientLevel level;
    private LocalPlayer player;
    private Goal goal;
    private Goal desiredFollowGoal;
    private FollowContinuation followContinuation;
    private int followRetryTicks;
    private String followHoldReason;
    private Node routeStart;
    private Options options;
    private boolean walkAfterPlan, checkMovementGuard, partial;
    private int replans, parts;
    private int recoveryTicks, frontierWaitTicks, frontierRetryTicks;
    private String recoveryReason;
    private String pendingVitality;
    private RouteEdits.ToolPause pausedRouteTool;
    private Set<BlockPos> permittedPortalCells = Set.of();
    private MinecraftTerrain terrain;
    private IncrementalSearch search;
    private DirectWalkRoute directWalk;
    private LiveGpuRoute liveGpu;
    private PathFollower follower;
    private TransportMode mode = TransportMode.WALK;
    private TransportRoute transport;
    private List<Vec3> transportPath = List.of();
    private List<Step> path = List.of();
    private KeyboardInput controlledInput;
    private Vec2 controlledDirection;

    private record FollowContinuation(Goal goal, Node anchor, int prefixSteps, List<Step> source, IncrementalSearch search) { }

    private Navigation() { }
    public static Navigation get() { return INSTANCE; }
    public static SafeReturnCheckpoints.Scope currentScope() { return SAFETY.scope(); }
    public static RouteExclusions activeExclusions(boolean avoidMobs) { return SAFETY.active(avoidMobs); }

    public List<RouteExclusions.Exclusion> noGoZones() { return SAFETY.zones(); }
    public RouteExclusions.Exclusion putNoGoZone(String name, BlockPos first, BlockPos second) { return SAFETY.put(name, first, second); }
    public boolean removeNoGoZone(String name) { return SAFETY.remove(name); }
    public List<RouteCorridor> corridors() { return CORRIDORS.list(); }
    public RouteCorridor putCorridor(String name, BlockPos first, BlockPos second) { return CORRIDORS.put(name, first, second); }
    public boolean removeCorridor(String name) { return CORRIDORS.remove(name); }
    public void observeBlock(BlockPos position) { observedBlocks.observe(Minecraft.getInstance(), position); }

    public void initialize() {
        ClientEvents.subscribe(this);
    }

    public void request(Goal next, boolean walk, Options requestedOptions) {
        if (owner != LOCAL && active()) throw new IllegalStateException("Another tool owns Wayclef navigation.");
        begin(LOCAL, next, walk, requestedOptions, walk);
    }

    public boolean requestOwned(Object token, Goal next, Options requestedOptions) {
        if (active() && owner != token) return false;
        begin(Objects.requireNonNull(token, "navigation owner"), next, true, requestedOptions, false);
        return true;
    }

    public boolean requestGroundTransfer(Object token, Goal next, Options requestedOptions) {
        if (active() && owner != token) return false;
        begin(Objects.requireNonNull(token, "transfer owner"), next, true,
            requestedOptions.withTransport(TransportMode.WALK), false, Set.of(), new RouteEdits.Usage(0, 0), true);
        return true;
    }

    boolean requestPortal(Object token, Goal next, Options requestedOptions, Set<BlockPos> cells) {
        if (active() && owner != token) return false;
        Set<BlockPos> portal = Set.copyOf(cells);
        if (portal.isEmpty() || portal.size() > 12) throw new IllegalArgumentException("Select a known portal entry.");
        begin(Objects.requireNonNull(token, "portal owner"), next, true,
            requestedOptions.withTransport(TransportMode.WALK), true, portal);
        return true;
    }

    boolean requestFollow(Object token, Goal next, Options requestedOptions) {
        if (active() && owner != token) return false;
        begin(Objects.requireNonNull(token, "follow owner"), next, true,
            requestedOptions.withTransport(TransportMode.WALK), true);
        desiredFollowGoal = next;
        return true;
    }

    boolean requestTrip(Object token, Goal next, Options requestedOptions) {
        if (active() && owner != token) return false;
        begin(Objects.requireNonNull(token, "trip owner"), next, true, requestedOptions, true);
        return true;
    }

    boolean refreshFollow(Object token, Goal next, Options requestedOptions) {
        if (owner != token || state == State.STOPPED) return false;
        if (state == State.PAUSED) {
            if (!followToolReady(token)) return false;
            begin(token, next, true, requestedOptions.withTransport(TransportMode.WALK), true, Set.of(), edits.usage());
        } else if (!active()) return requestFollow(token, next, requestedOptions);
        desiredFollowGoal = next;
        followHoldReason = null;
        return true;
    }

    boolean waitingForFollowTool(Object token) {
        return owner == token && state == State.PAUSED && pausedRouteTool != null;
    }

    boolean followToolReady(Object token) {
        return waitingForFollowTool(token) && samePlayer() && player.onGround() && pausedRouteTool.replacementReady(level, player);
    }

    public String ownedState(Object token) {
        return owner == token ? state.name() : State.IDLE.name();
    }

    public String ownedStatus(Object token) {
        return owner == token ? statusText() : "No navigation job for this owner.";
    }

    public void cancelOwned(Object token) {
        if (owner == token) stop();
    }

    boolean holdFollow(Object token, String reason) {
        if (owner != token || state == State.STOPPED || state == State.PAUSED) return false;
        desiredFollowGoal = null;
        followContinuation = null;
        if (follower != null && (follower.committedAirMove() || !player.onGround())) {
            followHoldReason = reason;
        } else {
            finishFollowHold(reason);
        }
        return true;
    }

    private void finishFollowHold(String reason) {
        end(State.ARRIVED, reason);
        path = List.of();
    }

    void setMovementGuard(BooleanSupplier guard) {
        externalMovement = Objects.requireNonNull(guard, "movement guard");
    }

    public boolean movementReservedByOther(Object token) {
        return active() && owner != token || externalMovement.getAsBoolean();
    }

    private void begin(Object token, Goal next, boolean walk, Options requestedOptions, boolean guard) {
        begin(token, next, walk, requestedOptions, guard, Set.of());
    }

    private void begin(Object token, Goal next, boolean walk, Options requestedOptions, boolean guard, Set<BlockPos> portal) {
        begin(token, next, walk, requestedOptions, guard, portal, new RouteEdits.Usage(0, 0));
    }

    private void begin(Object token, Goal next, boolean walk, Options requestedOptions, boolean guard,
                       Set<BlockPos> portal, RouteEdits.Usage usage) {
        begin(token, next, walk, requestedOptions, guard, portal, usage, false);
    }

    private void begin(Object token, Goal next, boolean walk, Options requestedOptions, boolean guard,
                       Set<BlockPos> portal, RouteEdits.Usage usage, boolean groundTransfer) {
        ReplaceActivity activity = ReplaceActivity.get();
        if (activity.active() && !activity.ownsNavigation(token)) throw new IllegalStateException("Stop Replace before starting another navigation job.");
        BlueprintConstruction construction = BlueprintConstruction.get();
        if (construction.active() && !construction.ownsNavigation(token)) throw new IllegalStateException("Stop the blueprint build before starting another navigation job.");
        JobQueue jobs = JobQueue.get();
        if (jobs.active() && !jobs.ownsNavigation(token)) throw new IllegalStateException("Stop the task queue before starting another navigation job.");
        Objects.requireNonNull(next, "destination");
        Objects.requireNonNull(requestedOptions, "settings");
        Minecraft mc = Minecraft.getInstance();
        TransportMode nextMode = groundTransfer
            ? mc.player != null && mc.player.getVehicle() instanceof AbstractBoat ? TransportMode.BOAT : TransportMode.MOUNT
            : selectMode(mc.player, requestedOptions.transportMode());
        if (next.sightY() != null && nextMode != TransportMode.WALK && !groundTransfer)
            throw new IllegalStateException("Use walking mode for a line-of-sight destination.");
        if (next.skyExposed() && (nextMode != TransportMode.WALK || groundTransfer))
            throw new IllegalStateException("Use walking mode to route to the surface.");
        if (next.skyExposed() && mc.level != null && !mc.level.dimensionType().hasSkyLight())
            throw new IllegalStateException("This dimension has no sky-exposed surface.");
        if ((next.heightBand() != null || next.bounds() != null) && (nextMode != TransportMode.WALK || groundTransfer))
            throw new IllegalStateException("Use walking mode for a height or selection-constrained route.");
        String problem = playerProblem(mc, guard, nextMode);
        if (problem != null) throw new IllegalStateException(problem);
        if (walk) {
            String conflict = SopranoClient.movementConflictReason();
            if (conflict != null) throw new IllegalStateException(conflict);
        }
        if (!ObservedBlocks.targetStillPossible(mc.level, next)) throw new IllegalStateException("The observed target block has changed.");
        if (walk && !allowedScreen(mc.gui.screen())) throw new IllegalStateException("Close the current game screen before moving.");
        String lowVitality = walk ? vitalityProblem(mc.player, requestedOptions) : null;
        if (lowVitality != null && !mc.player.isFallFlying() && !StriderRoute.riding(mc.player))
            throw new IllegalStateException(lowVitality);
        MinecraftTerrain nextTerrain = null;
        Node start = null;
        TransportRoute nextTransport = null;
        if (nextMode == TransportMode.WALK) {
            nextTerrain = terrain(mc.level, mc.player, requestedOptions, portal, next);
            start = nextTerrain.startAtPlayer();
            if (start == null) throw new IllegalStateException(nextTerrain.obstructionReason("Stand on safe, loaded ground or grab a ladder before planning."));
        } else {
            nextTransport = groundTransfer ? new GroundTransfer(mc.level, mc.player, next, requestedOptions)
                : transportFor(nextMode, mc.level, mc.player, next, requestedOptions);
            if (nextTransport.status() == TransportRoute.Status.FAILED) {
                nextTransport.close();
                throw new IllegalStateException(nextTransport.detail());
            }
        }
        releaseControls();
        directWalk = null;
        cancelGpuRoute();
        releaseTransport();
        doors.reset();
        edits.clear(mc);
        edits.setUsage(usage);
        mode = nextMode;
        owner = token;
        level = mc.level;
        player = mc.player;
        goal = next;
        desiredFollowGoal = null;
        followContinuation = null;
        followHoldReason = null;
        followRetryTicks = 0;
        options = requestedOptions;
        pendingVitality = null;
        pausedRouteTool = null;
        permittedPortalCells = portal;
        walkAfterPlan = walk;
        checkMovementGuard = guard;
        replans = parts = 0;
        recoveryTicks = frontierWaitTicks = frontierRetryTicks = 0;
        transportPath = List.of();
        if (nextTransport == null) {
            if (walk && needsStagedSearch(start)) frontierWaitTicks = 84;
            plan(nextTerrain, start);
        } else {
            terrain = null;
            routeStart = null;
            search = null;
            follower = null;
            transport = nextTransport;
            path = List.of();
            state = State.PLANNING;
            detail = nextMode + " · " + transport.detail();
        }
    }

    private static TransportMode selectMode(LocalPlayer player, TransportMode requested) {
        if (requested != TransportMode.AUTO || player == null) return requested == TransportMode.AUTO ? TransportMode.WALK : requested;
        if (player.isFallFlying()) return TransportMode.ELYTRA;
        if (StriderRoute.riding(player)) return TransportMode.STRIDER;
        if (player.getVehicle() instanceof AbstractBoat) return TransportMode.BOAT;
        if (player.getVehicle() instanceof AbstractMinecart) return TransportMode.RAIL;
        if (player.getVehicle() instanceof Cushion) return TransportMode.CUSHION;
        if (player.isPassenger()) return TransportMode.MOUNT;
        return player.isInWater() ? TransportMode.SWIM : TransportMode.WALK;
    }

    private static TransportRoute transportFor(TransportMode mode, ClientLevel level, LocalPlayer player, Goal goal, Options options) {
        return switch (mode) {
            case ELYTRA -> new ElytraRoute(level, player, goal, options);
            case BOAT -> new BoatRoute(level, player, goal, options);
            case MOUNT -> new MountRoute(level, player, goal, options);
            case RAIL -> new RailRoute(level, player, goal, options);
            case STRIDER -> new StriderRoute(level, player, goal, options);
            case SWIM -> new SwimRoute(level, player, goal, options);
            case CUSHION -> new CushionRoute(level, player, goal, options);
            default -> throw new IllegalArgumentException("Unsupported vehicle mode: " + mode);
        };
    }

    private void plan(MinecraftTerrain nextTerrain, Node start) {
        cancelGpuRoute();
        terrain = nextTerrain;
        routeStart = start;
        followContinuation = null;
        var config = options.search();
        if (!config.allowPartial() && (needsStagedSearch(start) || walkAfterPlan && edits.canEdit(options.permissions()))) {
            config = new IncrementalSearch.Config(config.diagonals(), config.jumping(), config.maxDrop(),
                config.horizontalRange(), config.maxVisited(), true, config.searchMode(), config.gapJumps());
        }
        search = new IncrementalSearch(terrain, start, goal, config);
        directWalk = search.result().status() == IncrementalSearch.Status.SEARCHING && player.onGround()
            ? DirectWalkRoute.forGoal(start, goal, config) : null;
        var module = SopranoClient.module();
        if (module != null && module.useDedicatedGpu() && search.result().status() == IncrementalSearch.Status.SEARCHING && player.onGround()
            && goal.sightY() == null && goal.heightBand() == null && goal.bounds() == null && !goal.skyExposed()
            && permittedPortalCells.isEmpty() && CORRIDORS.list().isEmpty()) {
            var survey = PlanningViewport.retainedTerrain(Minecraft.getInstance());
            if (survey != null) liveGpu = LiveGpuRoute.submit(survey, start, goal, config, activeExclusions(options.avoidMobs()));
        }
        follower = null;
        path = List.of();
        partial = false;
        state = State.PLANNING;
        detail = "Checking loaded terrain.";
    }

    private boolean needsStagedSearch(Node start) {
        if (goal.heightBand() != null || goal.skyExposed()) return false;
        long distance = Math.max(Math.abs((long) goal.x() - start.x()), Math.abs((long) goal.z() - start.z()));
        return distance - goal.radius() > options.search().horizontalRange()
            || !level.getChunkSource().hasChunk(goal.x() >> 4, goal.z() >> 4);
    }

    public boolean active() {
        return state == State.PLANNING || state == State.WALKING || state == State.PAUSED
            || state == State.RECOVERING || state == State.WAITING_TERRAIN || state == State.EDITING;
    }
    public boolean movementInUse() { return walkAfterPlan && active(); }
    public boolean ownsBreaking() { return state == State.EDITING && edits.ownsBreaking(Minecraft.getInstance()); }
    public List<Step> path() { return path; }
    public List<Vec3> transportPath() { return transportPath; }

    public StepInspection inspectStep(int index) {
        if (index < 0 || index >= path.size() || !samePlayer()) throw new IllegalArgumentException("Select a confirmed walking step.");
        Step chosen = path.get(index);
        var current = terrain(level, player, options, permittedPortalCells, goal);
        List<String> alternatives = new ArrayList<>();
        var config = options.search();
        Node from = chosen.from();
        current.beginSearchSlice();
        try {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0 || !config.diagonals() && dx != 0 && dz != 0) continue;
                    int x = from.x() + dx, z = from.z() + dz;
                    if (!Node.validColumn(x) || !Node.validColumn(z)) continue;
                    var floors = current.floors(x, z, Math.max(Node.MIN_Y16, from.y16() - 16 * Math.max(1, config.maxDrop())),
                        Math.min(Node.MAX_Y16, from.y16() + 16));
                    String label = x + ", " + z + " · ";
                    if (floors.isEmpty()) {
                        alternatives.add(label + current.obstructionReason("No verified standing position at this height."));
                        continue;
                    }
                    for (Node to : floors) {
                        if (to.equals(chosen.to())) continue;
                        int rise = to.y16() - from.y16();
                        if (dx != 0 && dz != 0 && (rise != 0 || !clearDiagonalSide(current, from, x, z))) {
                            alternatives.add(label + "Diagonal needs level footing and clear adjacent sides.");
                            continue;
                        }
                        Move move = current.canTraverse(from, to, Move.WALK) ? Move.WALK
                            : rise > 0 && config.jumping() && current.canTraverse(from, to, Move.JUMP) ? Move.JUMP
                            : rise < 0 && -rise <= config.maxDrop() * 16 && current.canTraverse(from, to, Move.DROP) ? Move.DROP : null;
                        if (move == null) alternatives.add(label + current.obstructionReason("The footing or swept body is obstructed."));
                        else alternatives.add(label + "Viable " + move + " edge, cost "
                            + (new Step(from, to, move).cost() + current.transitionPenalty(from, to, move))
                            + ". The planner chose the displayed route using total path cost and remaining distance.");
                    }
                }
            }
            for (Node to : current.climbNeighbors(from)) {
                if (to.equals(chosen.to())) continue;
                String label = to.x() + ", " + to.y() + ", " + to.z() + " · ";
                alternatives.add(label + (current.canTraverse(from, to, Move.CLIMB)
                    ? "Viable CLIMB edge, cost " + (new Step(from, to, Move.CLIMB).cost()
                        + current.transitionPenalty(from, to, Move.CLIMB)) + "."
                    : current.obstructionReason("The ladder entry or exit is obstructed.")));
            }
        } finally {
            current.endSearchSlice();
        }
        return new StepInspection(chosen, chosen.cost() + current.transitionPenalty(chosen.from(), chosen.to(), chosen.move()), alternatives);
    }

    private static boolean clearDiagonalSide(MinecraftTerrain terrain, Node from, int x, int z) {
        return terrain.canTraverse(from, new Node(x, from.y16(), from.z()), Move.WALK)
            && terrain.canTraverse(from, new Node(from.x(), from.y16(), z), Move.WALK);
    }

    public TravelProgress travelProgress() {
        if (!samePlayer() || goal == null || !active() && state != State.PREVIEW)
            return new TravelProgress(statusText(), 0, Double.NaN, false, null);
        Vec3 position = player.position();
        if (!path.isEmpty()) {
            int index = follower == null ? 0 : Math.min(follower.completedSteps(), path.size() - 1);
            Step step = path.get(index);
            double distance = position.distanceTo(center(step.to()));
            double seconds = distance / nominalSpeed(step.move());
            for (int next = index + 1; next < path.size(); next++) {
                Step pending = path.get(next);
                double length = center(pending.from()).distanceTo(center(pending.to()));
                distance += length;
                seconds += length / nominalSpeed(pending.move());
            }
            Node target = step.to();
            String instruction = state == State.WALKING ? step.move() + " to " + target.x() + ", " + target.y() + ", " + target.z() : statusText();
            if (partial) {
                Node frontier = path.getLast().to();
                distance += Math.hypot(goal.x() - frontier.x(), goal.z() - frontier.z());
            }
            return new TravelProgress(instruction, distance, state == State.WALKING && !partial ? seconds : Double.NaN, partial, goal);
        }
        if (!transportPath.isEmpty()) {
            int nearest = 0;
            for (int index = 1; index < transportPath.size(); index++) {
                if (position.distanceToSqr(transportPath.get(index)) < position.distanceToSqr(transportPath.get(nearest))) nearest = index;
            }
            double distance = position.distanceTo(transportPath.get(nearest));
            for (int index = nearest + 1; index < transportPath.size(); index++) {
                distance += transportPath.get(index - 1).distanceTo(transportPath.get(index));
            }
            var mover = player.getVehicle() == null ? player : player.getVehicle();
            double speed = mover.getDeltaMovement().length() * 20;
            double seconds = state == State.WALKING && speed > .5 ? distance / speed : Double.NaN;
            return new TravelProgress(statusText(), distance, seconds, false, goal);
        }
        return new TravelProgress(statusText(), Math.hypot(goal.x() + .5 - position.x, goal.z() + .5 - position.z),
            Double.NaN, true, goal);
    }

    private static Vec3 center(Node node) { return new Vec3(node.centerX(), node.y(), node.centerZ()); }

    private static double nominalSpeed(Move move) {
        return switch (move) {
            case WALK -> 4.1;
            case JUMP, DROP -> 2.4;
            case CLIMB -> 1.5;
            case BUBBLE -> 3.5;
        };
    }

    public String statusText() {
        if (pendingVitality != null && active() && state != State.PAUSED)
            return pendingVitality + " Waiting for a safe stop. " + detail;
        return switch (state) {
            case IDLE -> detail;
            case PLANNING -> "Planning · " + detail;
            case PREVIEW -> "Preview ready · " + detail;
            case WALKING -> (mode == TransportMode.WALK ? "Walking" : mode.toString()) + " · " + detail;
            case PAUSED -> "Paused · " + detail;
            case EDITING -> "Route edit · " + detail;
            case RECOVERING, WAITING_TERRAIN -> "Waiting · " + detail;
            case ARRIVED -> "Arrived · " + detail;
            case STOPPED -> "Stopped · " + detail;
            case FAILED -> "Could not continue · " + detail;
        };
    }

    public void pause() {
        if (active() && state != State.PAUSED) pauseFor("Paused by you.");
    }

    public void resume() {
        if (state != State.PAUSED || goal == null) throw new IllegalStateException("There is no paused destination.");
        if (owner != LOCAL) throw new IllegalStateException("Resume from the tool that requested this path.");
        if (!samePlayer()) {
            stop();
            throw new IllegalStateException("The world or player changed. Select a destination again.");
        }
        var usage = edits.usage();
        begin(LOCAL, goal, walkAfterPlan, options, checkMovementGuard, permittedPortalCells, usage);
    }

    public void stop() {
        end(State.STOPPED, "Select a destination to navigate again.");
        path = List.of();
        transportPath = List.of();
    }

    public void stopLocal() {
        if (owner == LOCAL) stop();
    }

    public Goal goalAtCrosshair() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK)
            throw new IllegalStateException("Aim at a nearby block in the world.");
        return goalAtBlock(hit.getBlockPos());
    }

    public Goal goalAtBlock(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !mc.level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4))
            throw new IllegalStateException("That block is outside the loaded area.");
        Node feet = new MinecraftTerrain(mc.level, mc.player).standingNodeAt(pos);
        if (feet == null) throw new IllegalStateException("The selected block has no safe standing surface.");
        return new Goal(feet.x(), feet.y16(), feet.z(), 0);
    }

    /** Finds an approach with ordinary walking hazards enabled, including the portal itself. */
    public Goal goalNearPortal(BlockPos position) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel current = mc.level;
        if (current == null || mc.player == null) throw new IllegalStateException("Join the portal's dimension first.");
        int x = position.getX(), y = position.getY(), z = position.getZ();
        if (!Node.validColumn(x) || !Node.validColumn(z) || y < current.getMinY() || y > current.getMaxY()
            || y < Node.MIN_Y16 / 16 || y > Node.MAX_Y16 / 16) {
            throw new IllegalArgumentException("The saved portal lies outside this dimension's supported bounds.");
        }
        // A sight goal preserves the approach height but can still walk through confirmed sections.
        if (!current.getChunkSource().hasChunk(x >> 4, z >> 4)) return Goal.lineOfSight(x, y, z, PORTAL_APPROACH_RADIUS);
        int y16 = y * 16;
        if (current.getBlockState(position).is(Blocks.NETHER_PORTAL)) {
            y16 = PortalEntry.find(current, new Goal(x, y16, z, 0)).goal().y16();
        }
        int lower = Math.max(Node.MIN_Y16, Math.max(current.getMinY() * 16, y16 - 48));
        int upper = Math.min(Node.MAX_Y16, Math.min((current.getMaxY() + 1) * 16, y16 + 36));

        var module = SopranoClient.module();
        var terrain = new MinecraftTerrain(current, mc.player, module != null && module.options().avoidMobs());
        Vec3 playerFeet = mc.player.position();
        Node nearest = null;
        double nearestPortal = Double.POSITIVE_INFINITY, nearestPlayer = Double.POSITIVE_INFINITY;
        terrain.beginSearchSlice();
        try {
            for (int dz = -PORTAL_APPROACH_RADIUS; dz <= PORTAL_APPROACH_RADIUS; dz++) {
                for (int dx = -PORTAL_APPROACH_RADIUS; dx <= PORTAL_APPROACH_RADIUS; dx++) {
                    int horizontal = dx * dx + dz * dz;
                    if (horizontal == 0 || horizontal > PORTAL_APPROACH_RADIUS * PORTAL_APPROACH_RADIUS
                        || !Node.validColumn(x + dx) || !Node.validColumn(z + dz)) continue;
                    for (Node candidate : terrain.floors(x + dx, z + dz, lower, upper)) {
                        if (!terrain.canFinish(candidate)) continue;
                        double rise = (candidate.y16() - y16) / 16.0;
                        double portalDistance = horizontal + rise * rise;
                        double playerDistance = playerFeet.distanceToSqr(candidate.centerX(), candidate.y(), candidate.centerZ());
                        if (portalDistance < nearestPortal || portalDistance == nearestPortal && playerDistance < nearestPlayer) {
                            nearestPortal = portalDistance;
                            nearestPlayer = playerDistance;
                            nearest = candidate;
                        }
                    }
                }
            }
        } finally {
            terrain.endSearchSlice();
        }
        if (nearest == null) throw new IllegalStateException("No safe loaded footing was found near the saved portal.");
        return new Goal(nearest.x(), nearest.y16(), nearest.z(), 0);
    }

    public Goal lineOfSightGoal(BlockPos target, int range) {
        return Goal.lineOfSight(target.getX(), target.getY(), target.getZ(), range);
    }

    public Goal knownBlockGoal(String blockId) { return observedBlocks.nearest(Minecraft.getInstance(), blockId); }

    public Goal heightGoal(int minY, int maxY, int horizontalLimit) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) throw new IllegalStateException("Join a world first.");
        if (minY < mc.level.getMinY() || maxY > (long) mc.level.getMaxY() + 1 || minY > maxY) {
            throw new IllegalArgumentException("Enter an ordered height range in the current dimension.");
        }
        var feet = mc.player.blockPosition();
        return Goal.height(feet.getX(), feet.getZ(), minY * 16, maxY * 16, horizontalLimit);
    }

    public Goal surfaceGoal(int horizontalLimit) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) throw new IllegalStateException("Join a world first.");
        var feet = mc.player.blockPosition();
        return Goal.surface(feet.getX(), feet.getZ(), Math.min(1_024, horizontalLimit));
    }

    public Goal facingGoal(double blocks) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) throw new IllegalStateException("Join a world first.");
        if (!Double.isFinite(blocks) || blocks < 1 || blocks > 4_096) {
            throw new IllegalArgumentException("Facing distance must be between 1 and 4,096 blocks.");
        }
        double yaw = Math.toRadians(mc.player.getYRot());
        int x = (int) Math.floor(mc.player.getX() - Math.sin(yaw) * blocks);
        int z = (int) Math.floor(mc.player.getZ() + Math.cos(yaw) * blocks);
        return new Goal(x, null, z, 0);
    }

    public Goal safeReturnGoal() {
        Minecraft mc = Minecraft.getInstance();
        var scope = currentScope();
        if (scope == null || mc.player == null) throw new IllegalStateException("Join the checkpoint's world first.");
        var current = new MinecraftTerrain(mc.level, mc.player);
        current.beginSearchSlice();
        try {
            for (var checkpoint : checkpoints.checkpoints(scope).reversed()) {
                var feet = checkpoint.feet();
                if (mc.player.position().distanceToSqr(feet.x(), feet.y(), feet.z()) < .75 * .75
                    || !current.isSafeCheckpoint(feet.x(), feet.y(), feet.z())) continue;
                int x = (int) Math.floor(feet.x()), z = (int) Math.floor(feet.z());
                int y16 = (int) Math.round(feet.y() * 16);
                for (Node node : current.floors(x, z, y16 - 1, y16 + 1)) {
                    if (current.isSafeCheckpoint(node.centerX(), node.y(), node.centerZ())) {
                        return new Goal(node.x(), node.y16(), node.z(), 0);
                    }
                }
            }
        } finally {
            current.endSearchSlice();
        }
        throw new IllegalStateException("No earlier safe checkpoint is currently verified and loaded.");
    }

    private void sampleCheckpoint(Minecraft mc) {
        if (sampledPlayer != mc.player) {
            checkpoints.resetSampling();
            sampledPlayer = mc.player;
        }
        var scope = currentScope();
        if (scope == null || mc.player == null || !mc.player.isAlive() || !mc.player.onGround()
            || mc.player.isPassenger() || mc.player.getDeltaMovement().horizontalDistanceSqr() > .04 * .04) {
            checkpoints.resetSampling();
            return;
        }
        var feet = new RouteExclusions.Point(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        boolean safe;
        var current = new MinecraftTerrain(mc.level, mc.player);
        try {
            current.beginSearchSlice();
            safe = current.isSafeCheckpoint(feet.x(), feet.y(), feet.z());
        } catch (IllegalStateException unavailable) {
            safe = false;
        } finally {
            current.endSearchSlice();
        }
        checkpoints.observe(scope, sampleTick, new SafeReturnCheckpoints.Observation(feet, true, safe, safe, safe, safe));
    }

    @Override
    public void tickPre() {
        Minecraft mc = Minecraft.getInstance();
        sampleTick++;
        observedBlocks.observeCrosshair(mc);
        sampleCheckpoint(mc);
        if (level != null && !samePlayer()) {
            forgetWorld();
            return;
        }
        if (!active() || state == State.PAUSED) return;
        if (!ObservedBlocks.targetStillPossible(mc.level, goal)) {
            end(State.FAILED, "The observed target block has changed.");
            return;
        }
        if (walkAfterPlan) {
            String conflict = SopranoClient.movementConflictReason();
            if (conflict != null) { pauseFor(conflict); return; }
            boolean ridingBubble = state == State.WALKING && follower != null && follower.ridingBubble();
            String problem = playerProblem(mc, checkMovementGuard, mode, ridingBubble);
            if (problem != null) { pauseFor(problem); return; }
            if (!allowedScreen(mc.gui.screen())) { pauseFor("A game menu opened."); return; }
            if (mc.gui.screen() == null && (!mc.getWindow().isFocused() || !mc.mouseHandler.isMouseGrabbed())) {
                pauseFor("Minecraft lost focus.");
                return;
            }
            if (mc.gui.screen() == null && SopranoClient.manualMovementHeld(mc.options)) {
                pauseFor("You took control of movement.");
                return;
            }
        }
        try {
            if (walkAfterPlan && pauseForVitalityIfSafe()) return;
            if (state == State.RECOVERING) {
                if (!planFromPlayer() && --recoveryTicks <= 0) end(State.FAILED, recoveryReason + " Could not regain safe footing.");
            } else if (state == State.WAITING_TERRAIN) {
                if (++frontierRetryTicks >= 12) {
                    frontierRetryTicks = 0;
                    if (!planFromPlayer()) end(State.FAILED, "Could not regain footing at the end of the confirmed section.");
                }
            } else if (state == State.EDITING) {
                var result = edits.advance(mc, terrain, options);
                detail = edits.detail();
                if (result == RouteEdits.Status.CONFIRMED) {
                    edits.clear(mc);
                    replan(detail);
                } else if (result == RouteEdits.Status.PAUSED) {
                    var tool = edits.toolPause();
                    pauseFor(detail);
                    pausedRouteTool = tool;
                } else if (result == RouteEdits.Status.FAILED) {
                    end(State.FAILED, detail);
                }
            } else if (transport != null) {
                advanceTransport(planningSlice(mc));
            } else if (state == State.PLANNING) {
                advanceWalkingPlan(planningSlice(mc));
            } else if (state == State.WALKING && desiredFollowGoal != null && followHoldReason == null) {
                advanceFollowContinuation(planningSlice(mc));
            }
        } catch (RuntimeException failure) {
            SopranoClient.LOG.warn("Soprano planning stopped", failure);
            end(State.FAILED, failure instanceof IllegalStateException ? failure.getMessage() : "Could not check the current terrain.");
        }
    }

    private AdaptivePlanning.Slice planningSlice(Minecraft mc) {
        boolean adaptive = options.adaptivePlanning() && (mode != TransportMode.ELYTRA || !player.isFallFlying());
        return planningWork.slice(options.expansionsPerTick(), options.planningBudgetMillis(), adaptive,
            adaptive ? mc.getFps() : 0, adaptive ? mc.options.framerateLimit().get() : 0);
    }

    private IncrementalSearch.Result advanceSearch(IncrementalSearch planning, AdaptivePlanning.Slice budget) {
        boolean adaptive = options.adaptivePlanning();
        int previous = adaptive ? planning.result().visited() : 0;
        long started = adaptive ? System.nanoTime() : 0;
        var result = planning.advance(budget.nodes(), budget.nanos());
        if (adaptive) planningWork.observe(System.nanoTime() - started, result.visited() - previous);
        return result;
    }

    private void advanceWalkingPlan(AdaptivePlanning.Slice budget) {
        long began = System.nanoTime();
        if (directWalk != null) {
            var status = directWalk.advance(terrain, budget);
            if (status == DirectWalkRoute.Status.CHECKING) {
                detail = directWalk.checkedSteps() + " direct steps checked.";
                return;
            }
            var checked = directWalk;
            directWalk = null;
            if (status == DirectWalkRoute.Status.FOUND && player.onGround()
                && terrain.isPlayerClear() && terrain.canApproach(routeStart)) {
                accept(new IncrementalSearch.Result(IncrementalSearch.Status.FOUND, checked.path(), checked.checkedSteps(),
                    "Found a direct route to the goal."));
                return;
            }
            long remaining = budget.nanos() - (System.nanoTime() - began);
            if (remaining <= 0) return;
            budget = new AdaptivePlanning.Slice(budget.nodes(), remaining);
        }
        if (liveGpu != null) {
            began = System.nanoTime();
            long costLimit = options.search().searchMode() == IncrementalSearch.SearchMode.OPTIMAL
                ? search.optimalFrontierLowerBound() : Long.MAX_VALUE;
            var status = liveGpu.advance(terrain, budget, costLimit,
                () -> player.onGround() && terrain.isPlayerClear() && terrain.canApproach(routeStart));
            if (status == LiveGpuRoute.Status.VERIFYING) {
                detail = "Checking the GPU route against live terrain.";
                return;
            }
            if (status == LiveGpuRoute.Status.READY) {
                var candidate = liveGpu.candidate();
                cancelGpuRoute();
                accept(new IncrementalSearch.Result(IncrementalSearch.Status.FOUND, candidate.steps(), candidate.steps().size(),
                    "GPU route verified against live terrain."));
                if (state == State.PREVIEW || state == State.WALKING) detail = "GPU route verified • " + detail;
                return;
            }
            if (status == LiveGpuRoute.Status.REJECTED) {
                cancelGpuRoute();
                long remaining = budget.nanos() - (System.nanoTime() - began);
                if (remaining <= 0) return;
                budget = new AdaptivePlanning.Slice(budget.nodes(), remaining);
            }
        }
        accept(advanceSearch(search, budget));
    }

    private void cancelGpuRoute() {
        if (liveGpu != null) liveGpu.cancel();
        liveGpu = null;
    }

    private void advanceFollowContinuation(AdaptivePlanning.Slice budget) {
        if (follower == null || path.isEmpty()) return;
        if (goal.equals(desiredFollowGoal)) {
            followContinuation = null;
            return;
        }
        int activeStep = follower.completedSteps();
        if (followContinuation != null && (followContinuation.source() != path || activeStep >= followContinuation.prefixSteps())) {
            followContinuation = null;
        }
        if (followContinuation == null) {
            if (followRetryTicks > 0 && --followRetryTicks > 0 && path.size() - activeStep > 2) return;
            int prefixSteps = Math.min(path.size(), activeStep + FOLLOW_LOOKAHEAD_STEPS);
            if (prefixSteps <= activeStep) return;
            // A continuation starts at verified footing, which an intermediate bubble waypoint lacks.
            while (prefixSteps < path.size() && path.get(prefixSteps - 1).move() == Move.BUBBLE
                && terrain.isBubblePoint(path.get(prefixSteps - 1).to())) prefixSteps++;
            Node anchor = path.get(prefixSteps - 1).to();
            followContinuation = new FollowContinuation(desiredFollowGoal, anchor, prefixSteps, path,
                new IncrementalSearch(terrain, anchor, desiredFollowGoal, options.search()));
        }
        FollowContinuation pending = followContinuation;
        var result = advanceSearch(pending.search(), budget);
        if (result.status() == IncrementalSearch.Status.SEARCHING) return;
        followContinuation = null;
        followRetryTicks = FOLLOW_RETRY_TICKS;
        if (result.status() == IncrementalSearch.Status.FAILED) return;
        var joined = FollowSplice.join(path, activeStep, pending.prefixSteps(), pending.anchor(), result.path());
        if (joined.isEmpty()) return;
        // Retain a short recent prefix for map inspection without growing an endless follow route.
        path = follower.replaceFuturePath(joined, activeStep >= 32 ? activeStep - 8 : 0);
        goal = pending.goal();
        partial = result.status() == IncrementalSearch.Status.PARTIAL;
    }

    private boolean continueFollowFromHere() {
        if (desiredFollowGoal == null || desiredFollowGoal.equals(goal)) return false;
        FollowContinuation pending = followContinuation;
        if (pending != null && pending.source() == path && pending.prefixSteps() == path.size()
            && pending.goal().equals(desiredFollowGoal)) {
            releaseControls();
            goal = pending.goal();
            routeStart = pending.anchor();
            search = pending.search();
            followContinuation = null;
            follower = null;
            path = List.of();
            partial = false;
            state = State.PLANNING;
            detail = "Finishing the checked continuation toward the target.";
        } else {
            replan("The follow target moved beyond the confirmed route.");
        }
        return true;
    }

    private boolean pauseForVitalityIfSafe() {
        if (pendingVitality == null) {
            pendingVitality = vitalityProblem(player, options);
            if (pendingVitality == null) return false;
            if (transport instanceof ElytraRoute flight && player.isFallFlying()) flight.requestVitalityLanding();
        }
        if (transport instanceof StriderRoute crossing) {
            if (!crossing.stopOnVerifiedGround()) return false;
        } else if (player.isFallFlying() || transport instanceof RailRoute && player.isPassenger()
            || follower != null && follower.committedAirMove()
            || !player.isPassenger() && !player.onGround() && !player.onClimbable()) {
            return false;
        }
        pauseFor(pendingVitality);
        return true;
    }

    private static String vitalityProblem(LocalPlayer player, Options options) {
        return options.vitality().pauseReason(player.getHealth(), player.getFoodData().getFoodLevel());
    }

    private void advanceTransport(AdaptivePlanning.Slice budget) {
        transport.advance(budget.nodes(), budget.nanos());
        transportPath = List.copyOf(transport.waypoints());
        String message = transport.detail();
        switch (transport.status()) {
            case PLANNING -> detail = mode + " · " + message;
            case MOVING -> {
                if (!walkAfterPlan) end(State.PREVIEW, mode + " · " + message);
                else {
                    state = State.WALKING;
                    detail = message;
                }
            }
            case ARRIVED -> {
                if (pendingVitality != null) pauseFor(pendingVitality + " " + message);
                else end(State.ARRIVED, message);
            }
            case FAILED -> {
                if (pendingVitality != null && player.onGround() && !player.isPassenger() && !player.isInLiquid())
                    pauseFor(pendingVitality + " " + message);
                else end(State.FAILED, message);
            }
        }
    }

    private void accept(IncrementalSearch.Result result) {
        if (result.status() == IncrementalSearch.Status.SEARCHING) {
            detail = result.visited() + " positions checked.";
            return;
        }
        cancelGpuRoute();
        if (result.status() == IncrementalSearch.Status.FAILED) {
            if (continueFollowFromHere()) return;
            if (walkAfterPlan && !goal.skyExposed() && edits.begin(Minecraft.getInstance(), terrain, goal, options)) {
                releaseControls();
                search = null;
                state = State.EDITING;
                detail = edits.detail();
            } else if (walkAfterPlan && frontierWaitTicks > 0) {
                frontierWaitTicks -= 12;
                state = State.WAITING_TERRAIN;
                detail = "Waiting for the next loaded section toward " + goal.x() + ", " + goal.z() + ".";
            } else {
                end(State.FAILED, result.reason());
            }
            return;
        }
        partial = result.status() == IncrementalSearch.Status.PARTIAL;
        path = result.path();
        search = null;
        if (!walkAfterPlan) {
            end(State.PREVIEW, (partial ? "Partial path · " : "") + path.size() + " moves.");
        } else if (path.isEmpty()) {
            if (continueFollowFromHere()) return;
            int x = (int) Math.floor(player.getX());
            int z = (int) Math.floor(player.getZ());
            int y16 = (int) Math.round(player.getY() * 16);
            if (Node.validColumn(x) && Node.validColumn(z) && y16 >= Node.MIN_Y16 && y16 <= Node.MAX_Y16
                && goal.matches(new Node(x, y16, z)) && goalEnvironmentStillValid()) {
                end(State.ARRIVED, "Your feet are already in the destination area.");
            } else if (player.onGround() && terrain.canApproach(routeStart)) {
                follower = new PathFollower(terrain, player, routeStart, options.steerView());
                state = State.WALKING;
                detail = "Settling on the destination's footing.";
            } else {
                end(State.FAILED, "Move onto the destination's footing and try again.");
            }
        } else {
            follower = new PathFollower(terrain, player, path, options.steerView());
            state = State.WALKING;
            detail = (partial ? "Walking the next loaded section · " : "") + path.size() + " moves.";
        }
    }

    private boolean goalEnvironmentStillValid() {
        if (goal.skyExposed()) return player.onGround() && terrain.isSkyExposed(player.getX(), player.getY(), player.getZ());
        return terrain.hasLineOfSight(player.getX(), player.getY(), player.getZ(), goal);
    }

    public void applyControls(KeyboardInput input) {
        if (state != State.WALKING && state != State.EDITING || !samePlayer() || player.input != input) return;
        String conflict = SopranoClient.movementConflictReason();
        if (conflict != null) { pauseFor(conflict); return; }
        try {
            if (state == State.EDITING) {
                var motion = edits.motion();
                if (motion != null) applyIntent(input, motion.input(), motion.direction());
                return;
            }
            if (transport != null) {
                applyIntent(input, transport.input(), transport.walkDirection());
                return;
            }
            if (followHoldReason != null && player.onGround() && !follower.committedAirMove()) {
                finishFollowHold(followHoldReason);
                return;
            }
            if (!doors.ready(terrain, follower.nextStep(), options.permissions())) {
                if (doors.failure() == null) {
                    detail = "Opening the next door, gate, or trapdoor.";
                    Input wait = player.onClimbable() && !player.onGround()
                        ? new Input(false, false, false, false, false, true, false) : Input.EMPTY;
                    applyIntent(input, wait);
                } else {
                    end(State.FAILED, doors.failure());
                }
                return;
            }
            Input intent = follower.tick();
            if (followHoldReason != null && (follower.status() != PathFollower.Status.MOVING
                || player.onGround() && !follower.committedAirMove())) {
                finishFollowHold(followHoldReason);
                return;
            }
            switch (follower.status()) {
                case ARRIVED -> {
                    if (continueFollowFromHere()) return;
                    if (partial && ++parts < options.partialSegmentLimit()) {
                        frontierWaitTicks = needsStagedSearch(path.getLast().to()) ? 84 : 0;
                        replan("Reached the end of the loaded section.");
                    }
                    else if (partial) end(State.FAILED, "The destination still needs more loaded terrain.");
                    else if (!goalEnvironmentStillValid()) {
                        String changed = goal.skyExposed() ? "The destination is no longer open to the sky."
                            : "The destination is no longer visible from the verified footing.";
                        if (options.autoReplan() && replans++ < options.replanLimit()) replan(changed);
                        else end(State.FAILED, changed);
                    }
                    else end(State.ARRIVED, "Destination reached.");
                    return;
                }
                case BLOCKED -> {
                    String reason = follower.message();
                    if (options.autoReplan() && replans++ < options.replanLimit()) replan(reason);
                    else end(State.FAILED, reason);
                    return;
                }
                case MOVING -> detail = follower.completedSteps() + "/" + follower.totalSteps() + " moves";
            }
            applyIntent(input, intent, follower.walkDirection());
        } catch (RuntimeException failure) {
            SopranoClient.LOG.warn("Soprano walking stopped", failure);
            end(State.FAILED, "Could not safely continue walking.");
        }
    }

    private void applyIntent(KeyboardInput input, Input intent) {
        applyIntent(input, intent, null);
    }

    private void applyIntent(KeyboardInput input, Input intent, Vec2 verifiedDirection) {
        Vec2 direction = verifiedDirection == null ? direction(intent) : verifiedDirection;
        Vec2 applied = new Vec2(direction.x, direction.y);
        ((NavigationInput) input).soprano$apply(intent, applied);
        controlledInput = input;
        controlledDirection = applied;
    }

    private void replan(String reason) {
        releaseControls();
        if (desiredFollowGoal != null) goal = desiredFollowGoal;
        followContinuation = null;
        if (!planFromPlayer()) {
            state = State.RECOVERING;
            recoveryTicks = 30;
            recoveryReason = reason;
            detail = reason + " Waiting for safe footing.";
            return;
        }
        detail = reason + " Checking a new path.";
    }

    private boolean planFromPlayer() {
        MinecraftTerrain next = terrain(level, player, options, permittedPortalCells, goal);
        Node start = next.startAtPlayer();
        if (start == null) return false;
        plan(next, start);
        return true;
    }

    private static MinecraftTerrain terrain(ClientLevel level, LocalPlayer player, Options options, Set<BlockPos> portal, Goal goal) {
        var roads = CORRIDORS.list().stream().map(RouteCorridor::bounds).toList();
        return new MinecraftTerrain(level, player, options.avoidMobs(), options.permissions(), portal,
            new RoutePolicy(goal.bounds(), roads));
    }

    private void pauseFor(String reason) { end(State.PAUSED, reason); }

    private void end(State next, String reason) {
        State previous = state;
        releaseControls();
        directWalk = null;
        cancelGpuRoute();
        edits.clear(Minecraft.getInstance());
        pausedRouteTool = null;
        if (next == State.ARRIVED && options != null && options.permissions().closeDoors()) doors.finish();
        doors.reset();
        state = next;
        detail = reason;
        search = null;
        followContinuation = null;
        followHoldReason = null;
        follower = null;
        terrain = null;
        routeStart = null;
        releaseTransport();
        if (previous == next) return;
        switch (next) {
            case ARRIVED -> RouteAudio.signal(RouteAudio.Cue.ARRIVED);
            case PAUSED -> RouteAudio.signal(RouteAudio.Cue.PAUSED);
            default -> { }
        }
    }

    private void releaseTransport() {
        if (transport != null) transport.close();
        transport = null;
    }

    private boolean samePlayer() {
        Minecraft mc = Minecraft.getInstance();
        return player != null && mc.player == player && mc.level == level && player.isAlive() && !player.isRemoved();
    }

    private void forgetWorld() {
        stop();
        planningWork.reset();
        level = null;
        player = null;
        goal = null;
        checkpoints.resetSampling();
    }

    @Override
    public void disconnected() { forgetWorld(); }

    @Override
    public void packetSent(Connection source, Packet<?> outbound) { edits.sent(source, outbound); }

    @Override
    public void mouseClick(ClientEvents.Action action) {
        if (state == State.EDITING && action == ClientEvents.Action.PRESS && Minecraft.getInstance().gui.screen() == null) {
            pauseFor("You took control of block interactions.");
        }
    }

    public void acknowledge(ClientLevel world, int sequence) { edits.acknowledge(world, sequence); }

    @Override
    public void screenChanged(Screen screen) {
        if (movementInUse() && state != State.PAUSED && !allowedScreen(screen))
            pauseFor("A game menu opened.");
    }

    @Override
    public void keyInput(KeyEvent input, ClientEvents.Action action) {
        if (!movementInUse() || state == State.PAUSED || action == ClientEvents.Action.RELEASE) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() != null) return;
        if (SopranoClient.manualMovementKey(mc.options, input)) pauseFor("You took control of movement.");
    }

    static boolean allowedScreen(Screen screen) {
        return screen == null || SopranoClient.host().isCompatibleScreen(screen) || screen instanceof PlanningScreen || screen.getClass() == ChatScreen.class;
    }

    private String playerProblem(Minecraft mc, boolean solo, TransportMode requested) {
        return playerProblem(mc, solo, requested, false);
    }

    private String playerProblem(Minecraft mc, boolean solo, TransportMode requested, boolean ridingBubble) {
        if (mc.player == null || mc.level == null || !mc.player.isAlive() || mc.player.isRemoved()) return "Join a world first.";
        if (!(mc.player.input instanceof KeyboardInput) || !(mc.player.input instanceof NavigationInput)) return "Another controller owns player input.";
        if (mc.player.isSleeping() || mc.player.getAbilities().flying || mc.player.isSpectator()) return "Leave sleep, creative flight, or spectator mode before navigating.";
        if (requested == TransportMode.WALK && (mc.player.isPassenger() || mc.player.isFallFlying()
            || mc.player.isInWater() && !ridingBubble || mc.player.isInLava())) return "This walking route needs a player who can walk normally.";
        if (requested == TransportMode.ELYTRA && (mc.player.isPassenger() || mc.player.isInWater() || mc.player.isInLava()))
            return "Elytra routing needs open air and an unmounted player.";
        if (requested == TransportMode.SWIM && (mc.player.isPassenger() || mc.player.isFallFlying() || mc.player.isInLava()))
            return "Surface swimming needs an unmounted player in water.";
        if ((requested == TransportMode.BOAT || requested == TransportMode.MOUNT) && !mc.player.isPassenger()
            && (transport == null || !transport.allowsDismounted()))
            return "Mount the selected transport before navigating.";
        if (requested == TransportMode.RAIL && mc.player.isPassenger() && !(mc.player.getVehicle() instanceof AbstractMinecart))
            return "Dismount before planning a minecart trip.";
        if (solo && externalMovement.getAsBoolean()) return "Another tool already controls movement.";
        return null;
    }

    private void releaseControls() {
        if (controlledInput != null && controlledInput.getMoveVector() == controlledDirection)
            ((NavigationInput) controlledInput).soprano$apply(Input.EMPTY, Vec2.ZERO);
        controlledInput = null;
        controlledDirection = null;
    }

    private static Vec2 direction(Input keys) {
        float lateral = (keys.left() ? 1 : 0) - (keys.right() ? 1 : 0);
        float forward = (keys.forward() ? 1 : 0) - (keys.backward() ? 1 : 0);
        return new Vec2(lateral, forward).normalized();
    }
}
