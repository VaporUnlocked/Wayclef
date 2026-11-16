package dev.soprano.game;

import dev.soprano.SopranoClient;
import dev.soprano.game.transport.CushionRoute;
import dev.soprano.game.transport.PearlTrajectory;
import dev.soprano.game.transport.TransportMode;
import dev.soprano.path.Goal;
import dev.soprano.path.Node;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.Cushion;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.function.Predicate;

/** Owns a short approach and one verified vanilla use interaction. */
public final class WorldInteraction {
    private static final WorldInteraction INSTANCE = new WorldInteraction();
    private static final int CUSHION_SEARCH_RANGE = 32;
    private static final int APPROACH_TIMEOUT = 20 * 45;
    private static final int CONFIRMATION_TIMEOUT = 20 * 4;
    private static final double[] FACE_OFFSETS = {.5, .23, .77};

    private sealed interface Target permits Seat, Ignition, EndEye, Gateway {
        BlockPos position();
        String description();
    }

    private record Seat(Cushion cushion) implements Target {
        @Override public BlockPos position() { return BlockPos.containing(cushion.getBoundingBox().getCenter()); }
        @Override public String description() { return cushion.getColor().getName().replace('_', ' ') + " cushion"; }
    }

    private record Ignition(BlockPos position) implements Target {
        @Override public String description() { return "Nether portal"; }
    }

    private record EndEye(EndPortalRing ring, BlockPos position) implements Target {
        @Override public String description() { return "End portal frame"; }
    }

    private record Gateway(BlockPos position) implements Target {
        @Override public String description() { return "End gateway"; }
    }

    private final Object owner = new Object();
    private ClientLevel level;
    private LocalPlayer player;
    private Target target;
    private Navigation.Options options;
    private int remaining;
    private int originalSlot = -1;
    private int selectedSlot = -1;
    private boolean walking;
    private boolean clicked;
    private Vec3 pearlOrigin;
    private String status = "";

    private WorldInteraction() { }

    public static WorldInteraction get() { return INSTANCE; }
    public boolean active() { return target != null; }
    public String statusText() { return status; }

    public void sit(DyeColor color, Navigation.Options requested) {
        Minecraft mc = ready();
        LocalPlayer local = mc.player;
        Cushion chosen = mc.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof Cushion cushion
            && available(cushion, color) && local.distanceToSqr(cushion) <= CUSHION_SEARCH_RANGE * CUSHION_SEARCH_RANGE
            && CushionRoute.seatHit(mc.level, local, cushion) != null ? cushion : null;
        if (chosen == null) {
            chosen = mc.level.getEntitiesOfClass(Cushion.class, local.getBoundingBox().inflate(CUSHION_SEARCH_RANGE),
                    cushion -> available(cushion, color)
                        && local.distanceToSqr(cushion) <= CUSHION_SEARCH_RANGE * CUSHION_SEARCH_RANGE)
                .stream().min(Comparator.comparingDouble(local::distanceToSqr)).orElse(null);
        }
        if (chosen == null) throw new IllegalStateException(color == null ? "No free cushion is nearby."
            : "No free " + color.getName().replace('_', ' ') + " cushion is nearby.");
        begin(mc, new Seat(chosen), requested);
    }

    public void ignite(BlockPos position, Navigation.Options requested) {
        Minecraft mc = ready();
        NetherFrame.requireUnlit(mc.level, position);
        if (!igniter(mc.player.getOffhandItem()) && ignitionSlot(mc.player) < 0) {
            throw new IllegalStateException("Put flint and steel or a fire charge in the hotbar or offhand.");
        }
        begin(mc, new Ignition(position.immutable()), requested);
    }

    public void fillEndPortal(BlockPos position, Navigation.Options requested) {
        Minecraft mc = ready();
        EndPortalRing ring = EndPortalRing.find(mc.level, position);
        if (ring.active(mc.level)) throw new IllegalStateException("The End portal is already active.");
        var missing = ring.missing(mc.level);
        if (missing.isEmpty()) throw new IllegalStateException("All sockets are filled. The server has not opened the portal.");
        if (countUsable(mc.player, stack -> stack.is(Items.ENDER_EYE)) < missing.size()) {
            throw new IllegalStateException("Put " + missing.size() + " Eyes of Ender in the hotbar or offhand.");
        }
        begin(mc, new EndEye(ring, ring.next(mc.level, mc.player.position())), requested);
    }

    public void hopGateway(BlockPos position, Navigation.Options requested) {
        Minecraft mc = ready();
        requireGateway(mc.level, position);
        if (countUsable(mc.player, stack -> stack.is(Items.ENDER_PEARL)) == 0) {
            throw new IllegalStateException("Put an ender pearl in the hotbar or offhand.");
        }
        begin(mc, new Gateway(position.immutable()), requested);
    }

    private static Minecraft ready() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.gameMode == null || !mc.player.isAlive()
            || mc.player.isSpectator()) throw new IllegalStateException("Join a world with an active player first.");
        if (mc.player.isPassenger()) throw new IllegalStateException("Dismount first.");
        if (mc.player.isInLiquid() || mc.player.isFallFlying()) throw new IllegalStateException("Start from dry footing.");
        if (Navigation.get().active()) throw new IllegalStateException("Stop the current route first.");
        return mc;
    }

    private void begin(Minecraft mc, Target next, Navigation.Options requested) {
        level = mc.level;
        player = mc.player;
        target = next;
        options = requested.withTransport(TransportMode.WALK);
        remaining = APPROACH_TIMEOUT;
        status = "Approaching the " + next.description() + ".";
    }

    public void tick() {
        if (!active()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != level || mc.player != player || !player.isAlive() || player.isRemoved()) {
            finish("The world or player changed.");
            return;
        }
        if (clicked && confirmed()) {
            try {
                afterConfirmation();
            } catch (IllegalArgumentException | IllegalStateException rejected) {
                finish(rejected.getMessage());
            }
            if (!active() || !clicked) return;
        }
        if (--remaining <= 0) {
            finish(clicked ? "The server did not confirm the " + target.description() + " interaction."
                : "The approach to the " + target.description() + " timed out.");
            return;
        }
        if (mc.gui.screen() instanceof ChatScreen) return;
        if (mc.gui.screen() != null || mc.gameMode == null || !mc.getWindow().isFocused()
            || !mc.mouseHandler.isMouseGrabbed()) {
            finish("Interaction stopped when Minecraft lost focus or opened a menu.");
            return;
        }
        if (clicked) return;
        if (player.isPassenger() || player.isSpectator() || SopranoClient.movementConflictReason() != null
            || SopranoClient.manualInteractionHeld(mc.options)) {
            finish("The player can no longer approach the interaction target.");
            return;
        }
        try {
            if (walking) {
                switch (Navigation.get().ownedState(owner)) {
                    case "ARRIVED" -> walking = false;
                    case "FAILED", "STOPPED", "PAUSED", "IDLE" -> {
                        finish("Could not walk within reach of the " + target.description() + ".");
                        return;
                    }
                    default -> { return; }
                }
            } else if (target instanceof Seat seat && !available(seat.cushion(), null)) {
                finish("The selected cushion is no longer free.");
                return;
            }

            if (player.onGround() && !player.isShiftKeyDown() && !player.isUsingItem() && use(mc)) {
                clicked = true;
                remaining = target instanceof Gateway ? 20 * 10 : CONFIRMATION_TIMEOUT;
                status = "Waiting for the server to confirm the " + target.description() + " interaction.";
            } else if (Navigation.get().ownedState(owner).equals("ARRIVED")) {
                finish("The " + target.description() + " is out of reach from the approach point.");
            } else if (player.onGround() && !player.isShiftKeyDown() && !player.isUsingItem()) {
                if (!Navigation.get().requestOwned(owner, approachGoal(), options)) {
                    finish("Another activity owns navigation.");
                    return;
                }
                walking = true;
            }
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            finish(rejected.getMessage());
        }
    }

    public void stop() { if (active()) finish("Interaction stopped."); }

    private boolean confirmed() {
        return switch (target) {
            case Seat seat -> player.getVehicle() == seat.cushion();
            case Ignition ignition -> level.getBlockState(ignition.position()).is(Blocks.NETHER_PORTAL);
            case EndEye eye -> {
                var state = level.getBlockState(eye.position());
                yield state.is(Blocks.END_PORTAL_FRAME) && state.getValue(EndPortalFrameBlock.HAS_EYE);
            }
            case Gateway gateway -> pearlOrigin != null && player.position().distanceToSqr(pearlOrigin) > 64
                && player.position().distanceToSqr(Vec3.atCenterOf(gateway.position())) > 64;
        };
    }

    private void afterConfirmation() {
        switch (target) {
            case Seat seat -> finish("Seated on the " + seat.description() + ".");
            case Ignition ignored -> finish("Nether portal activated.");
            case Gateway ignored -> finish("The server moved the player through the End gateway.");
            case EndEye eye -> {
                if (eye.ring().active(level)) {
                    finish("The End portal opened.");
                    return;
                }
                BlockPos next = eye.ring().next(level, player.position());
                if (next == null) return;
                Navigation.get().cancelOwned(owner);
                walking = clicked = false;
                target = new EndEye(eye.ring(), next);
                remaining = APPROACH_TIMEOUT;
                status = "Approaching the next empty End portal socket.";
            }
        }
    }

    private boolean use(Minecraft mc) {
        return switch (target) {
            case Seat seat -> useSeat(mc, seat);
            case Ignition ignition -> useIgnition(mc, ignition);
            case EndEye eye -> useEye(mc, eye);
            case Gateway gateway -> usePearl(mc, gateway);
        };
    }

    private boolean useSeat(Minecraft mc, Seat seat) {
        Cushion cushion = seat.cushion();
        if (!available(cushion, null)) throw new IllegalStateException("The selected cushion is no longer free.");
        EntityHitResult hit = CushionRoute.seatHit(level, player, cushion);
        if (hit == null) return false;
        if (!mc.gameMode.interact(player, cushion, hit, InteractionHand.MAIN_HAND).consumesAction()) {
            throw new IllegalStateException("The game rejected the cushion interaction.");
        }
        return true;
    }

    private boolean useIgnition(Minecraft mc, Ignition ignition) {
        BlockPos opening = ignition.position();
        Direction.Axis axis = NetherFrame.requireUnlit(level, opening);
        if (!outsidePortalPlane(opening, axis, player.position()) || !player.isWithinBlockInteractionRange(opening.below(), 0)) {
            return false;
        }
        BlockHitResult hit = ignitionHit(level, player, player.getEyePosition(), opening.below());
        if (hit == null) return false;
        InteractionHand hand = equip(WorldInteraction::igniter, "Keep flint and steel or a fire charge in the hotbar or offhand.");
        if (!mc.gameMode.useItemOn(player, hand, hit).consumesAction()) {
            throw new IllegalStateException("The game rejected lighting the Nether portal.");
        }
        player.swing(hand, SwingAnimation.DEFAULT, false);
        return true;
    }

    private boolean useEye(Minecraft mc, EndEye eye) {
        var missing = eye.ring().missing(level);
        if (!missing.contains(eye.position())) return true;
        if (!eye.ring().clearOfOpening(player.position(), player.getBbWidth() / 2 + .05)
            || !player.isWithinBlockInteractionRange(eye.position(), 0)) return false;
        BlockHitResult hit = topHit(level, player, player.getEyePosition(), eye.position(), 13.0 / 16.0);
        if (hit == null) return false;
        InteractionHand hand = equip(stack -> stack.is(Items.ENDER_EYE), "Keep Eyes of Ender in the hotbar or offhand.");
        if (!mc.gameMode.useItemOn(player, hand, hit).consumesAction()) {
            throw new IllegalStateException("The game rejected the End portal frame interaction.");
        }
        player.swing(hand, SwingAnimation.DEFAULT, false);
        return true;
    }

    private boolean usePearl(Minecraft mc, Gateway gateway) {
        requireGateway(level, gateway.position());
        var aim = PearlTrajectory.aimAtGateway(level, player, gateway.position());
        if (aim == null) return false;
        InteractionHand hand = equip(stack -> stack.is(Items.ENDER_PEARL), "Keep an ender pearl in the hotbar or offhand.");
        if (player.getCooldowns().isOnCooldown(player.getItemInHand(hand))) {
            throw new IllegalStateException("The ender pearl is still cooling down.");
        }
        float yaw = player.getYRot(), pitch = player.getXRot();
        pearlOrigin = player.position();
        try {
            player.setYRot(aim.yaw());
            player.setXRot(aim.pitch());
            if (!mc.gameMode.useItem(player, hand).consumesAction()) throw new IllegalStateException("The game rejected the pearl throw.");
        } finally {
            player.setYRot(yaw);
            player.setXRot(pitch);
        }
        return true;
    }

    private static void requireGateway(ClientLevel level, BlockPos at) {
        if (!EndPortalRing.loaded(level, at) || !level.getBlockState(at).is(Blocks.END_GATEWAY)) {
            throw new IllegalArgumentException("Choose a loaded End gateway.");
        }
    }

    private Goal approachGoal() {
        BlockPos at = target.position();
        if (target instanceof Seat) {
            int radius = (int) Math.clamp(Math.floor(player.entityInteractionRange()) - 1, 1, 4);
            return Goal.lineOfSight(at.getX(), at.getY(), at.getZ(), radius);
        }
        int radius = (int) Math.clamp(Math.ceil(player.blockInteractionRange()), 1, 8);
        double margin = player.getBbWidth() / 2 + .05;
        return switch (target) {
            case Ignition ignored -> {
                Direction.Axis axis = NetherFrame.requireUnlit(level, at);
                yield footing(at, radius, feet -> outsidePortalPlane(at, axis, feet)
                    && ignitionHit(level, player, standingEye(feet), at.below()) != null);
            }
            case EndEye eye -> footing(at, radius, feet -> eye.ring().clearOfOpening(feet, margin)
                && topHit(level, player, standingEye(feet), at, 13.0 / 16.0) != null);
            case Gateway ignored -> footing(at, 5, feet -> PearlTrajectory.aimAtGateway(level, player, feet, at) != null);
            case Seat ignored -> throw new IllegalStateException("Cushion approach is already set.");
        };
    }

    private Vec3 standingEye(Vec3 feet) { return feet.add(0, player.getEyeHeight(Pose.STANDING), 0); }

    private Goal footing(BlockPos at, int radius, Predicate<Vec3> interaction) {
        var terrain = new MinecraftTerrain(level, player, options.avoidMobs());
        int lowest = Math.max(Node.MIN_Y16, (at.getY() - 3) * 16);
        int highest = Math.min(Node.MAX_Y16, (at.getY() + 3) * 16);
        Node nearest = null;
        double distance = Double.POSITIVE_INFINITY;
        terrain.beginSearchSlice();
        try {
            for (int x = at.getX() - radius; x <= at.getX() + radius; x++) {
                for (int z = at.getZ() - radius; z <= at.getZ() + radius; z++) {
                    if (!Node.validColumn(x) || !Node.validColumn(z)) continue;
                    for (Node node : terrain.floors(x, z, lowest, highest)) {
                        Vec3 feet = new Vec3(node.centerX(), node.y(), node.centerZ());
                        double distanceSquared = player.position().distanceToSqr(feet);
                        if (distanceSquared >= distance || !terrain.canFinish(node)) continue;
                        if (interaction.test(feet)) {
                            distance = distanceSquared;
                            nearest = node;
                        }
                    }
                }
            }
        } finally {
            terrain.endSearchSlice();
        }
        if (nearest == null) throw new IllegalStateException("No safe footing has a clear interaction with the " + target.description() + ".");
        return new Goal(nearest.x(), nearest.y16(), nearest.z(), 0);
    }

    private boolean outsidePortalPlane(BlockPos opening, Direction.Axis axis, Vec3 feet) {
        double offset = axis == Direction.Axis.X ? feet.z - (opening.getZ() + .5) : feet.x - (opening.getX() + .5);
        return Math.abs(offset) >= .51 + player.getBbWidth() / 2;
    }

    private InteractionHand equip(Predicate<ItemStack> matches, String missing) {
        if (matches.test(player.getMainHandItem())) return InteractionHand.MAIN_HAND;
        if (matches.test(player.getOffhandItem())) return InteractionHand.OFF_HAND;
        int slot = hotbarSlot(player, matches);
        if (slot < 0) throw new IllegalStateException(missing);
        Inventory inventory = player.getInventory();
        if (originalSlot < 0) originalSlot = inventory.getSelectedSlot();
        selectedSlot = slot;
        inventory.setSelectedSlot(slot);
        return InteractionHand.MAIN_HAND;
    }

    private void finish(String message) {
        Navigation.get().cancelOwned(owner);
        if (originalSlot >= 0 && Minecraft.getInstance().player == player) {
            Inventory inventory = player.getInventory();
            if (inventory.getSelectedSlot() == selectedSlot) inventory.setSelectedSlot(originalSlot);
        }
        level = null;
        player = null;
        target = null;
        options = null;
        originalSlot = selectedSlot = -1;
        clicked = walking = false;
        pearlOrigin = null;
        status = message;
    }

    private static boolean available(Cushion cushion, DyeColor color) {
        return CushionRoute.free(cushion)
            && (color == null || cushion.getColor() == color);
    }

    private static BlockHitResult ignitionHit(ClientLevel level, LocalPlayer player, Vec3 eye, BlockPos floor) {
        return topHit(level, player, eye, floor, 1);
    }

    private static BlockHitResult topHit(ClientLevel level, LocalPlayer player, Vec3 eye, BlockPos floor, double height) {
        double reach = player.blockInteractionRange();
        for (double dx : FACE_OFFSETS) {
            for (double dz : FACE_OFFSETS) {
                Vec3 point = new Vec3(floor.getX() + dx, floor.getY() + height - .0001, floor.getZ() + dz);
                if (eye.distanceToSqr(point) > reach * reach) continue;
                BlockHitResult hit = clip(level, player, eye, point);
                if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(floor)
                    && hit.getDirection() == Direction.UP) return hit;
            }
        }
        return null;
    }

    private static BlockHitResult clip(ClientLevel level, LocalPlayer player, Vec3 eye, Vec3 target) {
        return level.clip(new ClipContext(eye, target, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
    }

    private static boolean igniter(ItemStack stack) { return stack.is(Items.FLINT_AND_STEEL) || stack.is(Items.FIRE_CHARGE); }

    private static int ignitionSlot(LocalPlayer player) {
        return hotbarSlot(player, WorldInteraction::igniter);
    }

    private static int hotbarSlot(LocalPlayer player, Predicate<ItemStack> matches) {
        for (int slot = 0; Inventory.isHotbarSlot(slot); slot++) {
            if (matches.test(player.getInventory().getItem(slot))) return slot;
        }
        return -1;
    }

    private static int countUsable(LocalPlayer player, Predicate<ItemStack> matches) {
        ItemStack offhand = player.getOffhandItem();
        int total = matches.test(offhand) ? offhand.getCount() : 0;
        for (int slot = 0; Inventory.isHotbarSlot(slot); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (matches.test(stack)) total += stack.getCount();
        }
        return total;
    }
}
