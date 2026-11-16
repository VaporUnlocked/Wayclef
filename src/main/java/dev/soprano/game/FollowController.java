package dev.soprano.game;

import dev.soprano.SopranoClient;
import dev.soprano.host.ClientEvents;
import dev.soprano.path.Goal;
import dev.soprano.path.Node;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Keeps one follow job behind a currently visible target in the local world. */
public final class FollowController implements ClientEvents.Listener {
    private enum TargetGroup { EXACT, PLAYERS, ENTITIES }

    private static final FollowController INSTANCE = new FollowController();
    private static final double DEFAULT_GAP = 3;
    private static final double MAX_VISIBILITY_DISTANCE = 48;
    private static final double RESUME_MARGIN = .55;
    private static final int FAILURE_TIMEOUT = 60;

    private final Object owner = new Object();
    private ClientLevel level;
    private LocalPlayer player;
    private MinecraftTerrain targetTerrain;
    private UUID targetId;
    private int targetEntityId;
    private boolean targetIsPlayer;
    private TargetGroup targetGroup;
    private int nextGroupSearch;
    private String targetName;
    private Navigation.Options options;
    private double gap;
    private Goal requestedGoal;
    private boolean holding;
    private boolean failedRequest;
    private int unseenTicks;
    private int failedTicks;
    private String detail = "No active follow.";

    private FollowController() { }

    public static FollowController get() { return INSTANCE; }

    public void initialize() {
        ClientEvents.subscribe(this);
    }

    public boolean active() { return targetId != null; }

    public String statusText() {
        return active() ? "Following " + targetName + ". " + detail : detail;
    }

    public void follow(String playerName, Navigation.Options requestedOptions) {
        follow(playerName, DEFAULT_GAP, requestedOptions);
    }

    public void follow(String playerName, double distance, Navigation.Options requestedOptions) {
        follow(findTarget(playerName, distance), TargetGroup.EXACT, distance, requestedOptions);
    }

    public void follow(Entity target, double distance, Navigation.Options requestedOptions) {
        follow(target, TargetGroup.EXACT, distance, requestedOptions);
    }

    public void followNearest(boolean players, double distance, Navigation.Options requestedOptions) {
        Minecraft mc = requireWorld(distance);
        Entity target = nearest(mc.level, mc.player, players);
        if (target == null) throw new IllegalStateException(players ? "No visible players are within follow range." : "No visible entities are within follow range.");
        follow(target, players ? TargetGroup.PLAYERS : TargetGroup.ENTITIES, distance, requestedOptions);
    }

    private void follow(Entity target, TargetGroup group, double distance, Navigation.Options requestedOptions) {
        Objects.requireNonNull(requestedOptions, "settings");
        Minecraft mc = requireWorld(distance);
        if (target == null || !visible(mc.player, target)) throw new IllegalStateException("That target is no longer visible within follow range.");

        var liveTerrain = new MinecraftTerrain(mc.level, mc.player, requestedOptions.avoidMobs());
        Node ground = groundBelow(liveTerrain, target);
        Goal destination = ground == null ? null : goal(ground, distance);
        var localFeet = mc.player.blockPosition();
        Navigation navigation = Navigation.get();
        if (!navigation.requestFollow(owner, destination == null ? new Goal(localFeet.getX(), null, localFeet.getZ(), 0)
            : destination, requestedOptions)) {
            throw new IllegalStateException("Another tool owns navigation.");
        }
        level = mc.level;
        player = mc.player;
        targetTerrain = liveTerrain;
        targetGroup = group;
        nextGroupSearch = 0;
        rememberTarget(target);
        options = requestedOptions;
        gap = distance;
        requestedGoal = destination;
        holding = failedRequest = false;
        unseenTicks = failedTicks = 0;
        detail = navigation.ownedStatus(owner);
        if (ground == null) hold("Waiting for verified ground beneath the target.");
        else if (withinGap(player, target, ground)) hold("Keeping a comfortable gap.");
    }

    public Goal previewGoal(String playerName, double distance) {
        Player target = findTarget(playerName, distance);
        Minecraft mc = Minecraft.getInstance();
        Node ground = groundBelow(new MinecraftTerrain(mc.level, mc.player), target);
        if (ground == null) throw new IllegalStateException("No verified ground beneath the target.");
        return goal(ground, distance);
    }

    private static Player findTarget(String playerName, double distance) {
        String name = Objects.requireNonNull(playerName, "player name").strip();
        if (name.isEmpty()) throw new IllegalArgumentException("Enter a Minecraft player name.");
        Minecraft mc = requireWorld(distance);
        for (Player candidate : mc.level.players()) {
            if (candidate.getGameProfile().name().equalsIgnoreCase(name) && visible(mc.player, candidate)) return candidate;
        }
        throw new IllegalStateException("That player is not visible within follow range.");
    }

    private static Minecraft requireWorld(double distance) {
        if (!Double.isFinite(distance) || distance < 2 || distance > 32)
            throw new IllegalArgumentException("Follow distance must be 2-32 blocks.");
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) throw new IllegalStateException("Join a world first.");
        return mc;
    }

    public static List<Entity> visibleTargets(Minecraft mc) {
        if (mc.level == null || mc.player == null) return List.of();
        List<Entity> targets = new ArrayList<>();
        for (Entity entity : mc.level.entitiesForRendering()) if (visible(mc.player, entity)) targets.add(entity);
        targets.sort(Comparator.comparingDouble(mc.player::distanceToSqr));
        return List.copyOf(targets);
    }

    public void stop() {
        if (active()) finish("Follow stopped.");
    }

    @Override
    public void tickPre() {
        if (!active()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != level || mc.player != player || !player.isAlive() || player.isRemoved()) {
            finish("The world or player changed.");
            return;
        }
        Navigation navigation = Navigation.get();
        String state = navigation.ownedState(owner);
        boolean toolPaused = navigation.waitingForFollowTool(owner);
        if (toolPaused) {
            detail = navigation.ownedStatus(owner);
            if (mc.gui.screen() != null || !mc.getWindow().isFocused() || !mc.mouseHandler.isMouseGrabbed()
                || !navigation.followToolReady(owner)) return;
        } else if (state.equals("STOPPED") || state.equals("PAUSED") || state.equals("IDLE")) {
            finish("Follow ended because navigation released control.");
            return;
        }
        if (!Navigation.allowedScreen(mc.gui.screen()) || mc.gui.screen() == null
            && (!mc.getWindow().isFocused() || !mc.mouseHandler.isMouseGrabbed())) {
            finish("Follow stopped when Minecraft lost focus or opened a game menu.");
            return;
        }
        Entity target = visibleTarget();
        if (target == null) {
            if (toolPaused) return;
            unseenTicks = 1;
            hold("Paused until the target is visible again.");
            return;
        }
        if (unseenTicks > 0) {
            failedRequest = false;
            failedTicks = 0;
        }
        unseenTicks = 0;
        Node ground = groundBelow(targetTerrain, target);
        if (ground == null) {
            if (toolPaused) return;
            if (requestedGoal != null && (state.equals("WALKING") || state.equals("PLANNING"))) {
                detail = "Keeping the last verified ground target.";
            } else {
                hold("Waiting for verified ground beneath the target.");
            }
            return;
        }
        if (!toolPaused && withinGap(player, target, ground)) {
            hold("Keeping a comfortable gap.");
            failedRequest = false;
            failedTicks = 0;
            return;
        }

        if (state.equals("FAILED") || failedRequest) {
            detail = "Waiting for a new reachable target position.";
            if (++failedTicks >= FAILURE_TIMEOUT) {
                finish("Could not find a safe route to the target.");
                return;
            }
        } else if (!holding) {
            detail = navigation.ownedStatus(owner);
        }

        if (!navigation.active() && !player.onGround() && !player.onClimbable()) return;
        Goal next = goal(ground, gap);
        if (!toolPaused && next.equals(requestedGoal)) return;
        try {
            if (!navigation.refreshFollow(owner, next, options)) {
                finish("Another tool took control of navigation.");
                return;
            }
            holding = failedRequest = false;
            failedTicks = 0;
            detail = navigation.ownedStatus(owner);
        } catch (IllegalStateException problem) {
            if (toolPaused) {
                detail = problem.getMessage();
                return;
            }
            if (!hold(problem.getMessage())) return;
            failedRequest = true;
        }
        requestedGoal = next;
    }

    @Override
    public void disconnected() {
        if (active()) finish("Left the world.");
    }

    @Override
    public void screenChanged(Screen screen) {
        if (active() && !Navigation.get().waitingForFollowTool(owner) && !Navigation.allowedScreen(screen)) {
            finish("Follow stopped when a game menu opened.");
        }
    }

    @Override
    public void keyInput(KeyEvent input, ClientEvents.Action action) {
        if (!active() || action == ClientEvents.Action.RELEASE) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() != null) return;
        if (SopranoClient.manualMovementKey(mc.options, input)) finish("You took control of movement.");
    }

    private Entity visibleTarget() {
        Entity candidate = level.getEntity(targetEntityId);
        if (candidate != null && targetId.equals(candidate.getUUID()) && visible(player, candidate)) return candidate;
        // A player can return with the same UUID and a new client entity ID after respawning.
        if (targetIsPlayer) {
            for (Player returning : level.players()) {
                if (targetId.equals(returning.getUUID()) && visible(player, returning)) {
                    rememberTarget(returning);
                    return returning;
                }
            }
        }
        if (targetGroup == TargetGroup.EXACT) return null;
        if (player.tickCount < nextGroupSearch) return null;
        nextGroupSearch = player.tickCount + 5;
        candidate = nearest(level, player, targetGroup == TargetGroup.PLAYERS);
        if (candidate == null) return null;
        rememberTarget(candidate);
        requestedGoal = null;
        unseenTicks = 1;
        return candidate;
    }

    private static Entity nearest(ClientLevel level, LocalPlayer player, boolean players) {
        Entity best = null;
        double distance = Double.POSITIVE_INFINITY;
        Iterable<? extends Entity> candidates = players ? level.players() : level.entitiesForRendering();
        for (Entity candidate : candidates) {
            if (!players && candidate instanceof Player) continue;
            double next = player.distanceToSqr(candidate);
            if (next < distance && visible(player, candidate)) {
                best = candidate;
                distance = next;
            }
        }
        return best;
    }

    private void rememberTarget(Entity target) {
        targetId = target.getUUID();
        targetEntityId = target.getId();
        targetIsPlayer = target instanceof Player;
        targetName = target instanceof Player remote ? remote.getGameProfile().name()
            : target instanceof ItemEntity item ? item.getItem().getHoverName().getString() : target.getName().getString();
    }

    private boolean hold(String reason) {
        if (!holding) {
            if (!Navigation.get().holdFollow(owner, reason)) {
                finish("Navigation released control.");
                return false;
            }
            holding = true;
        }
        requestedGoal = null;
        detail = reason;
        return true;
    }

    private void finish(String reason) {
        targetId = null;
        targetGroup = null;
        level = null;
        player = null;
        targetTerrain = null;
        targetName = null;
        options = null;
        requestedGoal = null;
        holding = failedRequest = false;
        detail = reason;
        Navigation.get().cancelOwned(owner);
    }

    private static Node groundBelow(MinecraftTerrain terrain, Entity target) {
        terrain.beginSearchSlice();
        try {
            return terrain.followGroundBelow(target.getX(), target.getY(), target.getZ());
        } finally {
            terrain.endSearchSlice();
        }
    }

    private static Goal goal(Node ground, double gap) {
        int radius = (int) Math.floor((gap - .75) / Math.sqrt(2));
        return new Goal(ground.x(), ground.y16(), ground.z(), radius);
    }

    private static boolean visible(LocalPlayer observer, Entity target) {
        return target != observer && target.level() == observer.level() && target.isAlive() && !target.isRemoved()
            && !target.isSpectator() && (target.isPickable() || target instanceof ItemEntity)
            && !target.isInvisibleTo(observer) && observer.distanceToSqr(target) <= MAX_VISIBILITY_DISTANCE * MAX_VISIBILITY_DISTANCE
            && observer.hasLineOfSight(target);
    }

    private boolean withinGap(LocalPlayer observer, Entity target, Node ground) {
        return closeEnoughToFloor(observer.getY(), ground)
            && Math.hypot(observer.getX() - target.getX(), observer.getZ() - target.getZ()) <= gap + (holding ? RESUME_MARGIN : 0);
    }

    static boolean closeEnoughToFloor(double feetY, Node ground) {
        return Math.abs(feetY - ground.y()) <= .25;
    }

}
