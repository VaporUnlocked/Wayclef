package dev.soprano.game;

import dev.soprano.path.Move;
import dev.soprano.path.Node;
import dev.soprano.path.Step;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * Converts verified path edges into vanilla keyboard input. Each tick uses the
 * current player pose, which may differ from the preceding tick's prediction.
 */
public final class PathFollower {
    public enum Status { MOVING, ARRIVED, BLOCKED }

    private enum AirPhase { APPROACH, LAUNCHED, AIRBORNE, LANDED }

    private static final double FLOOR_TOLERANCE = .07;
    private static final double CENTER_TOLERANCE = .18;
    private static final double TAKEOFF_TOLERANCE = .12;
    private static final double TOUCHDOWN_TOLERANCE = .32;
    private static final double SETTLED_SPEED = .04;
    private static final double MAX_VIEW_TURN = 32;
    private static final double MAX_WALK_VIEW_TURN = 17;
    private static final double TURN_LOOKAHEAD = .56;
    private static final double TURN_EXIT_OFFSET = .30;
    private static final double WALK_HANDOFF_RADIUS = .23;
    private static final double ROUND_HANDOFF_RADIUS = .31;
    private static final int STALL_TICKS = 50;
    private static final int MAX_EDGE_TICKS = 160;
    private static final int MAX_LAUNCH_TICKS = 28;
    private static final int MAX_AIR_TICKS = 40;

    private final MinecraftTerrain terrain;
    private final LocalPlayer player;
    private List<Step> path;
    private final Node directTarget;
    private final boolean steerView;
    private Status status = Status.MOVING;
    private String message = "Following the path.";
    private Vec3 previousFeet;
    private Vec2 walkDirection;
    private AirPhase airPhase = AirPhase.APPROACH;
    private int stepIndex;
    private int edgeTicks;
    private int ticksSinceProgress;
    private int phaseTicks;
    private double bestDistance = Double.POSITIVE_INFINITY;

    public PathFollower(MinecraftTerrain terrain, LocalPlayer player, List<Step> path, boolean steerView) {
        this(terrain, player, path, null, steerView);
    }

    public PathFollower(MinecraftTerrain terrain, LocalPlayer player, Node target, boolean steerView) {
        this(terrain, player, List.of(), Objects.requireNonNull(target), steerView);
    }

    private PathFollower(MinecraftTerrain terrain, LocalPlayer player, List<Step> path, Node directTarget, boolean steerView) {
        this.terrain = Objects.requireNonNull(terrain);
        this.player = Objects.requireNonNull(player);
        this.path = List.copyOf(path);
        this.directTarget = directTarget;
        this.steerView = steerView;
        previousFeet = feet();
        if (this.path.isEmpty() && directTarget == null) arrive();
    }

    public Status status() { return status; }
    public String message() { return message; }
    public int completedSteps() { return stepIndex; }
    public int totalSteps() { return directTarget == null ? path.size() : 1; }
    List<Step> replaceFuturePath(List<Step> updated, int discardSteps) {
        if (directTarget != null || status != Status.MOVING || updated.size() <= stepIndex
            || discardSteps < 0 || discardSteps > stepIndex
            || !path.get(stepIndex).equals(updated.get(stepIndex))) {
            throw new IllegalArgumentException("A continuation must preserve the active walking edge.");
        }
        path = List.copyOf(updated.subList(discardSteps, updated.size()));
        stepIndex -= discardSteps;
        return path;
    }
    Step nextStep() { return stepIndex < path.size() ? path.get(stepIndex) : null; }
    boolean committedAirMove() {
        return airPhase == AirPhase.LAUNCHED || airPhase == AirPhase.AIRBORNE
            || ridingBubble() && (player.isInWater() || !player.onGround());
    }
    boolean ridingBubble() { return nextStep() != null && nextStep().move() == Move.BUBBLE; }
    /** Camera-relative vanilla movement vector for the walking input returned by the last tick. */
    public Vec2 walkDirection() { return walkDirection; }

    /** Run once before vanilla applies local movement for this tick. */
    public Input tick() {
        walkDirection = null;
        if (status != Status.MOVING) return Input.EMPTY;
        terrain.beginSearchSlice();
        try {
            Input intent = followTick();
            return SprintControl.request(intent, walkDirection, path, stepIndex, terrain, player);
        } finally {
            terrain.endSearchSlice();
        }
    }

    private Input followTick() {
        Vec3 feet = feet();
        double displacement = square(feet.x - previousFeet.x) + square(feet.y - previousFeet.y)
            + square(feet.z - previousFeet.z);
        if (displacement > 6.25) return block("A position correction interrupted movement.");
        previousFeet = feet;
        boolean playerClear = ridingBubble() ? terrain.isPlayerClearOnBubble(nextStep()) : terrain.isPlayerClear();
        if (!playerClear) return block(terrain.obstructionReason("The space around the player became unsafe."));
        Vec3 velocity = player.getDeltaMovement();
        double nextY = feet.y + (player.onGround() ? Math.max(0, velocity.y) : velocity.y);
        if (!terrain.exclusionClear(feet.x, feet.y, feet.z, feet.x + velocity.x, nextY, feet.z + velocity.z)) {
            return block(terrain.obstructionReason("The next movement is excluded."));
        }
        if (directTarget != null) return finishApproach(feet);

        for (int advanced = 0; advanced < 3; advanced++) {
            Step step = path.get(stepIndex);
            Step next = stepIndex + 1 < path.size() ? path.get(stepIndex + 1) : null;
            if (advanced != 0 && terrain.closedDoorOnStep(step) != null) return Input.EMPTY;
            if (!terrain.canTraverse(step.from(), step.to(), step.move()))
                return block(terrain.obstructionReason("The next edge changed. A new path is needed."));
            if (++edgeTicks > MAX_EDGE_TICKS) return block("The next waypoint took too long to reach.");
            if (step.move() == Move.BUBBLE) {
                if (!bubbleReached(step, next, feet)) return crossBubbleEdge(step, next, feet);
                if (!advance()) return Input.EMPTY;
                continue;
            }
            if (step.move() == Move.CLIMB) {
                if (!climbReached(step, feet)) return crossClimbEdge(step, feet);
                if (!advance()) return Input.EMPTY;
                continue;
            }
            if (step.move() != Move.WALK) {
                if (touchdownOn(step, feet) && canContinueTurn(terrain, step, next, feet, velocity,
                    walkingAcceleration(), ROUND_HANDOFF_RADIUS)) {
                    if (!advance()) return Input.EMPTY;
                    continue;
                }
                return crossAirEdge(step, feet);
            }

            Vec3 goal = center(step.to());
            boolean continuous = continuesAlong(step, next);
            boolean descending = step.to().y16() < step.from().y16();
            Vec3 turn = turnTarget(step, next, feet, goal);
            boolean movingTurn = !continuous && player.onGround() && next != null
                && canContinueTurn(terrain, step, next, feet, velocity, walkingAcceleration(),
                    turn == null ? WALK_HANDOFF_RADIUS : ROUND_HANDOFF_RADIUS);
            boolean reached = player.onGround() && Math.abs(feet.y - goal.y) <= FLOOR_TOLERANCE
                && (horizontalDistance(feet, goal) <= (continuous ? .23 : CENTER_TOLERANCE)
                    || continuous && justPassed(step, feet, goal));
            if (movingTurn || reached && (continuous || horizontalSpeed() <= SETTLED_SPEED)
                || descending && continuous && passedWalkingDescent(step, next, feet, goal)) {
                if (!advance()) return Input.EMPTY;
                continue;
            }
            if (!nearEdge(feet, step, .62)
                || feet.y < Math.min(center(step.from()).y, goal.y) - .15
                || feet.y > Math.max(center(step.from()).y, goal.y) + .65)
                return block("The player moved outside the verified walking edge.");

            if (stalled(goal, feet)) return block("Movement is obstructed near the next waypoint.");
            if (turn != null && horizontalDistance(feet, turn) > .09) return walkToward(feet, turn);
            if (player.onGround() && !terrain.canApproach(step.to())
                && !(descending && terrain.canFinishWalkingDescent(feet, step.to()))) {
                Vec3 source = center(step.from());
                if (stepIndex == 0 && horizontalDistance(feet, source) > CENTER_TOLERANCE
                    && terrain.canApproach(step.from())) {
                    return terrain.isClearOfEntities(step.from())
                        ? approachCenter(feet, source, CENTER_TOLERANCE, true) : Input.EMPTY;
                }
                return block("The walking approach from the player's current position became unsafe.");
            }
            if (!terrain.isClearOfEntities(step.to())) return Input.EMPTY;
            if (!continuous && player.onGround() && Math.abs(feet.y - goal.y) <= FLOOR_TOLERANCE) {
                if (next != null && keepWalkingToTurn(terrain, step, next, feet, velocity, walkingAcceleration()))
                    return walkToward(feet, goal);
                return approachCenter(feet, goal, CENTER_TOLERANCE, true);
            }
            if (descending && !player.onGround() && (!continuous || forwardProgress(step, feet, goal) >= 0))
                return approachWhileAirborne(feet, goal, true);
            return walkToward(feet, goal);
        }
        return Input.EMPTY;
    }

    private Input finishApproach(Vec3 feet) {
        Vec3 target = center(directTarget);
        double height = Math.abs(feet.y - target.y);
        if (player.onGround() && height <= FLOOR_TOLERANCE && horizontalDistance(feet, target) <= CENTER_TOLERANCE
            && horizontalSpeed() <= SETTLED_SPEED) {
            stepIndex = 1;
            arrive();
            return Input.EMPTY;
        }
        if (height > 1.15 || horizontalDistance(feet, target) > 1.8 || stalled(target, feet))
            return block("Could not settle on the destination's footing.");
        if (player.onGround() && !terrain.canApproach(directTarget))
            return block("The destination approach became unsafe.");
        if (!terrain.isClearOfEntities(directTarget)) return Input.EMPTY;
        return height <= FLOOR_TOLERANCE ? approachCenter(feet, target, CENTER_TOLERANCE, true) : walkToward(feet, target);
    }

    private Vec3 turnTarget(Step step, Step next, Vec3 feet, Vec3 goal) {
        if (!player.onGround() || horizontalDistance(feet, goal) > TURN_LOOKAHEAD
            || Math.abs(feet.y - goal.y) > WalkingSweep.EPSILON) return null;
        Vec3 target = roundedWalkTarget(step, next);
        return target != null && terrain.closedDoorOnStep(next) == null
            && terrain.canTraverse(next.from(), next.to(), next.move()) && safeTurnMovement(feet, target) ? target : null;
    }

    static Vec3 roundedWalkTarget(Step incoming, Step outgoing) {
        if (outgoing == null || incoming.move() != Move.WALK || outgoing.move() != Move.WALK
            || !incoming.to().equals(outgoing.from()) || outgoing.from().y16() != outgoing.to().y16()) return null;
        double ix = incoming.to().x() - incoming.from().x(), iz = incoming.to().z() - incoming.from().z();
        double ox = outgoing.to().x() - outgoing.from().x(), oz = outgoing.to().z() - outgoing.from().z();
        double length = Math.hypot(ox, oz);
        if (length < .01 || Math.abs(ix * oz - iz * ox) < .01) return null;
        return center(incoming.to()).add(ox * TURN_EXIT_OFFSET / length, 0, oz * TURN_EXIT_OFFSET / length);
    }

    private boolean safeTurnMovement(Vec3 feet, Vec3 target) {
        if (!terrain.canRoundWalk(feet, target)) return false;
        return terrain.canRoundWalk(feet, predictWalking(feet, target, player.getDeltaMovement(), walkingAcceleration()));
    }

    private double walkingAcceleration() {
        double friction = terrain.groundDrag() / .91;
        return player.getSpeed() * (.216 / (friction * friction * friction));
    }

    static boolean canContinueTurn(MinecraftTerrain terrain, Step incoming, Step outgoing, Vec3 feet, Vec3 velocity,
                                   double acceleration, double radius) {
        if (!connectedForwardWalk(incoming, outgoing)) return false;
        Vec3 corner = center(incoming.to());
        if (Math.abs(feet.y - corner.y) > FLOOR_TOLERANCE
            || horizontalDistance(feet, corner) > radius && !justPassed(incoming, feet, corner)) return false;
        Vec3 target = center(outgoing.to());
        return terrain.closedDoorOnStep(outgoing) == null && terrain.canTraverse(outgoing.from(), outgoing.to(), Move.WALK)
            && terrain.canWalkThroughTurn(feet, target)
            && terrain.canSteerThroughTurn(feet, predictWalking(feet, target, velocity, acceleration));
    }

    static boolean keepWalkingToTurn(MinecraftTerrain terrain, Step incoming, Step outgoing, Vec3 feet, Vec3 velocity,
                                     double acceleration) {
        if (!connectedForwardWalk(incoming, outgoing)) return false;
        Vec3 corner = center(incoming.to());
        double distance = horizontalDistance(feet, corner);
        if (distance <= WALK_HANDOFF_RADIUS || distance > TURN_LOOKAHEAD) return false;
        Vec3 predicted = predictWalking(feet, corner, velocity, acceleration);
        return forwardProgress(incoming, predicted, corner) <= .05 && horizontalDistance(predicted, corner) < distance
            && terrain.closedDoorOnStep(outgoing) == null && terrain.canTraverse(outgoing.from(), outgoing.to(), Move.WALK)
            && terrain.canSteerThroughTurn(feet, predicted);
    }

    private static boolean connectedForwardWalk(Step incoming, Step outgoing) {
        if (outgoing == null || outgoing.move() != Move.WALK || !incoming.to().equals(outgoing.from())) return false;
        int inX = incoming.to().x() - incoming.from().x(), inZ = incoming.to().z() - incoming.from().z();
        int outX = outgoing.to().x() - outgoing.from().x(), outZ = outgoing.to().z() - outgoing.from().z();
        return inX * outZ != inZ * outX || inX * outX + inZ * outZ >= 0;
    }

    private boolean touchdownOn(Step step, Vec3 feet) {
        if ((airPhase != AirPhase.AIRBORNE && airPhase != AirPhase.LANDED) || !player.onGround()) return false;
        Vec3 landing = center(step.to());
        return horizontalDistance(feet, landing) <= TOUCHDOWN_TOLERANCE && Math.abs(feet.y - landing.y) <= FLOOR_TOLERANCE;
    }

    private static Vec3 predictWalking(Vec3 feet, Vec3 target, Vec3 velocity, double acceleration) {
        double distance = horizontalDistance(feet, target);
        double steering = distance < .01 ? 0 : acceleration / distance;
        return feet.add(velocity.x + (target.x - feet.x) * steering, 0,
            velocity.z + (target.z - feet.z) * steering);
    }

    private boolean passedWalkingDescent(Step step, Step next, Vec3 feet, Vec3 goal) {
        return forwardProgress(step, feet, goal) >= .025 && horizontalDistance(feet, goal) <= .61
            && feet.y <= goal.y + .18 && nearEdge(feet, next, .34)
            && terrain.closedDoorOnStep(next) == null && terrain.canTraverse(next.from(), next.to(), Move.WALK)
            && terrain.canFinishWalkingDescent(feet, next.to());
    }

    private boolean bubbleReached(Step step, Step next, Vec3 feet) {
        Vec3 target = center(step.to());
        if (!terrain.isBubblePoint(step.to())) {
            return player.onGround() && !player.isInWater() && Math.abs(feet.y - target.y) <= FLOOR_TOLERANCE
                && horizontalDistance(feet, target) <= CENTER_TOLERANCE && horizontalSpeed() <= SETTLED_SPEED;
        }
        boolean nearExit = bubbleExit(next) && nearEdge(feet, next, .38);
        if (!nearExit && horizontalDistance(feet, target) > .30) return false;
        int rise = step.to().y16() - step.from().y16();
        if (rise > 0) return feet.y >= target.y - FLOOR_TOLERANCE;
        if (rise < 0) return feet.y <= target.y + FLOOR_TOLERANCE;
        return player.isInWater() || terrain.sinkingBubble(step) && !player.onGround();
    }

    private Input crossBubbleEdge(Step step, Step next, Vec3 feet) {
        Vec3 source = center(step.from()), target = center(step.to());
        boolean sinking = terrain.sinkingBubble(step);
        boolean entering = !terrain.isBubblePoint(step.from());
        boolean exiting = !terrain.isBubblePoint(step.to());
        boolean steeringToExit = bubbleExit(next) && (sinking ? feet.y <= target.y + .32 : feet.y >= target.y - .18)
            && terrain.canTraverse(next.from(), next.to(), Move.BUBBLE);
        double maxY = Math.max(source.y, target.y) + (exiting && !sinking ? 5 : 2);
        double minY = Math.min(source.y, target.y) - (entering && sinking ? 1.25 : .15);
        if (!nearEdge(feet, step, .58) && !(steeringToExit && nearEdge(feet, next, .40))
            || feet.y < minY || feet.y > maxY) return block("The player left the verified bubble elevator.");
        if (stalled(target, feet)) return block("Could not reach the next bubble elevator waypoint.");
        Node destination = steeringToExit ? next.to() : step.to();
        if (!terrain.isClearOfEntities(destination)) return Input.EMPTY;
        if (steeringToExit) target = center(destination);
        if (exiting && player.onGround() && !player.isInWater() && Math.abs(feet.y - target.y) <= FLOOR_TOLERANCE)
            return approachCenter(feet, target, CENTER_TOLERANCE, true);
        // Crouching protects feet at the bottom of a downward column. Let vanilla provide the vertical motion.
        boolean crouch = sinking && (!entering || player.isInWater());
        double dx = target.x - feet.x, dz = target.z - feet.z;
        if (!exiting && !steeringToExit && player.isInWater()) {
            Vec3 drift = player.getDeltaMovement();
            dx -= drift.x * 2;
            dz -= drift.z * 2;
        }
        if (Math.hypot(dx, dz) <= .12) return keys(0, 0, false, crouch);
        aim(feet, target);
        walkDirection = walkingDirection(dx, dz, player.getYRot());
        return keys(dx, dz, false, crouch);
    }

    private boolean bubbleExit(Step step) {
        return step != null && step.move() == Move.BUBBLE && !terrain.isBubblePoint(step.to());
    }

    private boolean climbReached(Step step, Vec3 feet) {
        Vec3 target = center(step.to());
        if (horizontalDistance(feet, target) > .22) return false;
        double height = feet.y - target.y;
        if (terrain.canFinish(step.to())) {
            return player.onGround() && Math.abs(height) <= FLOOR_TOLERANCE && horizontalSpeed() <= SETTLED_SPEED;
        }
        if (!player.onClimbable()) return false;
        int rise = step.to().y16() - step.from().y16();
        if (rise > 0) return height >= 0 && height <= .26;
        if (rise < 0) return height >= -.18 && height <= .06;
        return Math.abs(height) <= .15;
    }

    private Input crossClimbEdge(Step step, Vec3 feet) {
        Vec3 source = center(step.from()), target = center(step.to());
        if (!nearEdge(feet, step, .52) || feet.y < Math.min(source.y, target.y) - .24
            || feet.y > Math.max(source.y, target.y) + .70)
            return block("The player left the verified ladder corridor.");
        if (stalled(target, feet)) return block("Could not reach the next ladder position.");
        if (!terrain.isClearOfEntities(step.to())) return Input.EMPTY;

        double height = target.y - feet.y;
        boolean onLadder = player.onClimbable();
        if (player.onGround() && terrain.canFinish(step.to()) && Math.abs(height) <= FLOOR_TOLERANCE)
            return approachCenter(feet, target, CENTER_TOLERANCE);
        boolean jump = onLadder && height > .02;
        // Sneaking holds a lateral ladder exit at its elevation. Downward travel uses vanilla sliding.
        boolean holdRung = onLadder && !player.onGround() && step.to().y16() == step.from().y16() && !jump;
        if (horizontalDistance(feet, target) <= .11) return keys(0, 0, jump, holdRung);
        aim(feet, target);
        return keys(target.x - feet.x, target.z - feet.z, jump, holdRung);
    }

    private Input crossAirEdge(Step step, Vec3 feet) {
        Vec3 takeoff = center(step.from());
        Vec3 landing = center(step.to());
        if (airPhase == AirPhase.APPROACH) {
            if (step.gapJump()) return launchGap(step, feet, landing);
            if (!player.onGround() || Math.abs(feet.y - takeoff.y) > FLOOR_TOLERANCE)
                return block("The takeoff is no longer reachable from the ground.");
            if (stalled(takeoff, feet)) return block("The takeoff is obstructed.");
            if (!terrain.canApproach(step.from())) return block("The takeoff approach became unsafe.");
            if (!terrain.isClearOfEntities(step.from())) return Input.EMPTY;
            if (horizontalDistance(feet, takeoff) > TAKEOFF_TOLERANCE || horizontalSpeed() > SETTLED_SPEED)
                return approachCenter(feet, takeoff, TAKEOFF_TOLERANCE);
            if (!terrain.isClearOfEntities(step.to())) return Input.EMPTY;

            aim(feet, landing);
            if (steerView && Math.abs(turnRemaining(feet, landing)) > 22.5) return Input.EMPTY;
            enter(AirPhase.LAUNCHED);
            return keys(landing.x - feet.x, landing.z - feet.z, step.move() == Move.JUMP);
        }

        if (airPhase == AirPhase.LANDED) {
            if (!player.onGround() || Math.abs(feet.y - landing.y) > FLOOR_TOLERANCE
                || !terrain.canApproach(step.to()))
                return block("The landing could not be settled safely.");
            if (horizontalDistance(feet, landing) <= CENTER_TOLERANCE && horizontalSpeed() <= SETTLED_SPEED) {
                advance();
                return Input.EMPTY;
            }
            if (stalled(landing, feet)) return block("Movement stalled while centering the landing.");
            return approachCenter(feet, landing, CENTER_TOLERANCE);
        }

        if (airPhase == AirPhase.LAUNCHED && !player.onGround()) enter(AirPhase.AIRBORNE);
        if (airPhase == AirPhase.AIRBORNE && player.onGround()) {
            if (horizontalDistance(feet, landing) > TOUCHDOWN_TOLERANCE || Math.abs(feet.y - landing.y) > FLOOR_TOLERANCE)
                return block("The player landed away from the verified landing.");
            enter(AirPhase.LANDED);
            return Input.EMPTY;
        }

        int phaseLimit = airPhase == AirPhase.AIRBORNE ? MAX_AIR_TICKS
            : step.move() == Move.JUMP ? 6 : MAX_LAUNCH_TICKS;
        if (++phaseTicks > phaseLimit) return block("The jump or drop did not complete.");
        double maximumY = takeoff.y + (step.move() == Move.JUMP ? 1.35 : .20);
        double minimumY = Math.min(takeoff.y, landing.y) - .13;
        if (!nearEdge(feet, step, .52) || feet.y > maximumY || feet.y < minimumY)
            return block("The player left the verified jump or drop corridor.");
        if (!terrain.isClearOfEntities(step.to())) return Input.EMPTY;
        if (airPhase == AirPhase.AIRBORNE) return approachWhileAirborne(feet, landing);
        if (step.move() == Move.DROP && coastsInto(feet, landing, CENTER_TOLERANCE)) return Input.EMPTY;
        return moveToward(feet, landing);
    }

    private Input launchGap(Step step, Vec3 feet, Vec3 landing) {
        Vec3 launch = MinecraftTerrain.gapTakeoff(step.from(), step.to());
        if (!player.onGround() || Math.abs(feet.y - launch.y) > FLOOR_TOLERANCE || !terrain.canApproachGapTakeoff(feet, launch))
            return block("The one-block gap has no verified approach to its takeoff.");
        if (stalled(launch, feet)) return block("Could not settle near the one-block gap's takeoff edge.");
        if (horizontalDistance(feet, launch) > .05 || horizontalSpeed() > .025) return approachCenter(feet, launch, .05);
        if (!terrain.gapArcClearOfEntities(step.from(), step.to())) return Input.EMPTY;
        if (player.isSprinting()) return Input.EMPTY;
        if (Math.abs(player.getAttributeValue(Attributes.MOVEMENT_SPEED) - .1) > .008
            || Math.abs(player.getAttributeValue(Attributes.JUMP_STRENGTH) - .42) > .008
            || Math.abs(player.getAttributeValue(Attributes.GRAVITY) - .08) > .004
            || player.hasEffect(MobEffects.JUMP_BOOST) || player.hasEffect(MobEffects.LEVITATION)
            || player.hasEffect(MobEffects.SLOW_FALLING)) return block("A short gap jump requires ordinary walking and jumping physics.");

        Vec3 heading = feet.add(step.to().x() - step.from().x(), 0, step.to().z() - step.from().z());
        aim(feet, heading);
        if (Math.abs(turnRemaining(feet, heading)) > 3) {
            return steerView ? Input.EMPTY : block("Face directly across the one-block gap or enable view steering.");
        }
        Vec3 velocity = player.getDeltaMovement();
        double along = (velocity.x * (step.to().x() - step.from().x()) + velocity.z * (step.to().z() - step.from().z())) * .5;
        if (along < -.008) return Input.EMPTY;
        enter(AirPhase.LAUNCHED);
        return keys(landing.x - feet.x, landing.z - feet.z, true);
    }

    private Input approachCenter(Vec3 feet, Vec3 goal, double tolerance) {
        return approachCenter(feet, goal, tolerance, false);
    }

    private Input approachCenter(Vec3 feet, Vec3 goal, double tolerance, boolean walking) {
        Vec3 velocity = player.getDeltaMovement();
        double dx = goal.x - feet.x, dz = goal.z - feet.z;
        double speed = Math.hypot(velocity.x, velocity.z);
        if (speed > .012) {
            double coastScale = 1 / (1 - terrain.groundDrag());
            double afterCoastX = dx - velocity.x * coastScale;
            double afterCoastZ = dz - velocity.z * coastScale;
            if (Math.hypot(afterCoastX, afterCoastZ) <= tolerance) return Input.EMPTY;
            // With a nearby target and substantial drift, let the movement keys brake normally.
            if (Math.hypot(dx, dz) <= tolerance + .12 && speed > SETTLED_SPEED
                && afterCoastX * velocity.x + afterCoastZ * velocity.z < -.05 * speed)
                return walking ? walkingKeys(-velocity.x, -velocity.z) : keys(-velocity.x, -velocity.z, false);
        }
        if (Math.hypot(dx, dz) <= tolerance) return Input.EMPTY;
        return walking ? walkToward(feet, goal) : moveToward(feet, goal);
    }

    private boolean coastsInto(Vec3 feet, Vec3 goal, double tolerance) {
        Vec3 velocity = player.getDeltaMovement();
        double scale = 1 / (1 - terrain.groundDrag());
        return Math.hypot(velocity.x, velocity.z) > .012
            && Math.hypot(goal.x - feet.x - velocity.x * scale, goal.z - feet.z - velocity.z * scale) <= tolerance;
    }

    private Input approachWhileAirborne(Vec3 feet, Vec3 landing) {
        return approachWhileAirborne(feet, landing, false);
    }

    private Input approachWhileAirborne(Vec3 feet, Vec3 landing, boolean walking) {
        Vec3 velocity = player.getDeltaMovement();
        double dx = landing.x - feet.x, dz = landing.z - feet.z;
        double distance = Math.hypot(dx, dz);
        double speed = Math.hypot(velocity.x, velocity.z);
        if (velocity.y < 0 && speed > .015) {
            // Approximate the remaining falling ticks to choose between steering and releasing input.
            double verticalDiscriminant = square(velocity.y) + .16 * Math.max(0, feet.y - landing.y);
            double ticks = Math.clamp((Math.sqrt(verticalDiscriminant) + velocity.y) / .08, 1, 7);
            double drift = (1 - Math.pow(.91, ticks)) / .09;
            double errorX = dx - velocity.x * drift;
            double errorZ = dz - velocity.z * drift;
            if (Math.hypot(errorX, errorZ) <= .10) return Input.EMPTY;
            if (distance <= .48 && errorX * velocity.x + errorZ * velocity.z < -.08 * speed)
                return walking ? walkingKeys(-velocity.x, -velocity.z) : keys(-velocity.x, -velocity.z, false);
        }
        if (distance <= .075) return Input.EMPTY;
        return walking ? walkToward(feet, landing) : moveToward(feet, landing);
    }

    private Input moveToward(Vec3 from, Vec3 to) {
        aim(from, to);
        return keys(to.x - from.x, to.z - from.z, false);
    }

    private Input walkToward(Vec3 from, Vec3 to) {
        if (steerView && horizontalDistance(from, to) >= .025) {
            double turn = turnRemaining(from, to);
            player.setYRot(player.getYRot() + (float) Math.clamp(turn * .55, -MAX_WALK_VIEW_TURN, MAX_WALK_VIEW_TURN));
        }
        return walkingKeys(to.x - from.x, to.z - from.z);
    }

    private Input walkingKeys(double worldX, double worldZ) {
        walkDirection = walkingDirection(worldX, worldZ, player.getYRot());
        return keys(worldX, worldZ, false);
    }

    static Vec2 walkingDirection(double worldX, double worldZ, float yaw) {
        double length = Math.hypot(worldX, worldZ);
        if (length < 1e-5) return Vec2.ZERO;
        double rotation = Math.toRadians(yaw);
        double sin = Math.sin(rotation), cos = Math.cos(rotation);
        return new Vec2((float) ((worldX * cos + worldZ * sin) / length), (float) ((worldZ * cos - worldX * sin) / length));
    }

    private void aim(Vec3 from, Vec3 to) {
        if (!steerView || horizontalDistance(from, to) < .025) return;
        player.setYRot(player.getYRot() + (float) Math.clamp(turnRemaining(from, to), -MAX_VIEW_TURN, MAX_VIEW_TURN));
    }

    private double turnRemaining(Vec3 from, Vec3 to) {
        double desired = Math.toDegrees(Math.atan2(from.x - to.x, to.z - from.z));
        return Math.IEEEremainder(desired - player.getYRot(), 360);
    }

    private Input keys(double worldX, double worldZ, boolean jump) {
        return keys(worldX, worldZ, jump, false);
    }

    private Input keys(double worldX, double worldZ, boolean jump, boolean sneak) {
        if (Math.hypot(worldX, worldZ) < 1e-5) return new Input(false, false, false, false, jump, sneak, false);
        double direction = Math.atan2(worldX, worldZ) + Math.toRadians(player.getYRot());
        int octant = Math.floorMod((int) Math.round(direction * 4 / Math.PI), 8);
        return new Input(octant == 0 || octant == 1 || octant == 7, octant >= 3 && octant <= 5,
            octant >= 1 && octant <= 3, octant >= 5 && octant <= 7, jump, sneak, false);
    }

    private static boolean continuesAlong(Step step, Step next) {
        if (next == null || !step.to().equals(next.from())) return false;
        int rise = step.to().y16() - step.from().y16();
        int nextRise = next.to().y16() - step.to().y16();
        return next.move() == Move.WALK && rise * nextRise >= 0
            && step.to().x() - step.from().x() == next.to().x() - next.from().x()
            && step.to().z() - step.from().z() == next.to().z() - next.from().z();
    }

    private static boolean justPassed(Step step, Vec3 feet, Vec3 goal) {
        return forwardProgress(step, feet, goal) >= 0 && horizontalDistance(feet, goal) <= .34;
    }

    private static double forwardProgress(Step step, Vec3 feet, Vec3 goal) {
        double dx = step.to().x() - step.from().x(), dz = step.to().z() - step.from().z();
        return (feet.x - goal.x) * dx + (feet.z - goal.z) * dz;
    }

    private static boolean nearEdge(Vec3 feet, Step step, double limit) {
        Vec3 source = center(step.from()), target = center(step.to());
        double dx = target.x - source.x, dz = target.z - source.z;
        double lengthSquared = dx * dx + dz * dz;
        double projection = lengthSquared == 0 ? 0
            : Math.clamp(((feet.x - source.x) * dx + (feet.z - source.z) * dz) / lengthSquared, 0, 1);
        return Math.hypot(feet.x - source.x - dx * projection, feet.z - source.z - dz * projection) <= limit;
    }

    private boolean stalled(Vec3 target, Vec3 feet) {
        double distance = Math.hypot(horizontalDistance(feet, target), feet.y - target.y);
        if (distance + .018 < bestDistance) {
            bestDistance = distance;
            ticksSinceProgress = 0;
        } else {
            ticksSinceProgress++;
        }
        return ticksSinceProgress >= STALL_TICKS;
    }

    private void enter(AirPhase phase) {
        airPhase = phase;
        phaseTicks = 0;
        ticksSinceProgress = 0;
        bestDistance = Double.POSITIVE_INFINITY;
    }

    private boolean advance() {
        enter(AirPhase.APPROACH);
        edgeTicks = 0;
        if (++stepIndex == path.size()) {
            arrive();
            return false;
        }
        return true;
    }

    private void arrive() {
        status = Status.ARRIVED;
        message = "Destination reached.";
    }

    private Input block(String reason) {
        status = Status.BLOCKED;
        message = reason;
        return Input.EMPTY;
    }

    private Vec3 feet() { return new Vec3(player.getX(), player.getY(), player.getZ()); }
    private double horizontalSpeed() {
        Vec3 velocity = player.getDeltaMovement();
        return Math.hypot(velocity.x, velocity.z);
    }
    private static Vec3 center(Node node) { return new Vec3(node.centerX(), node.y(), node.centerZ()); }
    private static double horizontalDistance(Vec3 from, Vec3 to) { return Math.hypot(from.x - to.x, from.z - to.z); }
    private static double square(double value) { return value * value; }
}
