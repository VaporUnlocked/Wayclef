package dev.soprano.game.transport;

import dev.soprano.game.MinecraftTerrain;
import dev.soprano.game.Navigation;
import dev.soprano.game.PathFollower;
import dev.soprano.path.Goal;
import dev.soprano.path.IncrementalSearch;
import dev.soprano.path.Node;
import dev.soprano.path.Step;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Joins a settled vehicle to an itinerary's next walking leg. */
public final class GroundTransfer implements TransportRoute {
    private static final Input DISMOUNT = new Input(false, false, false, false, false, true, false);
    private static final int DISMOUNT_LIMIT = 24;

    private enum Phase { VERIFY_EXIT, DISMOUNT, WALK }
    private record Exit(Vec3 feet, Node start, float yaw) { }

    private final LocalPlayer player;
    private final Entity vehicle;
    private final Goal goal;
    private final Navigation.Options options;
    private final MinecraftTerrain terrain;
    private final BoatShore boatShore;
    private final MountDismount mountDismount;
    private final Exit exit;
    private Phase phase = Phase.VERIFY_EXIT;
    private Status status = Status.PLANNING;
    private String detail = "Checking the vehicle's exit and walking route.";
    private IncrementalSearch search;
    private PathFollower walker;
    private Node walkingStart;
    private List<Vec3> waypoints = List.of();
    private int dismountTicks;
    private int sections;
    private boolean partial;

    public GroundTransfer(ClientLevel level, LocalPlayer player, Goal goal, Navigation.Options options) {
        this.player = player;
        this.goal = goal;
        this.options = options;
        this.vehicle = player.getControlledVehicle();
        terrain = new MinecraftTerrain(level, player, options.avoidMobs());
        boatShore = vehicle instanceof AbstractBoat boat ? new BoatShore(level, player, boat, terrain, goal) : null;
        mountDismount = vehicle instanceof AbstractHorse mount ? new MountDismount(level, player, mount, terrain) : null;
        exit = settled() ? findExit() : null;
        if (exit == null) fail("Settle the vehicle beside safe dry ground before continuing on foot.");
        else search = new IncrementalSearch(terrain, exit.start(), goal, options.search());
    }

    @Override
    public void advance(int expansions, long budgetNanos) {
        if (status == Status.ARRIVED || status == Status.FAILED) return;
        if (phase == Phase.DISMOUNT) {
            if (!player.isPassenger()) confirmDryGround();
            return;
        }
        if (phase == Phase.WALK && (player.isPassenger() || player.isInLiquid())) {
            fail("The walking leg needs dry, unmounted movement.");
            return;
        }
        if (search == null) return;
        var result = search.advance(expansions, budgetNanos);
        if (result.status() == IncrementalSearch.Status.SEARCHING) return;
        search = null;
        if (result.status() != IncrementalSearch.Status.FOUND && result.status() != IncrementalSearch.Status.PARTIAL) {
            fail("No verified walking connection from the vehicle. " + result.reason());
            return;
        }
        partial = result.status() == IncrementalSearch.Status.PARTIAL;
        updateWaypoints(result.path());
        if (phase == Phase.VERIFY_EXIT) {
            if (!sameExit()) fail("The vehicle or its exit moved before dismounting.");
            else {
                phase = Phase.DISMOUNT;
                status = Status.MOVING;
                detail = "Dismounting onto verified footing.";
            }
        } else {
            walker = result.path().isEmpty() ? new PathFollower(terrain, player, walkingStart, options.steerView())
                : new PathFollower(terrain, player, result.path(), options.steerView());
            detail = "Walking after the vehicle transfer.";
        }
    }

    @Override
    public Input input() {
        if (status != Status.MOVING) return Input.EMPTY;
        if (phase == Phase.DISMOUNT) return dismountInput();
        if (walker == null) return Input.EMPTY;
        Input movement = walker.tick();
        switch (walker.status()) {
            case BLOCKED -> fail(walker.message());
            case ARRIVED -> {
                walker = null;
                if (!partial) arrive();
                else if (++sections < options.partialSegmentLimit()) restartWalk();
                else fail("More loaded terrain is needed to reach the walking stop.");
            }
            case MOVING -> detail = "Walking after dismounting (" + walker.completedSteps() + "/" + walker.totalSteps() + ").";
        }
        return status == Status.MOVING && walker != null ? movement : Input.EMPTY;
    }

    @Override public boolean allowsDismounted() { return phase != Phase.VERIFY_EXIT; }
    @Override public Vec2 walkDirection() {
        return status == Status.MOVING && phase == Phase.WALK && walker != null ? walker.walkDirection() : null;
    }
    @Override public Status status() { return status; }
    @Override public String detail() { return detail; }
    @Override public List<Vec3> waypoints() { return waypoints; }

    private Input dismountInput() {
        if (!player.isPassenger()) return Input.EMPTY;
        if (++dismountTicks > DISMOUNT_LIMIT || !sameExit()) {
            fail("The verified dismount was interrupted. Take control of the vehicle.");
            return Input.EMPTY;
        }
        if (boatShore != null) {
            float turn = Mth.wrapDegrees(exit.yaw() - player.getYRot());
            player.setYRot(player.getYRot() + Math.clamp(turn, -30, 30));
            if (dismountTicks == 1 || Math.abs(turn) > 1) return Input.EMPTY;
        }
        return DISMOUNT;
    }

    private void confirmDryGround() {
        Node start = player.onGround() && !player.isInLiquid() ? terrain.startAtPlayer() : null;
        if (start != null && player.position().distanceToSqr(exit.feet()) <= 4) {
            phase = Phase.WALK;
            walkingStart = start;
            search = new IncrementalSearch(terrain, start, goal, options.search());
            detail = "Verifying the walking leg from your actual footing.";
        } else if (++dismountTicks > DISMOUNT_LIMIT) {
            fail("Dismount did not reach the verified footing. Take control.");
        }
    }

    private void restartWalk() {
        Node start = terrain.startAtPlayer();
        if (start == null) fail("Stand on safe footing to continue the walking leg.");
        else {
            walkingStart = start;
            search = new IncrementalSearch(terrain, start, goal, options.search());
        }
    }

    private boolean settled() {
        if (vehicle == null || !vehicle.isAlive() || player.getControlledVehicle() != vehicle) return false;
        if (vehicle instanceof AbstractBoat) return vehicle.getDeltaMovement().horizontalDistance() <= .045;
        return MountRoute.supports(player) && vehicle.onGround() && !vehicle.isInLiquid()
            && vehicle.getDeltaMovement().horizontalDistance() <= .065;
    }

    private Exit findExit() {
        if (boatShore != null) {
            var candidate = boatShore.at(vehicle.getX(), vehicle.getZ(), true);
            return candidate == null ? null : new Exit(candidate.feet(), candidate.start(), candidate.yaw());
        }
        if (mountDismount == null) return null;
        var candidate = mountDismount.at(vehicle.position(), vehicle.getYRot());
        return candidate == null ? null : new Exit(candidate.feet(), candidate.start(), Float.NaN);
    }

    private boolean sameExit() {
        if (!settled()) return false;
        Exit latest;
        if (boatShore == null) latest = findExit();
        else {
            var candidate = boatShore.facing(vehicle.getX(), vehicle.getZ(), exit.yaw());
            latest = candidate == null ? null : new Exit(candidate.feet(), candidate.start(), candidate.yaw());
        }
        return latest != null && latest.start().equals(exit.start()) && latest.feet().distanceToSqr(exit.feet()) <= .16;
    }

    private void updateWaypoints(List<Step> path) {
        List<Vec3> points = new ArrayList<>(path.size() + 2);
        if (phase != Phase.WALK) points.add(vehicle.position());
        points.add(phase == Phase.WALK ? player.position() : exit.feet());
        for (Step step : path) points.add(new Vec3(step.to().centerX(), step.to().y(), step.to().centerZ()));
        waypoints = List.copyOf(points);
    }

    private void arrive() {
        status = Status.ARRIVED;
        detail = "Reached the walking stop after the vehicle transfer.";
    }

    private void fail(String reason) {
        status = Status.FAILED;
        detail = reason;
    }
}
