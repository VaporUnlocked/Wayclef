package dev.soprano.game;

import dev.soprano.path.Goal;
import dev.soprano.path.Move;
import dev.soprano.path.Node;
import dev.soprano.path.RouteExclusions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Opens a verified local walking edge within the route's action budget. */
final class RouteEdits {
    enum Status { WORKING, CONFIRMED, PAUSED, FAILED }
    record Usage(int breaks, int placements) { }
    record Motion(Input input, Vec2 direction) { }
    record ToolPause(BlockPos target, BlockState state, int reserve) {
        boolean replacementReady(ClientLevel level, LocalPlayer player) {
            return !loaded(level, target) || level.getBlockState(target) != state
                || ToolDurability.replacementReady(player, level, target, state, reserve);
        }
    }
    private enum Phase { EDGE, AIM, BREAK, ACK }
    record Change(BlockPos target, BlockState before, BlockState after, ConstructionPlacement.Supply supply) {
        boolean breaking() { return supply == null; }
    }
    private record Candidate(Node from, Node to, List<Change> changes, Vec3 edge) {
        Change first() { return changes.getFirst(); }
    }

    private static final double EDGE_OFFSET = .58;
    private static final double STILL_SPEED = .06;
    private static final Motion HOLD_SNEAK = new Motion(new Input(false, false, false, false, false, true, false), Vec2.ZERO);

    private Candidate candidate;
    private Phase phase;
    private ClientLevel level;
    private LocalPlayer player;
    private MultiPlayerGameMode gameMode;
    private Connection connection;
    private boolean capturing;
    private int breakCount, placementCount, waitTicks, sequence = -1, acknowledged = -1;
    private int originalSlot = -1, selectedSlot = -1;
    private Motion motion;
    private ToolPause toolPause;
    private String detail;

    Usage usage() { return new Usage(breakCount, placementCount); }
    void setUsage(Usage usage) { breakCount = usage.breaks(); placementCount = usage.placements(); }
    String detail() { return detail; }
    ToolPause toolPause() { return toolPause; }

    boolean canEdit(RoutePermissions permissions) {
        return breakCount < permissions.breakLimit() || placementCount < permissions.placeLimit();
    }

    boolean begin(Minecraft mc, MinecraftTerrain terrain, Goal goal, Navigation.Options options) {
        if (!canEdit(options.permissions()) || mc.gameMode == null || !mc.player.onGround()) return false;
        Node from = terrain.startAtPlayer();
        if (from == null || (from.y16() & 15) != 0 || !terrain.canFinish(from)
            || Math.hypot(from.centerX() - mc.player.getX(), from.centerZ() - mc.player.getZ()) > .42) return false;
        var helper = new ConstructionPlacement(mc.level, mc.player);
        BlockPos protectedBlock = goal.observedBlockId() == null ? null : new BlockPos(goal.x(), goal.sightY(), goal.z());
        Candidate best = null;
        double fromScore = distance(from, goal);
        double bestScore = Double.POSITIVE_INFINITY;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            var feet = new BlockPos(from.x(), from.y16() / 16, from.z()).relative(direction);
            if (!Node.validColumn(feet.getX()) || !Node.validColumn(feet.getZ())) continue;
            var to = new Node(feet.getX(), from.y16(), feet.getZ());
            double score = distance(to, goal);
            if (score >= fromScore || !loaded(mc.level, feet) || !terrain.selectedBody(to)
                || terrain.canTraverse(from, to, Move.WALK)) continue;
            Candidate edit = findEdit(mc, terrain, helper, from, to, feet, protectedBlock, options);
            if (edit == null) continue;
            double cost = score + (edit.changes().size() - 1) * .25;
            if (cost < bestScore) {
                best = edit;
                bestScore = cost;
            }
        }
        if (best == null) return false;
        candidate = best;
        phase = best.edge() == null ? Phase.AIM : Phase.EDGE;
        level = mc.level;
        player = mc.player;
        gameMode = mc.gameMode;
        connection = player.connection.getConnection();
        waitTicks = 0;
        sequence = acknowledged = -1;
        originalSlot = selectedSlot = -1;
        motion = null;
        detail = phase == Phase.EDGE ? "Approaching the verified bridge edge."
            : "Preparing to " + (best.first().breaking() ? "break " : "place ") + best.first().target().toShortString() + ".";
        return true;
    }

    private Candidate findEdit(Minecraft mc, MinecraftTerrain terrain, ConstructionPlacement helper, Node from, Node to,
                               BlockPos feet, BlockPos protectedBlock, Navigation.Options options) {
        Vec3 eye = mc.player.getEyePosition();
        if (breakCount < options.permissions().breakLimit()) {
            var protection = options.permissions().protectedBlocks();
            Change head = clearObstruction(mc, terrain, helper, feet.above(), protectedBlock, protection, eye);
            Change body = clearObstruction(mc, terrain, helper, feet, protectedBlock, protection, eye);
            List<Change> clearing = clearingSequence(head, body, options.permissions().breakLimit() - breakCount,
                changes -> safe(mc, new Candidate(from, to, changes, null), options));
            if (!clearing.isEmpty()) return new Candidate(from, to, clearing, null);
        }
        if (placementCount >= options.permissions().placeLimit()) return null;
        BlockPos target = feet.below();
        BlockState before = mc.level.getBlockState(target);
        if (!before.isAir() || !terrain.selectedBlock(target) || target.equals(protectedBlock)) return null;
        Inventory inventory = mc.player.getInventory();
        int selected = inventory.getSelectedSlot();
        var current = supportPlan(mc, terrain, helper, from, to, target, before, options, mc.player.getMainHandItem(),
            InteractionHand.MAIN_HAND, selected);
        if (current != null) return current;
        var offhand = supportPlan(mc, terrain, helper, from, to, target, before, options, mc.player.getOffhandItem(),
            InteractionHand.OFF_HAND, -1);
        if (offhand != null) return offhand;
        for (int slot = 0; Inventory.isHotbarSlot(slot); slot++) {
            if (slot == selected) continue;
            var plan = supportPlan(mc, terrain, helper, from, to, target, before, options, inventory.getItem(slot),
                InteractionHand.MAIN_HAND, slot);
            if (plan != null) return plan;
        }
        return null;
    }

    static List<Change> clearingSequence(Change head, Change body, int remaining, Predicate<List<Change>> opensEdge) {
        if (remaining <= 0) return List.of();
        if (head != null && opensEdge.test(List.of(head))) return List.of(head);
        if (body != null && opensEdge.test(List.of(body))) return List.of(body);
        if (remaining >= 2 && head != null && body != null) {
            // Mine the upper obstruction first, so the lower one can keep supporting it until removed.
            List<Change> opening = List.of(head, body);
            if (opensEdge.test(opening)) return opening;
        }
        return List.of();
    }

    private static Change clearObstruction(Minecraft mc, MinecraftTerrain terrain, ConstructionPlacement helper,
                                           BlockPos target, BlockPos protectedBlock, RouteBlockProtection protection, Vec3 eye) {
        if (!loaded(mc.level, target) || !terrain.selectedBlock(target) || target.equals(protectedBlock)) return null;
        BlockState state = mc.level.getBlockState(target);
        if (state.isAir() || protection.contains(state) || helper.breakHit(target, eye) == null) return null;
        return new Change(target, state, Blocks.AIR.defaultBlockState(), null);
    }

    private static Candidate supportPlan(Minecraft mc, MinecraftTerrain terrain, ConstructionPlacement helper, Node from, Node to,
                                         BlockPos target, BlockState before, Navigation.Options options, ItemStack stack,
                                         InteractionHand hand, int slot) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item) || item.getClass() != BlockItem.class
            || item.getBlock().getClass() != Block.class || !canSpendSupport(mc.player, item, options.permissions().materialReserve())) return null;
        BlockState state = item.getBlock().defaultBlockState();
        if (!state.isCollisionShapeFullBlock(mc.level, target) || MinecraftTerrain.inherentHazard(state)
            || !state.getFluidState().isEmpty()) return null;
        var change = new Change(target, before, state, new ConstructionPlacement.Supply(hand, slot, stack, item));
        var edit = new Candidate(from, to, List.of(change), null);
        if (!safe(mc, edit, options)) return null;
        Vec3 eye = mc.player.getEyePosition();
        if (placementAim(helper, change, eye) != null) return edit;
        Vec3 edge = bridgeEdge(terrain, mc.player.position(), from, to);
        if (edge == null) return null;
        Vec3 edgeEye = edge.add(0, eye.y - mc.player.getY(), 0);
        return placementAim(helper, change, edgeEye) == null ? null : new Candidate(from, to, List.of(change), edge);
    }

    private static boolean canSpendSupport(LocalPlayer player, Item item, int reserve) {
        if (reserve == 0 || player.getAbilities().instabuild) return true;
        return hasSupportBeyondReserve(player.getInventory().getNonEquipmentItems(), player.getOffhandItem(), item, reserve);
    }

    static boolean hasSupportBeyondReserve(Iterable<ItemStack> inventory, ItemStack offhand, Item item, int reserve) {
        int available = count(offhand, item);
        for (ItemStack stack : inventory) {
            available += count(stack, item);
            if (available > reserve) return true;
        }
        return available > reserve;
    }

    private static int count(ItemStack stack, Item item) { return stack.is(item) ? stack.getCount() : 0; }

    static Vec3 bridgeEdge(MinecraftTerrain terrain, Vec3 feet, Node from, Node to) {
        Vec3 edge = new Vec3(from.centerX() + EDGE_OFFSET * (to.x() - from.x()), from.y(),
            from.centerZ() + EDGE_OFFSET * (to.z() - from.z()));
        return terrain.canRoundWalk(feet, edge) ? edge : null;
    }

    private static ConstructionPlacement.Aim placementAim(ConstructionPlacement helper, Change change, Vec3 eye) {
        return helper.find(change.target(), change.after(), change.supply(), eye,
            pos -> pos.equals(change.target()) ? change.after() : null);
    }

    Status advance(Minecraft mc, MinecraftTerrain terrain, Navigation.Options options) {
        if (candidate == null || level != mc.level || player != mc.player || gameMode != mc.gameMode)
            return fail("The world changed during a route edit.");
        motion = null;
        if (mc.gui.screen() != null) {
            detail = "Close the screen to apply the route's permitted block edit.";
            return Status.WORKING;
        }
        if (++waitTicks > (phase == Phase.BREAK ? 160 : 45)) return fail("The game did not complete or acknowledge the route edit.");
        var edit = candidate.first();
        if (candidate.edge() != null) motion = HOLD_SNEAK;
        if (phase == Phase.ACK) {
            if (sequence < 0 || acknowledged < sequence) return Status.WORKING;
            if (!loaded(level, edit.target()) || level.getBlockState(edit.target()) != edit.after())
                return fail("The server rejected the route edit or the target changed.");
            detail = "Confirmed route edit at " + edit.target().toShortString() + ".";
            return Status.CONFIRMED;
        }
        if (!terrain.canFinish(candidate.from()) || !safe(mc, candidate, options)
            || candidate.edge() != null && !terrain.canRoundWalk(player.position(), player.position()))
            return fail("The route edit lost its verified foothold, target, or clearance.");
        var helper = new ConstructionPlacement(level, player);
        if (phase == Phase.EDGE) return advanceEdge(terrain, helper, edit);
        if (player.getDeltaMovement().horizontalDistanceSqr() > STILL_SPEED * STILL_SPEED) return Status.WORKING;
        ConstructionPlacement.Aim aim;
        if (edit.breaking()) {
            int reserve = options.permissions().toolDurabilityReserve();
            String pause = ToolDurability.pauseReason(player, level, edit.target(), edit.before(), reserve);
            if (pause != null) {
                toolPause = new ToolPause(edit.target(), edit.before(), reserve);
                detail = pause;
                return Status.PAUSED;
            }
            aim = helper.breakHit(edit.target(), player.getEyePosition());
        } else {
            var supply = edit.supply();
            int reserve = options.permissions().materialReserve();
            if (!canSpendSupport(player, supply.item(), reserve))
                return fail("Placement would use the " + reserve + "-block support material reserve.");
            ItemStack held = selectSupport(supply);
            if (held.isEmpty() || held.getItem() != supply.item()) return fail("The selected route support material changed.");
            var actual = new Change(edit.target(), edit.before(), edit.after(),
                new ConstructionPlacement.Supply(supply.hand(), supply.slot(), held, supply.item()));
            aim = placementAim(helper, actual, player.getEyePosition());
        }
        if (aim == null) return fail("The route edit is no longer visible and within reach.");
        if (!turn(aim)) return Status.WORKING;
        BlockHitResult hit = aim.hit();
        capturing = true;
        try {
            if (!edit.breaking()) {
                placementCount++;
                if (!mc.gameMode.useItemOn(player, edit.supply().hand(), hit).consumesAction()) return fail("The game refused to place the support block.");
                player.swing(edit.supply().hand(), SwingAnimation.DEFAULT, false);
                awaitAcknowledgement();
            } else if (phase == Phase.AIM) {
                breakCount++;
                if (!mc.gameMode.startDestroyBlock(edit.target(), hit.getDirection())) return fail("The game refused to mine the obstructing block.");
                player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
                if (mc.gameMode.isDestroying()) {
                    phase = Phase.BREAK;
                    waitTicks = 0;
                    detail = "Breaking the obstructing block at " + edit.target().toShortString() + ".";
                } else {
                    awaitAcknowledgement();
                }
            } else {
                if (mc.gameMode.continueDestroyBlock(edit.target(), hit.getDirection()))
                    player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
                if (!mc.gameMode.isDestroying() || level.getBlockState(edit.target()).isAir()) awaitAcknowledgement();
            }
        } finally {
            capturing = false;
        }
        return Status.WORKING;
    }

    private Status advanceEdge(MinecraftTerrain terrain, ConstructionPlacement helper, Change edit) {
        Vec3 feet = player.position();
        Vec3 edge = candidate.edge();
        if (!terrain.canRoundWalk(feet, edge)) return fail("The bridge edge is no longer safe to approach.");
        Vec3 speed = player.getDeltaMovement();
        if (speed.horizontalDistanceSqr() <= .035 * .035 && placementAim(helper, edit, player.getEyePosition()) != null) {
            phase = Phase.AIM;
            waitTicks = 0;
            detail = "Preparing to place support at " + edit.target().toShortString() + ".";
            return Status.WORKING;
        }
        double dx = edge.x - feet.x, dz = edge.z - feet.z;
        double distance = Math.hypot(dx, dz);
        if (distance <= .025) {
            return speed.horizontalDistanceSqr() <= .015 * .015 ? fail("The support face is still out of reach at the bridge edge.")
                : Status.WORKING;
        }
        double lookahead = Math.min(distance, .09);
        Vec3 next = feet.add(speed.x + dx / distance * lookahead, 0, speed.z + dz / distance * lookahead);
        if (!terrain.canRoundWalk(feet, next)) return Status.WORKING;
        Vec2 direction = PathFollower.walkingDirection(dx, dz, player.getYRot());
        float scale = (float) Math.clamp(distance / .16, .2, 1);
        motion = new Motion(new Input(direction.y > .04, direction.y < -.04, direction.x > .04, direction.x < -.04,
            false, true, false), new Vec2(direction.x * scale, direction.y * scale));
        return Status.WORKING;
    }

    Motion motion() { return motion; }

    private ItemStack selectSupport(ConstructionPlacement.Supply supply) {
        if (supply.hand() == InteractionHand.OFF_HAND) return player.getOffhandItem();
        var inventory = player.getInventory();
        int current = inventory.getSelectedSlot();
        if (selectedSlot >= 0 && current != selectedSlot) return ItemStack.EMPTY;
        if (current != supply.slot()) {
            if (inventory.getItem(supply.slot()).getItem() != supply.item()) return ItemStack.EMPTY;
            originalSlot = current;
            selectedSlot = supply.slot();
            inventory.setSelectedSlot(selectedSlot);
        }
        return player.getMainHandItem();
    }

    private boolean turn(ConstructionPlacement.Aim aim) {
        float yaw = (float) Math.IEEEremainder(aim.yaw() - player.getYRot(), 360);
        float pitch = aim.pitch() - player.getXRot();
        player.setYRot(player.getYRot() + Math.clamp(yaw, -30, 30));
        player.setXRot(player.getXRot() + Math.clamp(pitch, -25, 25));
        return Math.abs(yaw) <= 5 && Math.abs(pitch) <= 5;
    }

    private void awaitAcknowledgement() {
        phase = Phase.ACK;
        waitTicks = 0;
        detail = "Waiting for the server to confirm the route edit.";
    }

    private Status fail(String reason) {
        detail = reason;
        return Status.FAILED;
    }

    void clear(Minecraft mc) {
        if (phase == Phase.BREAK && mc.level == level && mc.player == player && mc.gameMode == gameMode && gameMode.isDestroying())
            gameMode.stopDestroyBlock();
        if (selectedSlot >= 0 && mc.level == level && mc.player == player
            && player.getInventory().getSelectedSlot() == selectedSlot) player.getInventory().setSelectedSlot(originalSlot);
        candidate = null;
        phase = null;
        level = null;
        player = null;
        gameMode = null;
        connection = null;
        capturing = false;
        motion = null;
        toolPause = null;
        originalSlot = selectedSlot = -1;
    }

    boolean ownsBreaking(Minecraft mc) {
        return phase == Phase.BREAK && mc.level == level && mc.player == player && mc.gameMode == gameMode
            && mc.gui.screen() == null && mc.isWindowActive() && gameMode.isDestroying();
    }

    void sent(Connection sentOn, Object packet) {
        if (!capturing || candidate == null || sentOn != connection) return;
        Change edit = candidate.first();
        if (edit.breaking() && packet instanceof ServerboundPlayerActionPacket action
            && action.getPos().equals(edit.target())
            && (action.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
            || action.getAction() == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK)) {
            sequence = action.getSequence();
        } else if (!edit.breaking() && packet instanceof ServerboundUseItemOnPacket action
            && action.hand() == edit.supply().hand()
            && action.hitResult().getBlockPos().relative(action.hitResult().getDirection()).equals(edit.target())) {
            sequence = action.sequence();
        }
    }

    void acknowledge(ClientLevel world, int value) {
        if (level == world && candidate != null) acknowledged = Math.max(acknowledged, value);
    }

    private static boolean safe(Minecraft mc, Candidate edit, Navigation.Options options) {
        var level = mc.level;
        var player = mc.player;
        double maxOffset = edit.edge() == null ? .42 : .74;
        if (!player.onGround() || Math.abs(player.getY() - edit.from().y()) > .08
            || Math.hypot(player.getX() - edit.from().centerX(), player.getZ() - edit.from().centerZ()) > maxOffset) return false;
        var exclusions = Navigation.activeExclusions(options.avoidMobs());
        Map<BlockPos, BlockState> changes = new HashMap<>();
        for (Change change : edit.changes()) {
            BlockPos target = change.target();
            AABB affected = new AABB(target);
            if (!loaded(level, target) || level.getBlockState(target) != change.before()
                || !level.getWorldBorder().isWithinBounds(target) || !level.mayInteract(player, target)
                || player.getBoundingBox().intersects(change.breaking() ? affected.inflate(.02) : affected)
                || exclusions.blockingAt(new RouteExclusions.Point(target.getX() + .5, target.getY(), target.getZ() + .5),
                    new RouteExclusions.Footprint(1, 1)).isPresent()) return false;
            changes.put(target, change.after());
            if (change.breaking() && (options.permissions().protectedBlocks().contains(change.before())
                || !safeToBreak(level, player, target, change.before(), new ConstructionTerrain(level, changes))))
                return false;
        }
        var view = new ConstructionTerrain(level, changes);
        var standing = player.getDimensions(Pose.STANDING);
        var predicted = new MinecraftTerrain(view, player, level::hasChunk, standing.width() / 2.0,
            standing.height(), () -> exclusions);
        return predicted.canApproachFrom(player.getX(), player.getY(), player.getZ(), edit.from())
            && predicted.canTraverse(edit.from(), edit.to(), Move.WALK);
    }

    private static boolean safeToBreak(ClientLevel level, LocalPlayer player, BlockPos target, BlockState state,
                                       ConstructionTerrain without) {
        if (state.isAir() || state.hasBlockEntity() || MinecraftTerrain.inherentHazard(state)
            || state.getDestroySpeed(level, target) < 0 || !state.isCollisionShapeFullBlock(level, target)
            || !player.getMainHandItem().canDestroyBlock(state, level, target, player)) return false;
        for (Direction face : Direction.values()) {
            BlockPos adjacent = target.relative(face);
            if (!loaded(level, adjacent)) return false;
            BlockState neighbor = without.getBlockState(adjacent);
            if (!neighbor.getFluidState().isEmpty() || face == Direction.UP && neighbor.getBlock() instanceof FallingBlock
                || !neighbor.canSurvive(without, adjacent)) return false;
        }
        return true;
    }

    private static boolean loaded(ClientLevel level, BlockPos pos) {
        return pos.getY() >= level.getMinY() && pos.getY() <= level.getMaxY() && level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private static double distance(Node node, Goal goal) {
        return Math.hypot(goal.x() - node.x(), goal.z() - node.z());
    }
}
