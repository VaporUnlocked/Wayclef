package dev.soprano.game;

import dev.soprano.SopranoClient;
import dev.soprano.host.ClientEvents;
import dev.soprano.map.MapVolume;
import dev.soprano.path.Goal;
import dev.soprano.path.IncrementalSearch;
import dev.soprano.path.Node;
import dev.soprano.ui.PlanningScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.protocol.Packet;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Explicit, bounded replacement of plain cubes using the game's mining and placement routines. */
public final class ReplaceActivity implements ClientEvents.Listener {
    public record Preview(int matches, int available, int missing, int scanned, int unknown,
                          String source, String replacement, String status) { }

    private record Survey(Preview preview, Block source, BlockItem item, InteractionHand hand, List<BlockPos> blocks) { }
    private enum Phase { IDLE, ARMED, CHOOSE, WALK, AIM_BREAK, BREAK, PAUSED_TOOL, CONFIRM_BREAK, AIM_PLACE, CONFIRM_PLACE }
    private enum Action { NONE, BREAK, PLACE }

    private static final ReplaceActivity INSTANCE = new ReplaceActivity();
    private static final int MAX_VOLUME = 32_768;
    private static final int CONFIRM_TICKS = 100;
    private static final int MAX_BREAK_TICKS = 1_200;
    private static final int MAX_STAND_ATTEMPTS = 3;
    private static final Navigation.Options WALK_OPTIONS = new Navigation.Options(
        new IncrementalSearch.Config(true, false, 1, 64, 12_000, false), 96, true, true);

    private final Object owner = new Object();
    private Connection connection;
    private int acknowledged = -1;
    private Phase phase = Phase.IDLE;
    private Action recording = Action.NONE;
    private ClientLevel level;
    private LocalPlayer player;
    private MultiPlayerGameMode gameMode;
    private Block source;
    private BlockItem replacement;
    private InteractionHand replacementHand;
    private BlockPos target;
    private List<BlockPos> queue = List.of();
    private List<Node> stands = List.of();
    private int next, nextStand, replaced, skipped, unknown, wait, sentSequence = -1;
    private boolean aimSettled, ownsMine, openedHole, viewOwned;
    private float ownedYaw, ownedPitch;
    private String status = "No replace activity is running.";

    private ReplaceActivity() { }

    public static ReplaceActivity get() { return INSTANCE; }
    public void initialize() { ClientEvents.subscribe(this); }
    public boolean active() { return phase != Phase.IDLE; }
    public boolean ownsNavigation(Object token) { return token == owner; }
    public int blocksProcessed() { return replaced; }
    public String statusText() { return status; }

    /** The vanilla idle attack handler must leave this activity's progressive break alone. */
    public boolean ownsBreaking() {
        if (!ownsMine || phase != Phase.BREAK) return false;
        Minecraft mc = Minecraft.getInstance();
        return sameWorld(mc) && mc.gui.screen() == null && mc.isWindowActive()
            && !mc.options.keyAttack.isDown() && !mc.options.keyUse.isDown();
    }

    public Preview preview(MapVolume.Box region, BlockPos sourcePosition) {
        return survey(Minecraft.getInstance(), region, sourcePosition, false).preview();
    }

    public void start(MapVolume.Box region, BlockPos sourcePosition) {
        Minecraft mc = Minecraft.getInstance();
        if (active()) throw new IllegalStateException("Stop the active Replace job first.");
        if (BlueprintConstruction.get().active()) throw new IllegalStateException("Stop the blueprint build before starting Replace.");
        if (Navigation.get().movementReservedByOther(owner) || FollowController.get().active())
            throw new IllegalStateException("Stop the active navigation or Follow job first.");
        if (mc.gui.screen() != null && !(mc.gui.screen() instanceof PlanningScreen) && !(mc.gui.screen() instanceof ChatScreen))
            throw new IllegalStateException("Close the current game screen before starting Replace.");
        Survey survey = survey(mc, region, sourcePosition, true);
        if (survey.blocks().isEmpty()) throw new IllegalStateException("There are no matching exposed cubes in the loaded selection.");
        level = mc.level;
        player = mc.player;
        gameMode = mc.gameMode;
        acknowledged = -1;
        connection = player.connection.getConnection();
        source = survey.source();
        replacement = survey.item();
        replacementHand = survey.hand();
        queue = survey.blocks();
        unknown = survey.preview().unknown();
        replaced = skipped = next = 0;
        target = null;
        openedHole = viewOwned = false;
        phase = Phase.ARMED;
        status = "Starting Replace on " + queue.size() + " matching blocks.";
    }

    public void stop() {
        if (active()) finish("Replace stopped.");
    }

    private static Survey survey(Minecraft mc, MapVolume.Box region, BlockPos sourcePosition, boolean collect) {
        if (mc.level == null || mc.player == null || mc.gameMode == null || mc.player.isSpectator() || !mc.player.mayBuild())
            throw new IllegalStateException("Join a world where you can build first.");
        if (region == null || sourcePosition == null) throw new IllegalArgumentException("Set both area corners and select a source block.");
        int volume = checkedVolume(region);
        int ySize = region.maxY() - region.minY() + 1;
        if (!Node.validColumn(region.minX()) || !Node.validColumn(region.maxX())
            || !Node.validColumn(region.minZ()) || !Node.validColumn(region.maxZ())
            || mc.level.isOutsideBuildHeight(region.minY()) || mc.level.isOutsideBuildHeight(region.maxY()))
            throw new IllegalArgumentException("The selected area is outside the world build limits.");
        if (!loaded(mc.level, sourcePosition)) throw new IllegalStateException("The source block is outside loaded terrain.");

        BlockState sample = mc.level.getBlockState(sourcePosition);
        if (!plainCube(mc.level, sourcePosition, sample) || !exposed(mc.level, sourcePosition))
            throw new IllegalArgumentException("Select an exposed, breakable full cube as the source.");
        InteractionHand hand = InteractionHand.MAIN_HAND;
        ItemStack stack = mc.player.getItemInHand(hand);
        BlockItem item = replacementCandidate(mc.level, sourcePosition, sample.getBlock(), stack);
        if (item == null) {
            hand = InteractionHand.OFF_HAND;
            stack = mc.player.getItemInHand(hand);
            item = replacementCandidate(mc.level, sourcePosition, sample.getBlock(), stack);
        }
        if (item == null) throw new IllegalArgumentException("Hold a different plain full cube in either hand.");

        int known = 0, matches = 0;
        List<BlockPos> blocks = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = region.minX(); x <= region.maxX(); x++) {
            for (int z = region.minZ(); z <= region.maxZ(); z++) {
                if (!mc.level.hasChunk(x >> 4, z >> 4)) continue;
                known += ySize;
                for (int y = region.minY(); y <= region.maxY(); y++) {
                    cursor.set(x, y, z);
                    BlockState current = mc.level.getBlockState(cursor);
                    if (current.getBlock() != sample.getBlock() || !plainCube(mc.level, cursor, current)
                        || !mc.level.getWorldBorder().isWithinBounds(cursor) || !exposed(mc.level, cursor)
                        || !safeToRemove(mc.level, cursor)) continue;
                    matches++;
                    if (collect) blocks.add(cursor.immutable());
                }
            }
        }

        if (collect) blocks.sort(Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(mc.player.position())));
        int available = mc.player.isCreative() ? matches : stack.getCount();
        int missing = Math.max(0, matches - available);
        int unknown = volume - known;
        String detail = matches + " exposed matches. " + missing + " more blocks needed. Using "
            + (hand == InteractionHand.MAIN_HAND ? "main hand." : "offhand.");
        if (unknown != 0) detail += " " + unknown + " unloaded voxels excluded.";
        return new Survey(new Preview(matches, available, missing, known, unknown, id(sample.getBlock()),
            id(item.getBlock()), detail), sample.getBlock(), item, hand, List.copyOf(blocks));
    }

    private static int checkedVolume(MapVolume.Box region) {
        long x = (long) region.maxX() - region.minX() + 1;
        long y = (long) region.maxY() - region.minY() + 1;
        long z = (long) region.maxZ() - region.minZ() + 1;
        if (x > MAX_VOLUME || y > MAX_VOLUME || z > MAX_VOLUME || x * y * z > MAX_VOLUME)
            throw new IllegalArgumentException("Replace accepts an area of up to " + MAX_VOLUME + " voxels.");
        return (int) (x * y * z);
    }

    private static BlockItem replacementCandidate(ClientLevel level, BlockPos at, Block source, ItemStack stack) {
        if (!stack.isEmpty() && stack.getItem() instanceof BlockItem item && item.getClass() == BlockItem.class
            && item.getBlock() != source && plainCube(level, at, item.getBlock().defaultBlockState())) return item;
        return null;
    }

    private static String id(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    private static boolean loaded(ClientLevel level, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos) && level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private static boolean plainCube(ClientLevel level, BlockPos pos, BlockState state) {
        return state.getBlock().getClass() == Block.class && !state.hasBlockEntity()
            && state.getFluidState().isEmpty() && state.getDestroySpeed(level, pos) >= 0
            && state.isCollisionShapeFullBlock(level, pos);
    }

    private static boolean exposed(ClientLevel level, BlockPos pos) {
        for (Direction side : Direction.values()) {
            BlockPos adjacent = pos.relative(side);
            if (loaded(level, adjacent) && level.getBlockState(adjacent).isAir()) return true;
        }
        return false;
    }

    private static boolean safeToRemove(ClientLevel level, BlockPos pos) {
        BlockPos above = pos.above();
        if (loaded(level, above) && level.getBlockState(above).getBlock() instanceof FallingBlock) return false;
        for (Direction side : Direction.values()) {
            BlockPos adjacent = pos.relative(side);
            if (loaded(level, adjacent) && !level.getBlockState(adjacent).getFluidState().isEmpty()) return false;
        }
        return true;
    }

    @Override
    public void tickPost() {
        if (!active()) return;
        Minecraft mc = Minecraft.getInstance();
        if (!sameWorld(mc)) { finish("World or player changed."); return; }
        if (Navigation.get().movementReservedByOther(owner)) { finish("Another tool took control of movement."); return; }
        if (phase == Phase.PAUSED_TOOL && (mc.gui.screen() != null || !mc.isWindowActive()
            || !mc.mouseHandler.isMouseGrabbed() || SopranoClient.manualInteractionHeld(mc.options) || player.isUsingItem())) return;
        if (phase == Phase.ARMED && (mc.gui.screen() instanceof PlanningScreen || mc.gui.screen() instanceof ChatScreen)) return;
        if (mc.gui.screen() != null || !mc.isWindowActive() || !mc.mouseHandler.isMouseGrabbed()) {
            finish("Replace stopped when the game lost focus or opened a screen."); return;
        }
        if (SopranoClient.manualInteractionHeld(mc.options) || player.isUsingItem() || movedView()) { finish("You took control."); return; }
        if (player.isPassenger() || player.isFallFlying() || player.getAbilities().flying
            || player.isInWater() || player.isInLava()) {
            finish("Replace needs normal ground controls.");
            return;
        }
        if (!heldReplacement() && phase != Phase.CONFIRM_PLACE) { finish("Hold the original replacement block to continue."); return; }

        try {
            switch (phase) {
                case ARMED -> phase = Phase.CHOOSE;
                case CHOOSE -> choose();
                case WALK -> walk();
                case AIM_BREAK -> aimBreak();
                case BREAK -> continueBreak();
                case PAUSED_TOOL -> resumeAfterTool();
                case CONFIRM_BREAK -> confirmBreak();
                case AIM_PLACE -> aimPlace();
                case CONFIRM_PLACE -> confirmPlace();
                case IDLE -> { }
            }
        } catch (IllegalStateException invalid) {
            finish(invalid.getMessage());
        }
    }

    private boolean sameWorld(Minecraft mc) {
        return player != null && level == mc.level && player == mc.player && gameMode == mc.gameMode
            && player.isAlive() && !player.isRemoved() && player.mayBuild() && !player.isSpectator();
    }

    private boolean movedView() {
        return viewOwned && (Math.abs(Mth.wrapDegrees(player.getYRot() - ownedYaw)) > .4f
            || Math.abs(player.getXRot() - ownedPitch) > .4f);
    }

    private boolean heldReplacement() {
        ItemStack stack = player.getItemInHand(replacementHand);
        return !stack.isEmpty() && stack.getItem() == replacement;
    }

    private void choose() {
        viewOwned = false;
        if (next == queue.size()) {
            finish(skipped == 0 && unknown == 0 ? "Replace finished." : "Replace finished with exclusions.");
            return;
        }
        target = queue.get(next++);
        if (!sourceIntact()) { skip("The next source changed."); return; }
        if (currentBreakHit() != null) { beginAim(Phase.AIM_BREAK); return; }
        if (!player.onGround()) { skip("Cannot reach the next source from safe footing."); return; }
        stands = findStands();
        nextStand = 0;
        walkToStand();
    }

    private void walk() {
        String state = Navigation.get().ownedState(owner);
        switch (state) {
            case "ARRIVED" -> {
                Navigation.get().cancelOwned(owner);
                if (currentBreakHit() == null) walkToStand();
                else beginAim(Phase.AIM_BREAK);
            }
            case "FAILED" -> walkToStand();
            case "PAUSED", "STOPPED", "IDLE" -> finish("Navigation stopped before reaching the next source.");
            default -> status = "Walking to the next replacement. " + Navigation.get().ownedStatus(owner);
        }
    }

    private void walkToStand() {
        if (!sourceIntact() || nextStand >= Math.min(MAX_STAND_ATTEMPTS, stands.size())) {
            skip("The next source has no reachable work position.");
            return;
        }
        Node stand = stands.get(nextStand++);
        if (!Navigation.get().requestOwned(owner, new Goal(stand.x(), stand.y16(), stand.z(), 0), WALK_OPTIONS)) {
            finish("Another tool owns navigation.");
            return;
        }
        phase = Phase.WALK;
        status = "Planning a path to the next replacement.";
    }

    private List<Node> findStands() {
        MinecraftTerrain terrain = new MinecraftTerrain(level, player);
        List<Node> choices = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0 || Math.abs(dx) + Math.abs(dz) > 3) continue;
                for (Node stand : terrain.floors(target.getX() + dx, target.getZ() + dz,
                    (target.getY() - 2) * 16, (target.getY() + 1) * 16)) {
                    Vec3 eye = new Vec3(stand.centerX(), stand.y() + player.getEyeHeight(), stand.centerZ());
                    if (breakHit(eye) != null && placementHit(eye, true) != null) choices.add(stand);
                }
            }
        }
        choices.sort(Comparator.comparingDouble(node ->
            player.position().distanceToSqr(node.centerX(), node.y(), node.centerZ())));
        return choices;
    }

    private boolean sourceIntact() {
        if (target == null || !loaded(level, target) || !level.mayInteract(player, target)
            || !level.getWorldBorder().isWithinBounds(target)) return false;
        BlockState current = level.getBlockState(target);
        return current.getBlock() == source && plainCube(level, target, current) && safeToRemove(level, target)
            && player.getMainHandItem().canDestroyBlock(current, level, target, player);
    }

    private BlockHitResult currentBreakHit() {
        if (!sourceIntact() || !player.onGround() || player.getBoundingBox().intersects(new AABB(target).expandTowards(0, .08, 0))
            || !level.isUnobstructed(replacement.getBlock().defaultBlockState(), target, CollisionContext.of(player))) return null;
        Vec3 eye = player.getEyePosition();
        BlockHitResult hit = breakHit(eye);
        return hit != null && placementHit(eye, true) != null ? hit : null;
    }

    private BlockHitResult breakHit(Vec3 eye) {
        BlockHitResult hit = trace(eye, Vec3.atCenterOf(target));
        return hit != null && hit.getBlockPos().equals(target) ? hit : null;
    }

    private BlockHitResult trace(Vec3 eye, Vec3 destination) {
        double reach = player.blockInteractionRange();
        if (eye.distanceToSqr(destination) > reach * reach) return null;
        BlockHitResult hit = level.clip(new ClipContext(eye, destination, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    private BlockHitResult placementHit(Vec3 eye, boolean beforeBreak) {
        for (Direction side : Direction.values()) {
            BlockPos support = target.relative(side);
            if (!loaded(level, support) || !level.mayInteract(player, support)
                || !level.getWorldBorder().isWithinBounds(support)) continue;
            if (!plainCube(level, support, level.getBlockState(support))) continue;
            Direction face = side.getOpposite();
            Vec3 center = Vec3.atCenterOf(support);
            Vec3 facePoint = center.relative(face, .5);
            if (eye.subtract(facePoint).dot(Vec3.atLowerCornerOf(face.getUnitVec3i())) <= 0) continue;
            BlockHitResult hit = trace(eye, center.relative(face, .49));
            if (hit == null) continue;
            if (beforeBreak && hit.getBlockPos().equals(target)) return hit;
            if (hit.getBlockPos().equals(support) && hit.getDirection() == face) return hit;
        }
        return null;
    }

    private void beginAim(Phase nextPhase) {
        wait = 0;
        aimSettled = false;
        phase = nextPhase;
    }

    private boolean aim(BlockHitResult hit) {
        Vec3 difference = hit.getLocation().subtract(player.getEyePosition());
        float yaw = (float) (Math.toDegrees(Math.atan2(difference.z, difference.x)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(difference.y, Math.hypot(difference.x, difference.z)));
        float yawDelta = Mth.wrapDegrees(yaw - player.getYRot());
        float pitchDelta = pitch - player.getXRot();
        player.setYRot(player.getYRot() + Math.clamp(yawDelta, -40, 40));
        player.setXRot(player.getXRot() + Math.clamp(pitchDelta, -35, 35));
        ownedYaw = player.getYRot();
        ownedPitch = player.getXRot();
        viewOwned = true;
        boolean aligned = Math.abs(yawDelta) < .15f && Math.abs(pitchDelta) < .15f;
        boolean ready = aligned && aimSettled;
        aimSettled = aligned;
        if (!ready) return false;
        HitResult looked = player.pick(player.blockInteractionRange(), 1, false);
        return looked instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK
            && block.getBlockPos().equals(hit.getBlockPos()) && block.getDirection() == hit.getDirection();
    }

    private void aimBreak() {
        BlockHitResult hit = currentBreakHit();
        if (hit == null || ++wait > 40) { skip("The next source lost clear reach or placement support."); return; }
        if (!aim(hit) || pauseForTool()) return;
        if (gameMode.isDestroying()) { finish("Another action controls mining."); return; }
        sentSequence = -1;
        recording = Action.BREAK;
        boolean started;
        try { started = gameMode.startDestroyBlock(target, hit.getDirection()); }
        finally { recording = Action.NONE; }
        if (!started) { skip("The game refused to start mining the next source."); return; }
        player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        ownsMine = gameMode.isDestroying();
        if (level.getBlockState(target).isAir() || !ownsMine) beginBreakConfirmation();
        else {
            phase = Phase.BREAK;
            wait = 0;
            status = "Replacing " + (replaced + 1) + ". Mining the source block.";
        }
    }

    private void continueBreak() {
        if (level.getBlockState(target).isAir()) { beginBreakConfirmation(); return; }
        if (pauseForTool()) return;
        BlockHitResult hit = currentBreakHit();
        if (hit == null || ++wait > MAX_BREAK_TICKS || !gameMode.isDestroying()) {
            finish("Mining was interrupted before the source could be replaced.");
            return;
        }
        if (!aim(hit)) return;
        recording = Action.BREAK;
        try {
            if (gameMode.continueDestroyBlock(target, hit.getDirection()))
                player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        } finally { recording = Action.NONE; }
        if (level.getBlockState(target).isAir() || !gameMode.isDestroying()) beginBreakConfirmation();
    }

    private boolean pauseForTool() {
        String reason = ToolDurability.pauseReason(player, level, target, level.getBlockState(target), ToolDurability.configuredReserve());
        if (reason == null) return false;
        if (ownsMine && gameMode.isDestroying()) gameMode.stopDestroyBlock();
        ownsMine = viewOwned = false;
        phase = Phase.PAUSED_TOOL;
        status = "Paused. " + reason;
        return true;
    }

    private void resumeAfterTool() {
        if (!loaded(level, target) || level.getBlockState(target).getBlock() != source) {
            skip("The source changed while waiting for a tool.");
            return;
        }
        if (!player.onGround() || !sourceIntact()
            || !ToolDurability.replacementReady(player, level, target, level.getBlockState(target), ToolDurability.configuredReserve())) return;
        if (currentBreakHit() != null) beginAim(Phase.AIM_BREAK);
        else {
            stands = findStands();
            nextStand = 0;
            walkToStand();
        }
    }

    private void beginBreakConfirmation() {
        openedHole = level.getBlockState(target).isAir();
        if (ownsMine && gameMode.isDestroying()) gameMode.stopDestroyBlock();
        ownsMine = false;
        phase = Phase.CONFIRM_BREAK;
        wait = 0;
        status = "Waiting for the game's block-break acknowledgement.";
    }

    private boolean confirmed() {
        return sentSequence >= 0 && acknowledged >= sentSequence;
    }

    private void confirmBreak() {
        if (++wait > CONFIRM_TICKS) { finish("The server did not confirm the break."); return; }
        if (!confirmed()) return;
        if (!loaded(level, target) || !level.getBlockState(target).isAir()) {
            finish("The server rejected the break or the block changed.");
            return;
        }
        openedHole = true;
        beginAim(Phase.AIM_PLACE);
    }

    private void aimPlace() {
        if (!loaded(level, target) || !level.mayInteract(player, target)
            || !level.getWorldBorder().isWithinBounds(target) || !level.getBlockState(target).isAir()
            || !player.onGround() || player.getBoundingBox().intersects(new AABB(target))) {
            finish("The replacement position is no longer clear.");
            return;
        }
        BlockHitResult hit = placementHit(player.getEyePosition(), false);
        if (hit == null || ++wait > 40) { finish("The placement face is no longer reachable."); return; }
        if (!aim(hit)) return;
        ItemStack stack = player.getItemInHand(replacementHand);
        BlockPlaceContext context = replacement.updatePlacementContext(new BlockPlaceContext(player, replacementHand, stack, hit));
        BlockState placement = context == null ? null : replacement.getBlock().getStateForPlacement(context);
        if (context == null || !context.getClickedPos().equals(target) || !context.canPlace() || placement == null
            || placement.getBlock() != replacement.getBlock() || !placement.canSurvive(level, target)
            || !level.isUnobstructed(placement, target, CollisionContext.of(player))) {
            finish("The game cannot place the replacement at the selected block.");
            return;
        }
        sentSequence = -1;
        recording = Action.PLACE;
        try {
            var result = gameMode.useItemOn(player, replacementHand, hit);
            if (!result.consumesAction()) { finish("The game refused to place the replacement."); return; }
            player.swing(replacementHand, SwingAnimation.DEFAULT, false);
        } finally { recording = Action.NONE; }
        phase = Phase.CONFIRM_PLACE;
        wait = 0;
        status = "Waiting for the game's placement acknowledgement.";
    }

    private void confirmPlace() {
        if (++wait > CONFIRM_TICKS) { finish("The server did not confirm placement."); return; }
        if (!confirmed()) return;
        if (!loaded(level, target) || level.getBlockState(target).getBlock() != replacement.getBlock()) {
            finish("The server rejected the replacement or the block changed.");
            return;
        }
        openedHole = false;
        replaced++;
        phase = Phase.CHOOSE;
        status = "Replaced " + replaced + " of " + queue.size() + " blocks.";
    }

    private void skip(String reason) {
        skipped++;
        phase = Phase.CHOOSE;
        status = reason + " Skipped " + skipped + ".";
    }

    private void finish(String reason) {
        if (ownsMine && sameWorld(Minecraft.getInstance()) && gameMode.isDestroying()) gameMode.stopDestroyBlock();
        ownsMine = viewOwned = false;
        Navigation.get().cancelOwned(owner);
        phase = Phase.IDLE;
        recording = Action.NONE;
        connection = null;
        queue = List.of();
        stands = List.of();
        target = null;
        player = null;
        level = null;
        gameMode = null;
        status = reason + " Replaced " + replaced + ", skipped " + skipped + "."
            + (unknown == 0 ? "" : " " + unknown + " unloaded voxels excluded.")
            + (openedHole ? " Check the last block for an unfilled position." : "");
    }

    @Override
    public void packetSent(Connection source, Packet<?> outbound) {
        if (source != connection) return;
        if (recording == Action.BREAK && outbound instanceof ServerboundPlayerActionPacket packet
            && packet.getPos().equals(target) && (packet.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
            || packet.getAction() == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK)) {
            sentSequence = packet.getSequence();
        } else if (recording == Action.PLACE && outbound instanceof ServerboundUseItemOnPacket packet
            && packet.hand() == replacementHand
            && packet.hitResult().getBlockPos().relative(packet.hitResult().getDirection()).equals(target)) {
            sentSequence = packet.sequence();
        }
    }

    /** Called after the client applies the server acknowledgement and resolves local prediction. */
    public void acknowledge(ClientLevel appliedLevel, int sequence) {
        if (active() && level == appliedLevel) acknowledged = Math.max(acknowledged, sequence);
    }

    @Override
    public void screenChanged(Screen screen) {
        if (active() && phase != Phase.PAUSED_TOOL && screen != null
            && !(phase == Phase.ARMED && screen instanceof PlanningScreen)) finish("You opened a game screen.");
    }

    @Override
    public void disconnected() {
        if (active()) finish("Left the world.");
    }

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

}
