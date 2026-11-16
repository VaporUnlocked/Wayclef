package dev.soprano.game;

import dev.soprano.SopranoClient;
import dev.soprano.host.ClientEvents;
import dev.soprano.build.BlueprintBlockStates;
import dev.soprano.build.BuildLayers;
import dev.soprano.build.BuildPlan;
import dev.soprano.build.BuildPlan.Cell;
import dev.soprano.build.ScaffoldLedger;
import dev.soprano.game.ConstructionPlacement.Aim;
import dev.soprano.game.ConstructionPlacement.Supply;
import dev.soprano.path.Goal;
import dev.soprano.path.IncrementalSearch;
import dev.soprano.path.Move;
import dev.soprano.path.Node;
import dev.soprano.task.JobQueue;
import dev.soprano.ui.PlanningScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.protocol.Packet;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Client-thread blueprint work. Every placement and break uses the ordinary game interaction. */
public final class BlueprintConstruction implements ClientEvents.Listener {
    private enum Phase { IDLE, ARMED, SELECT, TRY_PLACE, WALK, SELECT_SUPPLY, AIM_PLACE, WAIT_PLACE, AUDIT,
        CLEANUP, CHECK_EXIT, AIM_BREAK, BREAK, PAUSED_TOOL, WAIT_BREAK }
    private enum WalkPurpose { BUILD_STAND, SCAFFOLD_STEP, CLEANUP_STAND }
    private enum Action { NONE, PLACE, BREAK }
    private enum SupplySelection { READY, WAITING, UNAVAILABLE }
    private record ScaffoldCandidate(BlockPos position, Node stand, double score) { }

    private static final BlueprintConstruction INSTANCE = new BlueprintConstruction();
    private static final Navigation.Options WALK = new Navigation.Options(
        new IncrementalSearch.Config(true, true, 1, 96, 14_000, false), 96, true, true);
    private static final int MAX_STANDS = 16;
    private static final int CONFIRM_TICKS = 100;
    private static final int MAX_SCAFFOLD = 512;

    private final Object owner = new Object();
    private final ScaffoldLedger scaffolds = new ScaffoldLedger();
    private final Map<String, BlockState> resolved = new HashMap<>();
    private final ArrayDeque<Cell> remaining = new ArrayDeque<>();
    private final ArrayDeque<Cell> deferred = new ArrayDeque<>();

    private Phase phase = Phase.IDLE;
    private Action recording = Action.NONE;
    private ClientLevel level;
    private LocalPlayer player;
    private MultiPlayerGameMode gameMode;
    private Connection connection;
    private ConstructionPlacement placement;
    private Node home;
    private Map<Cell, String> plan = Map.of();
    private List<BuildLayers.Layer> layers = List.of();
    private Iterator<Map.Entry<Cell, String>> audit;
    private int layer, placements, placementLimit, scaffoldPlacements, scaffoldLimit, unfinished, unverifiedScaffolds;
    private Block scaffoldMaterial;
    private boolean layerProgress, cleanupOnly, ownsMine, viewOwned, viewSettled;
    private String firstBlocked, buildResult, status = "No blueprint build is running.";
    private float ownedYaw, ownedPitch;
    private int originalSlot, ownedSlot = -1;

    private BlockPos buildTarget, target;
    private BlockState wanted, targetState;
    private Supply supply;
    private Node scaffoldStep;
    private boolean scaffoldPlacement;
    private List<Node> stands = List.of();
    private int standIndex, wait, sentSequence = -1, acknowledged = -1;
    private WalkPurpose walkPurpose;
    private ScaffoldExit exit;
    private ScaffoldLedger.Placement pendingPlacement;
    private ScaffoldLedger.Removal pendingRemoval;
    private long revision, verifiedRevision;
    private boolean invalidated;

    private BlueprintConstruction() { }

    public static BlueprintConstruction get() { return INSTANCE; }
    public void initialize() { ClientEvents.subscribe(this); }
    public boolean active() { return phase != Phase.IDLE; }
    public boolean ownsNavigation(Object token) { return token == owner; }
    public String statusText() { return status; }
    public int unfinished() { return unfinished; }

    public boolean cleanupAvailable() {
        return !active() && sameWorld(Minecraft.getInstance()) && scaffolds.ownedCount() > 0;
    }

    public void start(BuildPlan blueprint, BuildLayers.Direction order, int placementLimit, int scaffoldLimit) {
        start(blueprint, order, placementLimit, scaffoldLimit, null);
    }

    public void start(BuildPlan blueprint, BuildLayers.Direction order, int placementLimit, int scaffoldLimit, Block scaffoldMaterial) {
        Minecraft mc = Minecraft.getInstance();
        checkStart(mc);
        if (cleanupAvailable()) throw new IllegalStateException("Clean up the previous build's owned scaffolding first.");
        if (blueprint.size() == 0) throw new IllegalArgumentException("The blueprint has no blocks.");
        if (placementLimit < 0 || placementLimit > BuildPlan.MAX_BLOCKS * 2 || scaffoldLimit < 0 || scaffoldLimit > MAX_SCAFFOLD)
            throw new IllegalArgumentException("Choose valid placement and scaffold limits.");
        Node start = new MinecraftTerrain(mc.level, mc.player).startAtPlayer();
        if (!mc.player.onGround() || start == null) throw new IllegalStateException("Stand on verified, safe footing first.");

        bind(mc);
        scaffolds.clear();
        home = start;
        plan = blueprint.snapshot();
        layers = BuildLayers.stages(plan.keySet(), order);
        this.placementLimit = placementLimit;
        this.scaffoldLimit = scaffoldLimit;
        this.scaffoldMaterial = scaffoldMaterial;
        placements = scaffoldPlacements = layer = unverifiedScaffolds = 0;
        unfinished = plan.size();
        cleanupOnly = false;
        buildResult = null;
        loadLayer();
        phase = Phase.ARMED;
        status = "Starting construction of " + plan.size() + " planned blocks.";
    }

    public void cleanup() {
        Minecraft mc = Minecraft.getInstance();
        checkStart(mc);
        if (!cleanupAvailable()) throw new IllegalStateException("There is no confirmed scaffold to clean up in this world.");
        bind(mc);
        cleanupOnly = true;
        buildResult = "Scaffold cleanup finished.";
        phase = Phase.ARMED;
        status = "Cleanup armed. Close the map to start.";
    }

    public void stop() {
        if (active()) finish("Blueprint work stopped.");
    }

    private void checkStart(Minecraft mc) {
        if (active()) throw new IllegalStateException("Stop the current blueprint work first.");
        if (Navigation.get().movementReservedByOther(owner) || FollowController.get().active()
            || ReplaceActivity.get().active() || JobQueue.get().active())
            throw new IllegalStateException("Stop the current navigation or activity first.");
        if (mc.level == null || mc.player == null || mc.gameMode == null || !mc.player.isAlive()
            || !mc.player.mayBuild() || mc.player.isSpectator())
            throw new IllegalStateException("Join a world where you can build first.");
        if (mc.gui.screen() != null && !(mc.gui.screen() instanceof PlanningScreen) && !(mc.gui.screen() instanceof ChatScreen))
            throw new IllegalStateException("Close the current game screen first.");
    }

    private void bind(Minecraft mc) {
        level = mc.level;
        player = mc.player;
        gameMode = mc.gameMode;
        connection = player.connection.getConnection();
        placement = new ConstructionPlacement(level, player);
        originalSlot = player.getInventory().getSelectedSlot();
        ownedSlot = -1;
    }

    @Override
    public void tickPost() {
        Minecraft mc = Minecraft.getInstance();
        if (!active()) {
            if (level != null && !sameWorld(mc)) forgetWorld();
            return;
        }
        if (!sameWorld(mc)) { forgetWorld(); return; }
        if (phase == Phase.PAUSED_TOOL && (mc.gui.screen() != null || !mc.isWindowActive()
            || !mc.mouseHandler.isMouseGrabbed() || SopranoClient.manualInteractionHeld(mc.options) || player.isUsingItem())) return;
        if (phase == Phase.ARMED && (mc.gui.screen() instanceof PlanningScreen || mc.gui.screen() instanceof ChatScreen)) return;
        if (mc.gui.screen() != null || !mc.isWindowActive() || !mc.mouseHandler.isMouseGrabbed()) {
            finish("Blueprint work stopped when the game lost focus or opened a screen.");
            return;
        }
        if (Navigation.get().movementReservedByOther(owner) || player.isPassenger() || player.isFallFlying()
            || player.getAbilities().flying || player.isInWater() || player.isInLava()
            || SopranoClient.manualInteractionHeld(mc.options) || player.isUsingItem()
            || viewMoved()) {
            finish("Blueprint work released controls.");
            return;
        }
        try {
            switch (phase) {
                case ARMED -> phase = cleanupOnly ? Phase.CLEANUP : Phase.SELECT;
                case SELECT -> select();
                case TRY_PLACE -> tryPlace();
                case WALK -> walk();
                case SELECT_SUPPLY -> waitForSupply();
                case AIM_PLACE -> aimPlace();
                case WAIT_PLACE -> confirmPlace();
                case AUDIT -> audit();
                case CLEANUP -> selectCleanup();
                case CHECK_EXIT -> checkExit();
                case AIM_BREAK -> aimBreak();
                case BREAK -> continueBreak();
                case PAUSED_TOOL -> resumeAfterTool();
                case WAIT_BREAK -> confirmBreak();
                case IDLE -> { }
            }
        } catch (RuntimeException failure) {
            SopranoClient.LOG.warn("Blueprint work stopped", failure);
            finish(failure instanceof IllegalStateException ? failure.getMessage() : "Could not safely continue blueprint work.");
        }
    }

    private void loadLayer() {
        remaining.clear();
        remaining.addAll(layers.get(layer).targets());
        deferred.clear();
        layerProgress = false;
        firstBlocked = null;
    }

    private void select() {
        viewOwned = false;
        if (remaining.isEmpty()) {
            if (!deferred.isEmpty()) {
                if (!layerProgress) {
                    beginCleanup("Layer Y=" + layers.get(layer).y() + " could not finish. " + firstBlocked);
                    return;
                }
                remaining.addAll(deferred);
                deferred.clear();
                layerProgress = false;
                firstBlocked = null;
            } else if (++layer == layers.size()) {
                beginCleanup(null);
                return;
            } else loadLayer();
        }

        Cell cell = remaining.removeFirst();
        buildTarget = pos(cell);
        wanted = desired(buildTarget);
        if (wanted == null) { defer("Unknown block state at " + at(buildTarget) + "."); return; }
        if (!ConstructionPlacement.loaded(level, buildTarget)) { defer("Unloaded target at " + at(buildTarget) + "."); return; }
        BlockState current = level.getBlockState(buildTarget);
        if (ConstructionPlacement.matchesDuringConstruction(current, wanted)) {
            completeTarget();
            return;
        }
        if (wanted.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
            && wanted.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            defer("The upper half at " + at(buildTarget) + " needs its lower half placed first.");
            return;
        }
        if (wanted.hasProperty(BlockStateProperties.BED_PART)
            && wanted.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) {
            defer("The bed head at " + at(buildTarget) + " needs its foot placed first.");
            return;
        }
        if (!current.isAir() && !ConstructionPlacement.canUpgradeSlab(current, wanted)
            && !(current.is(Blocks.WATER) && !wanted.getFluidState().isEmpty())) {
            defer("Existing block conflicts at " + at(buildTarget) + ".");
            return;
        }
        if (placements >= placementLimit) {
            beginCleanup("The placement limit was reached.");
            return;
        }
        supply = supply(wanted.getBlock());
        if (supply == null) {
            defer("Missing " + BlueprintBlockStates.blockId(plan.get(cell)) + " for " + at(buildTarget) + ".");
            return;
        }
        stands = List.of();
        standIndex = 0;
        phase = Phase.TRY_PLACE;
    }

    private void tryPlace() {
        if (!ConstructionPlacement.loaded(level, buildTarget)) { defer("The target is unloaded."); return; }
        if (ConstructionPlacement.matchesDuringConstruction(level.getBlockState(buildTarget), wanted)) {
            completeTarget();
            return;
        }
        supply = supply(wanted.getBlock());
        if (supply == null) { defer("The required material is no longer available."); return; }
        if (safeFooting() && !player.getBoundingBox().intersects(new AABB(buildTarget))
            && placement.find(buildTarget, wanted, supply, player.getEyePosition(), this::desired) != null) {
            beginPlacement(buildTarget, wanted, supply, null);
            return;
        }
        stands = findStands(buildTarget, false);
        standIndex = 0;
        tryBuildStand();
    }

    private void tryBuildStand() {
        if (walkNext(WalkPurpose.BUILD_STAND)) return;
        if (tryScaffold()) return;
        defer("No verified placement position for the requested state at " + at(buildTarget) + ".");
    }

    private void beginPlacement(BlockPos at, BlockState state, Supply material, ScaffoldCandidate scaffold) {
        target = at;
        targetState = state;
        supply = material;
        scaffoldPlacement = scaffold != null;
        scaffoldStep = scaffold == null ? null : scaffold.stand();
        resetAction();
        switch (selectSupply()) {
            case READY -> beginAim(Phase.AIM_PLACE);
            case WAITING -> {
                wait = 0;
                phase = Phase.SELECT_SUPPLY;
                status = "Waiting for the selected material to reach the hotbar.";
            }
            case UNAVAILABLE -> defer("The required material could not be selected.");
        }
    }

    private void waitForSupply() {
        if (player.getItemInHand(supply.hand()).getItem() == supply.item()) beginAim(Phase.AIM_PLACE);
        else if (++wait >= CONFIRM_TICKS) defer("The server did not confirm the inventory swap.");
    }

    private void aimPlace() {
        if (!ConstructionPlacement.loaded(level, target) || !safeFooting()
            || player.getBoundingBox().intersects(new AABB(target))) {
            defer("Placement footing or target changed.");
            return;
        }
        ItemStack held = player.getItemInHand(supply.hand());
        if (held.isEmpty() || held.getItem() != supply.item()) { defer("The selected material changed."); return; }
        Supply actual = new Supply(supply.hand(), supply.slot(), held, supply.item());
        Aim aim = placement.find(target, targetState, actual, player.getEyePosition(), this::desired);
        if (++wait > 45 || aim == null) { defer("The required placement face is no longer reachable."); return; }
        BlockHitResult hit = settleAim(aim);
        if (hit == null || !placement.matchesInteraction(target, targetState, actual, hit, this::desired)) return;
        if (scaffoldPlacement) {
            BlockState before = level.getBlockState(target);
            pendingPlacement = scaffolds.beginPlacement(cell(target), observation(before, revision),
                BlueprintBlockStates.encode(targetState)).orElse(null);
            if (pendingPlacement == null) { defer("The temporary placement cell changed."); return; }
            scaffoldPlacements++;
        } else placements++;
        recording = Action.PLACE;
        try {
            var result = gameMode.useItemOn(player, supply.hand(), hit);
            if (!result.consumesAction() || sentSequence < 0) { defer("The game declined the placement."); return; }
            player.swing(supply.hand(), SwingAnimation.DEFAULT, false);
        } finally {
            recording = Action.NONE;
        }
        phase = Phase.WAIT_PLACE;
        wait = 0;
        status = "Waiting for placement confirmation at " + at(target) + ".";
    }

    private void confirmPlace() {
        if (++wait > CONFIRM_TICKS || invalidated || !ConstructionPlacement.loaded(level, target)) {
            defer("Placement could not be confirmed at " + at(target) + ".");
            return;
        }
        if (acknowledged < sentSequence) return;
        BlockState current = level.getBlockState(target);
        if (scaffoldPlacement) {
            if (verifiedRevision == 0) return;
            if (current != targetState || !scaffolds.confirmOwnPlacement(pendingPlacement, observation(current, verifiedRevision))) {
                defer("The temporary block changed or the server rejected it.");
                return;
            }
            pendingPlacement = null;
            Node step = scaffoldStep;
            resetAction();
            if (step == null) phase = Phase.TRY_PLACE;
            else if (!requestWalk(step, WalkPurpose.SCAFFOLD_STEP)) defer("The temporary step could not be reached.");
        } else if (ConstructionPlacement.matchesDuringConstruction(current, wanted)) {
            completeTarget();
        } else if (ConstructionPlacement.canUpgradeSlab(current, wanted) && placements < placementLimit) {
            resetAction();
            phase = Phase.TRY_PLACE;
        } else {
            defer("The server did not place the expected state at " + at(target) + ".");
        }
    }

    private boolean tryScaffold() {
        if (scaffoldPlacements >= scaffoldLimit || !player.onGround()) return false;
        MinecraftTerrain actual = new MinecraftTerrain(level, player);
        Node start = actual.startAtPlayer();
        if (start == null || (start.y16() & 15) != 0) return false;
        Supply material = scaffoldSupply(start);
        if (material == null) return false;
        BlockState state = material.item().getBlock().defaultBlockState();
        ScaffoldCandidate support = findTemporarySupport(state, material);
        if (support != null) {
            if (scaffolds.ownedCount() == 0) home = start;
            beginPlacement(support.position(), state, material, support);
            return true;
        }
        List<ScaffoldCandidate> candidates = new ArrayList<>();
        for (Direction side : Direction.Plane.HORIZONTAL) {
            for (int rise = 0; rise <= 1; rise++) {
                BlockPos at = new BlockPos(start.x() + side.getStepX(), start.y16() / 16 - 1 + rise,
                    start.z() + side.getStepZ());
                if (!temporaryCell(at)) continue;
                Node stand = new Node(at.getX(), (at.getY() + 1) * 16, at.getZ());
                double score = approachScore(stand);
                if (score >= approachScore(start)) continue;
                ConstructionTerrain view = new ConstructionTerrain(level, at, state);
                var standing = player.getDimensions(Pose.STANDING);
                MinecraftTerrain forecast = new MinecraftTerrain(view, player, view::hasChunk,
                    Math.max(standing.width(), player.getBbWidth()) / 2,
                    Math.max(standing.height(), player.getBbHeight()), () -> Navigation.activeExclusions(false));
                Move move = rise == 0 ? Move.WALK : Move.JUMP;
                if (!forecast.canTraverse(start, stand, move) || !forecast.canTraverse(stand, start, rise == 0 ? Move.WALK : Move.DROP)
                    || placement.find(at, state, material, player.getEyePosition(), this::desired) == null) continue;
                candidates.add(new ScaffoldCandidate(at, stand, score));
            }
        }
        if (candidates.isEmpty()) return false;
        candidates.sort(Comparator.comparingDouble(ScaffoldCandidate::score));
        ScaffoldCandidate best = candidates.getFirst();
        if (scaffolds.ownedCount() == 0) home = start;
        beginPlacement(best.position(), state, material, best);
        return true;
    }

    private ScaffoldCandidate findTemporarySupport(BlockState state, Supply material) {
        if (!wanted.canSurvive(level, buildTarget)) return null;
        Vec3 eye = player.getEyePosition();
        double reach = player.blockInteractionRange();
        for (Direction face : Direction.values()) {
            if (wanted.hasProperty(BlockStateProperties.AXIS)
                && wanted.getValue(BlockStateProperties.AXIS) != face.getAxis()) continue;
            BlockPos support = buildTarget.relative(face.getOpposite());
            if (!temporaryCell(support)) continue;
            Vec3 surface = Vec3.atCenterOf(support).relative(face, .5);
            if (surface.distanceToSqr(eye) > reach * reach) continue;
            var view = new ConstructionTerrain(level, support, state);
            BlockHitResult hit = view.clip(new ClipContext(eye, surface.relative(face.getOpposite(), .003),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(support)
                && hit.getDirection() == face && placement.find(support, state, material, eye, this::desired) != null)
                return new ScaffoldCandidate(support, null, 0);
        }
        return null;
    }

    private boolean temporaryCell(BlockPos at) {
        return ConstructionPlacement.loaded(level, at) && Node.validColumn(at.getX()) && Node.validColumn(at.getZ())
            && at.getY() >= -2048 && at.getY() < 2048 && !plan.containsKey(cell(at))
            && level.getBlockState(at).isAir() && !player.getBoundingBox().intersects(new AABB(at));
    }

    private double approachScore(Node node) {
        double y = buildTarget.getY() - node.y();
        return Math.hypot(node.x() - buildTarget.getX(), node.z() - buildTarget.getZ()) + Math.abs(y) * 2;
    }

    private void completeTarget() {
        unfinished--;
        layerProgress = true;
        resetAction();
        phase = Phase.SELECT;
        status = "Building layer Y=" + layers.get(layer).y() + ". " + unfinished + " targets remaining.";
    }

    private void defer(String reason) {
        if (buildTarget != null) deferred.addLast(cell(buildTarget));
        if (firstBlocked == null) firstBlocked = reason;
        releasePending();
        resetAction();
        phase = Phase.SELECT;
        status = reason;
    }

    private void beginCleanup(String reason) {
        buildResult = reason;
        phase = Phase.CLEANUP;
    }

    private void beginAudit() {
        audit = plan.entrySet().iterator();
        unfinished = 0;
        viewOwned = false;
        phase = Phase.AUDIT;
    }

    private void audit() {
        for (int checked = 0; checked < 256 && audit.hasNext(); checked++) {
            var entry = audit.next();
            BlockPos at = pos(entry.getKey());
            if (!ConstructionPlacement.loaded(level, at) || level.getBlockState(at) != desired(at)) unfinished++;
        }
        if (audit.hasNext()) return;
        if (buildResult == null) buildResult = unfinished == 0 ? "Blueprint finished." : "Blueprint has unmatched block states.";
        finish(buildResult);
    }

    private void selectCleanup() {
        viewOwned = false;
        Cell cell = scaffolds.nextCleanupCell().orElse(null);
        if (cell == null) {
            if (cleanupOnly) finish(buildResult);
            else beginAudit();
            return;
        }
        target = pos(cell);
        if (!ConstructionPlacement.loaded(level, target)) {
            scaffolds.loseObservation(cell);
            unverifiedScaffolds++;
            return;
        }
        targetState = level.getBlockState(target);
        if (scaffolds.ownedObservation(cell).filter(owned -> owned.state().equals(BlueprintBlockStates.encode(targetState))).isEmpty()) {
            scaffolds.loseObservation(cell);
            unverifiedScaffolds++;
            return;
        }
        resetAction();
        stands = findStands(target, true);
        standIndex = 0;
        if (!startExitCheck() && !walkNext(WalkPurpose.CLEANUP_STAND)) stopCleanup("The owned scaffold is out of safe reach.");
    }

    private boolean startExitCheck() {
        if (breakHit() == null) return false;
        exit = new ScaffoldExit(level, player, target, home);
        phase = Phase.CHECK_EXIT;
        status = "Checking an exit with the scaffold removed.";
        return true;
    }

    private void checkExit() {
        switch (exit.advance()) {
            case FOUND -> {
                if (!exit.remainsSafe() || breakHit() == null) { nextCleanupStand(); return; }
                var attempt = scaffolds.beginRemoval(cleanupCheck(true));
                pendingRemoval = attempt.removal().orElse(null);
                if (pendingRemoval == null) { stopCleanup("The scaffold's ownership or exit changed."); return; }
                beginAim(Phase.AIM_BREAK);
            }
            case FAILED, PARTIAL -> nextCleanupStand();
            case SEARCHING -> { }
        }
    }

    private void nextCleanupStand() {
        if (!walkNext(WalkPurpose.CLEANUP_STAND)) stopCleanup("No verified way out remains after removing this scaffold.");
    }

    private ScaffoldLedger.CleanupCheck cleanupCheck(boolean reachable) {
        Cell cell = cell(target);
        return new ScaffoldLedger.CleanupCheck(cell, currentOwned(cell), reachable, exit.remainsSafe());
    }

    private Optional<ScaffoldLedger.Observation> currentOwned(Cell cell) {
        if (!ConstructionPlacement.loaded(level, target)) return Optional.empty();
        String state = BlueprintBlockStates.encode(level.getBlockState(target));
        return scaffolds.ownedObservation(cell).filter(owned -> state.equals(owned.state()));
    }

    private Aim breakHit() {
        if (!ConstructionPlacement.loaded(level, target) || !player.onGround() || level.getBlockState(target) != targetState
            || !level.mayInteract(player, target)
            || player.getBoundingBox().expandTowards(0, -.12, 0).intersects(new AABB(target))) return null;
        return placement.breakHit(target, player.getEyePosition());
    }

    private void aimBreak() {
        Aim aim = breakHit();
        if (++wait > 45 || aim == null || scaffolds.recheckRemoval(pendingRemoval, cleanupCheck(aim != null))
            != ScaffoldLedger.CleanupStatus.READY) { stopCleanup("Cleanup reach, ownership or exit changed."); return; }
        BlockHitResult hit = settleAim(aim);
        if (hit == null || pauseForTool()) return;
        if (gameMode.isDestroying()) { stopCleanup("Another action controls mining."); return; }
        recording = Action.BREAK;
        boolean started;
        try {
            started = gameMode.startDestroyBlock(target, hit.getDirection());
        } finally {
            recording = Action.NONE;
        }
        if (!started || sentSequence < 0) { stopCleanup("The game refused to break the owned scaffold."); return; }
        player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        ownsMine = gameMode.isDestroying();
        if (level.getBlockState(target).isAir() || !ownsMine) waitBreak();
        else {
            phase = Phase.BREAK;
            wait = 0;
        }
    }

    private void continueBreak() {
        if (invalidated || !exit.remainsSafe()) { stopCleanup("Cleanup safety changed while mining."); return; }
        if (level.getBlockState(target).isAir()) { waitBreak(); return; }
        if (pauseForTool()) return;
        Aim aim = breakHit();
        if (aim == null || ++wait > 1200 || !gameMode.isDestroying()
            || scaffolds.recheckRemoval(pendingRemoval, cleanupCheck(true)) != ScaffoldLedger.CleanupStatus.READY) {
            stopCleanup("Scaffold mining was interrupted.");
            return;
        }
        BlockHitResult hit = settleAim(aim);
        if (hit == null) return;
        recording = Action.BREAK;
        try {
            if (gameMode.continueDestroyBlock(target, hit.getDirection()))
                player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        } finally {
            recording = Action.NONE;
        }
        if (level.getBlockState(target).isAir() || !gameMode.isDestroying()) waitBreak();
    }

    private boolean pauseForTool() {
        String reason = ToolDurability.pauseReason(player, level, target, targetState, ToolDurability.configuredReserve());
        if (reason == null) return false;
        stopMining();
        releasePending();
        resetAction();
        phase = Phase.PAUSED_TOOL;
        status = "Paused. " + reason;
        return true;
    }

    private void resumeAfterTool() {
        if (!player.onGround()) return;
        if (currentOwned(cell(target)).isPresent()
            && !ToolDurability.replacementReady(player, level, target, targetState, ToolDurability.configuredReserve())) return;
        phase = Phase.CLEANUP;
    }

    private void waitBreak() {
        stopMining();
        phase = Phase.WAIT_BREAK;
        wait = 0;
        status = "Waiting for scaffold removal confirmation.";
    }

    private void confirmBreak() {
        if (++wait > CONFIRM_TICKS || invalidated || !ConstructionPlacement.loaded(level, target)) {
            stopCleanup("Scaffold removal could not be confirmed.");
            return;
        }
        if (acknowledged < sentSequence || verifiedRevision == 0) return;
        BlockState current = level.getBlockState(target);
        if (!current.isAir() || !scaffolds.confirmOwnRemoval(pendingRemoval, observation(current, verifiedRevision))) {
            stopCleanup("The scaffold changed or the server rejected its removal.");
            return;
        }
        pendingRemoval = null;
        phase = Phase.CLEANUP;
    }

    private void stopCleanup(String reason) {
        buildResult = (buildResult == null ? "" : buildResult + " ") + reason;
        stopMining();
        releasePending();
        if (cleanupOnly) finish(buildResult);
        else beginAudit();
    }

    private List<Node> findStands(BlockPos around, boolean breaking) {
        MinecraftTerrain terrain = new MinecraftTerrain(level, player);
        List<Node> candidates = new ArrayList<>();
        double reach = Math.min(player.blockInteractionRange(), 6);
        int radius = (int) Math.ceil(reach);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > reach * reach || dx == 0 && dz == 0) continue;
                int x = around.getX() + dx, z = around.getZ() + dz;
                for (Node node : terrain.floors(x, z, (around.getY() - radius - 1) * 16, (around.getY() + radius) * 16)) {
                    if (!terrain.canFinish(node)) continue;
                    if (player.position().distanceToSqr(node.centerX(), node.y(), node.centerZ()) < .18) continue;
                    Vec3 eye = new Vec3(node.centerX(), node.y() + player.getEyeHeight(), node.centerZ());
                    if (breaking ? placement.breakHit(around, eye) != null : placement.potentiallyInReach(around, eye))
                        candidates.add(node);
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(node -> player.position().distanceToSqr(node.centerX(), node.y(), node.centerZ())));
        return List.copyOf(candidates.subList(0, Math.min(MAX_STANDS, candidates.size())));
    }

    private boolean walkNext(WalkPurpose purpose) {
        return standIndex < stands.size() && requestWalk(stands.get(standIndex++), purpose);
    }

    private boolean requestWalk(Node stand, WalkPurpose purpose) {
        viewOwned = false;
        if (!Navigation.get().requestOwned(owner, new Goal(stand.x(), stand.y16(), stand.z(), 0), WALK)) return false;
        walkPurpose = purpose;
        phase = Phase.WALK;
        status = "Planning a path to safe work footing.";
        return true;
    }

    private void walk() {
        switch (Navigation.get().ownedState(owner)) {
            case "ARRIVED" -> {
                Navigation.get().cancelOwned(owner);
                switch (walkPurpose) {
                    case BUILD_STAND -> {
                        Supply material = supply(wanted.getBlock());
                        if (material != null && safeFooting()
                            && !player.getBoundingBox().intersects(new AABB(buildTarget))
                            && placement.find(buildTarget, wanted, material, player.getEyePosition(), this::desired) != null)
                            beginPlacement(buildTarget, wanted, material, null);
                        else tryBuildStand();
                    }
                    case SCAFFOLD_STEP -> phase = Phase.TRY_PLACE;
                    case CLEANUP_STAND -> { if (!startExitCheck()) nextCleanupStand(); }
                }
            }
            case "FAILED" -> {
                switch (walkPurpose) {
                    case BUILD_STAND -> tryBuildStand();
                    case SCAFFOLD_STEP -> defer("Could not walk to the temporary step.");
                    case CLEANUP_STAND -> nextCleanupStand();
                }
            }
            case "PAUSED", "STOPPED", "IDLE" -> finish("Movement stopped before the next work position.");
            default -> status = Navigation.get().ownedStatus(owner);
        }
    }

    private Supply supply(Block block) {
        Inventory inventory = player.getInventory();
        int selected = inventory.getSelectedSlot();
        Supply current = material(player.getMainHandItem(), InteractionHand.MAIN_HAND, selected, block);
        if (current != null) return current;
        Supply offhand = material(player.getOffhandItem(), InteractionHand.OFF_HAND, -1, block);
        if (offhand != null) return offhand;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            if (slot == selected) continue;
            Supply candidate = material(inventory.getItem(slot), InteractionHand.MAIN_HAND, slot, block);
            if (candidate != null) return candidate;
        }
        return null;
    }

    private Supply scaffoldSupply(Node start) {
        BlockPos support = new BlockPos(start.x(), start.y16() / 16 - 1, start.z());
        Supply offhand = material(player.getOffhandItem(), InteractionHand.OFF_HAND, -1, scaffoldMaterial);
        if (plainScaffold(offhand, support)) return offhand;
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            Supply candidate = material(inventory.getItem(slot), InteractionHand.MAIN_HAND, slot, scaffoldMaterial);
            if (plainScaffold(candidate, support)) return candidate;
        }
        return null;
    }

    private boolean plainScaffold(Supply candidate, BlockPos at) {
        if (candidate == null || candidate.item().getClass() != BlockItem.class
            || candidate.item().getBlock().getClass() != Block.class) return false;
        BlockState state = candidate.item().getBlock().defaultBlockState();
        return state.getDestroySpeed(level, at) >= 0 && state.isCollisionShapeFullBlock(level, at)
            && state.getFluidState().isEmpty() && !state.hasBlockEntity();
    }

    private static Supply material(ItemStack stack, InteractionHand hand, int slot, Block required) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item) || required != null && item.getBlock() != required)
            return null;
        return new Supply(hand, slot, stack, item);
    }

    private SupplySelection selectSupply() {
        if (supply.hand() == InteractionHand.OFF_HAND)
            return player.getOffhandItem().getItem() == supply.item() ? SupplySelection.READY : SupplySelection.UNAVAILABLE;
        Inventory inventory = player.getInventory();
        if (Inventory.isHotbarSlot(supply.slot())) {
            inventory.setSelectedSlot(supply.slot());
            ownedSlot = supply.slot();
            return player.getMainHandItem().getItem() == supply.item() ? SupplySelection.READY : SupplySelection.UNAVAILABLE;
        }
        if (player.containerMenu != player.inventoryMenu || !player.inventoryMenu.getCarried().isEmpty())
            return SupplySelection.UNAVAILABLE;
        for (var slot : player.inventoryMenu.slots) {
            if (slot.container != inventory || slot.getContainerSlot() != supply.slot()) continue;
            ownedSlot = inventory.getSelectedSlot();
            gameMode.handleContainerInput(player.inventoryMenu.containerId, slot.index, ownedSlot, ContainerInput.SWAP, player);
            return player.getMainHandItem().getItem() == supply.item() ? SupplySelection.READY : SupplySelection.WAITING;
        }
        return SupplySelection.UNAVAILABLE;
    }

    private BlockState desired(BlockPos at) {
        if (!Node.validColumn(at.getX()) || !Node.validColumn(at.getZ()) || at.getY() < -2048 || at.getY() >= 2048) return null;
        String state = plan.get(cell(at));
        if (state == null) return null;
        if (!resolved.containsKey(state)) resolved.put(state, BlueprintBlockStates.resolve(state));
        return resolved.get(state);
    }

    private void beginAim(Phase next) {
        wait = 0;
        viewSettled = false;
        phase = next;
    }

    private BlockHitResult settleAim(Aim aim) {
        float yaw = Mth.wrapDegrees(aim.yaw() - player.getYRot()), pitch = aim.pitch() - player.getXRot();
        player.setYRot(player.getYRot() + Math.clamp(yaw, -35, 35));
        player.setXRot(player.getXRot() + Math.clamp(pitch, -30, 30));
        ownedYaw = player.getYRot();
        ownedPitch = player.getXRot();
        viewOwned = true;
        boolean aligned = Math.abs(yaw) < .12 && Math.abs(pitch) < .12;
        boolean ready = viewSettled && aligned;
        viewSettled = aligned;
        if (!ready) return null;
        HitResult looked = player.pick(player.blockInteractionRange(), 1, false);
        return looked instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && !hit.isInside()
            && hit.getBlockPos().equals(aim.hit().getBlockPos()) && hit.getDirection() == aim.hit().getDirection() ? hit : null;
    }

    private boolean viewMoved() {
        return viewOwned && (Math.abs(Mth.wrapDegrees(player.getYRot() - ownedYaw)) > .4f
            || Math.abs(player.getXRot() - ownedPitch) > .4f);
    }

    private boolean safeFooting() {
        return player.onGround() && new MinecraftTerrain(level, player).startAtPlayer() != null;
    }

    private void resetAction() {
        sentSequence = -1;
        verifiedRevision = 0;
        invalidated = false;
        viewOwned = viewSettled = false;
    }

    private void releasePending() {
        if (pendingPlacement != null) {
            scaffolds.abandonPlacement(pendingPlacement);
            if (sentSequence >= 0) unverifiedScaffolds++;
        }
        if (pendingRemoval != null) scaffolds.releaseFailedRemoval(pendingRemoval, currentOwned(pendingRemoval.cell()));
        pendingPlacement = null;
        pendingRemoval = null;
    }

    private void stopMining() {
        if (ownsMine && sameWorld(Minecraft.getInstance()) && gameMode.isDestroying()) gameMode.stopDestroyBlock();
        ownsMine = false;
    }

    private void finish(String reason) {
        stopMining();
        releasePending();
        Navigation.get().cancelOwned(owner);
        if (sameWorld(Minecraft.getInstance()) && player.getInventory().getSelectedSlot() == ownedSlot)
            player.getInventory().setSelectedSlot(originalSlot);
        phase = Phase.IDLE;
        recording = Action.NONE;
        resetAction();
        remaining.clear();
        deferred.clear();
        layers = List.of();
        plan = Map.of();
        resolved.clear();
        audit = null;
        status = reason + " " + unfinished + " blueprint blocks unfinished."
            + (scaffolds.ownedCount() == 0 ? "" : " " + scaffolds.ownedCount() + " confirmed temporary blocks remain.")
            + (unverifiedScaffolds == 0 ? "" : " Ownership could not be verified at " + unverifiedScaffolds + " temporary block positions.");
    }

    private void forgetWorld() {
        unverifiedScaffolds += scaffolds.ownedCount();
        scaffolds.clear();
        if (active()) finish("World or player changed.");
        level = null;
        player = null;
        gameMode = null;
        connection = null;
        acknowledged = -1;
    }

    private boolean sameWorld(Minecraft mc) {
        return level != null && mc.level == level && player != null && mc.player == player && mc.gameMode == gameMode
            && player.isAlive() && !player.isRemoved() && player.mayBuild() && !player.isSpectator();
    }

    public boolean ownsBreaking() {
        Minecraft mc = Minecraft.getInstance();
        return phase == Phase.BREAK && ownsMine && sameWorld(mc) && mc.gui.screen() == null
            && !mc.options.keyAttack.isDown() && !mc.options.keyUse.isDown();
    }

    @Override
    public void packetSent(Connection source, Packet<?> outbound) {
        if (source != connection || target == null) return;
        if (recording == Action.PLACE && outbound instanceof ServerboundUseItemOnPacket packet
            && packet.hand() == supply.hand() && (packet.hitResult().getBlockPos().equals(target)
                || packet.hitResult().getBlockPos().relative(packet.hitResult().getDirection()).equals(target))) {
            sentSequence = packet.sequence();
        } else if (recording == Action.BREAK && outbound instanceof ServerboundPlayerActionPacket packet
            && packet.getPos().equals(target) && (packet.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                || packet.getAction() == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK)) {
            sentSequence = packet.getSequence();
        }
    }

    public void acknowledge(ClientLevel appliedLevel, int sequence) {
        if (appliedLevel == level) acknowledged = Math.max(acknowledged, sequence);
    }

    public void serverBlock(ClientLevel appliedLevel, BlockPos at, BlockState state) {
        if (appliedLevel != level || at.getY() < -2048 || at.getY() >= 2048
            || !Node.validColumn(at.getX()) || !Node.validColumn(at.getZ())) return;
        Cell cell = cell(at);
        boolean pending = sentSequence >= 0 && at.equals(target) && (pendingPlacement != null || pendingRemoval != null);
        boolean owned = scaffolds.ownedObservation(cell).isPresent();
        if (!pending && !owned) return;
        long update = ++revision;
        if (pending && (pendingPlacement != null ? state == targetState : state.isAir())) {
            verifiedRevision = update;
            return;
        }
        scaffolds.observeBlockChange(cell, observation(state, update));
        if (owned && scaffolds.ownedObservation(cell).isEmpty()) unverifiedScaffolds++;
        if (pending) invalidated = true;
    }

    public void chunkChanged(ClientLevel appliedLevel, ChunkPos chunk) {
        if (appliedLevel != level) return;
        int previouslyOwned = scaffolds.ownedCount();
        scaffolds.loseChunk(chunk.x(), chunk.z());
        unverifiedScaffolds += previouslyOwned - scaffolds.ownedCount();
        if (target != null && chunk.contains(target) && (pendingPlacement != null || pendingRemoval != null)) invalidated = true;
    }

    @Override
    public void disconnected() { forgetWorld(); }

    @Override
    public void mouseClick(ClientEvents.Action action) {
        if (active() && phase != Phase.PAUSED_TOOL && action == ClientEvents.Action.PRESS && Minecraft.getInstance().gui.screen() == null)
            finish("You took control.");
    }

    @Override
    public void mouseScroll() {
        if (active() && phase != Phase.PAUSED_TOOL && Minecraft.getInstance().gui.screen() == null) finish("You changed the selected item.");
    }

    @Override
    public void keyInput(KeyEvent input, ClientEvents.Action action) {
        if (!active() || phase == Phase.PAUSED_TOOL || action == ClientEvents.Action.RELEASE || Minecraft.getInstance().gui.screen() != null) return;
        Options keys = Minecraft.getInstance().options;
        if (SopranoClient.manualMovementKey(keys, input) || keys.keyAttack.matches(input) || keys.keyUse.matches(input)
            || keys.keySwapOffhand.matches(input) || keys.keyDrop.matches(input)) {
            finish("You took control.");
            return;
        }
        for (var slot : keys.keyHotbarSlots) {
            if (slot.matches(input)) { finish("You changed the selected item."); return; }
        }
    }

    private static ScaffoldLedger.Observation observation(BlockState state, long revision) {
        return new ScaffoldLedger.Observation(BlueprintBlockStates.encode(state), revision);
    }

    private static BlockPos pos(Cell cell) { return new BlockPos(cell.x(), cell.y(), cell.z()); }
    private static Cell cell(BlockPos at) { return new Cell(at.getX(), at.getY(), at.getZ()); }
    private static String at(BlockPos pos) { return pos.getX() + ", " + pos.getY() + ", " + pos.getZ(); }
}
