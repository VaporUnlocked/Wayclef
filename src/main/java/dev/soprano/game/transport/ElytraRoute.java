package dev.soprano.game.transport;

import dev.soprano.game.MinecraftTerrain;
import dev.soprano.game.Navigation;
import dev.soprano.game.PathFollower;
import dev.soprano.path.Goal;
import dev.soprano.path.IncrementalSearch;
import dev.soprano.path.Node;
import dev.soprano.path.RouteExclusions;
import dev.soprano.path.Step;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static dev.soprano.game.transport.ElytraReservePolicy.Action.CONTINUE;
import static dev.soprano.game.transport.ElytraReservePolicy.Action.LAND;

/** Plans a descending glide to supported ground and follows it through ordinary player look controls. */
public final class ElytraRoute implements TransportRoute {
    private static final int MAX_PLANNING_TICKS = 36;
    private static final int MAX_LANDING_CANDIDATES = 8;
    private static final int MAX_DIVERSION_PREDICTIONS = 16;
    private static final int TICKS_PER_WEAR = 20;
    private static final int MAX_PREDICTION_TICKS = 192;
    private static final double LANDING_GAP = .055;
    private static final double MIN_SPEED = .10;
    private static final double MAX_LANDING_DRIFT = 8;
    private static final int[] DIVERSION_DISTANCES = {3, 5, 8, 12, 17, 24, 34, 46, 64, 88};
    private static final int[] DIVERSION_HEADINGS = {0, -16, 16, -32, 32, -48, 48};

    private record Approach(Node surface, List<Vec3> path, ElytraReservePolicy.Landing estimate, ElytraSupplies.Rocket rocket) { }

    private final ClientLevel level;
    private final LocalPlayer player;
    private final Goal goal;
    private final Navigation.Options options;
    private final MinecraftTerrain terrain;
    private final double flightHalfWidth;
    private final double flightHeight;
    private final BlockPos.MutableBlockPos block = new BlockPos.MutableBlockPos();
    private final Vec3 departure;
    private final List<Node> landings;
    private Status status = Status.PLANNING;
    private String detail = "Checking landing sites.";
    private List<Vec3> waypoints = List.of();
    private List<Vec3> flightPath = List.of();
    private GlideSearch flightSearch;
    private IncrementalSearch landingConnection;
    private List<Step> plannedFinish;
    private int landingIndex;
    private int planningTicks;
    private int leg = 1;
    private int slowTicks;
    private Vec3 landing;
    private Node landingSurface;
    private boolean crossesVoid;
    private ElytraReservePolicy.Reason diversion;
    private ElytraSupplies.Rocket pendingBoost;
    private long minimumBoostUntil;
    private long boostedUntil;
    private long rocketRetryAfter;
    private long predictedTouchdown;
    private long lastDiversionCheck;
    private long lastVoidCheck;
    private int diversionAttempts;
    private IncrementalSearch groundSearch;
    private PathFollower groundFollower;
    private Node walkingStart;

    public ElytraRoute(ClientLevel level, LocalPlayer player, Goal goal, Navigation.Options options) {
        this.level = level;
        this.player = player;
        this.goal = goal;
        this.options = options;
        terrain = new MinecraftTerrain(level, player, options.avoidMobs());
        var dimensions = player.getDimensions(Pose.FALL_FLYING);
        flightHalfWidth = dimensions.width() * .5;
        flightHeight = dimensions.height();
        departure = position();
        if (!player.isFallFlying()) {
            landings = List.of();
            fail("Start gliding with Elytra and pick a landing point below you.");
            return;
        }
        landings = findLandings();
    }

    @Override
    public void advance(int expansions, long budgetNanos) {
        if (status == Status.PLANNING) {
            if (diversion != null) seekDiversion(diversion, null);
            else advanceFlightPlan(expansions, budgetNanos);
        } else if (status == Status.MOVING && groundSearch != null) {
            advanceGroundPlan(expansions, budgetNanos);
        }
    }

    private List<Node> findLandings() {
        List<Node> candidates = new ArrayList<>();
        int ceiling = (int) Math.floor((departure.y - 2.25) * 16);
        Integer targetY = goal.y16();
        int minimum = targetY == null ? level.getMinY() * 16 : targetY;
        int maximum = targetY == null ? ceiling : Math.min(ceiling, targetY);
        addLandings(candidates, goal.x(), goal.z(), minimum, maximum);
        if (targetY != null) {
            minimum = Math.max(Node.MIN_Y16, targetY - 3 * 16);
            maximum = Math.min(ceiling, targetY + 3 * 16);
        }
        int offset = Math.max(3, Math.min(goal.radius(), 5));
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx != 0 || dz != 0) {
                    addLandings(candidates, goal.x() + dx * offset, goal.z() + dz * offset, minimum, maximum);
                }
            }
        }
        return candidates.stream().distinct()
            .sorted(Comparator.comparing((Node node) -> !goal.matches(node))
                .thenComparingDouble(node -> Math.hypot(node.x() - goal.x(), node.z() - goal.z()))
                .thenComparing(Comparator.comparingInt(Node::y16).reversed()))
            .limit(MAX_LANDING_CANDIDATES)
            .toList();
    }

    private void addLandings(List<Node> candidates, int x, int z, int minimum, int maximum) {
        if (!Node.validColumn(x) || !Node.validColumn(z)) return;
        terrain.floors(x, z, minimum, maximum).stream()
            .sorted(Comparator.comparingInt(Node::y16).reversed())
            .filter(node -> {
                Vec3 feet = landingPosition(node);
                return terrain.canFinish(node) && clearSweep(feet, feet, .65);
            })
            .limit(2)
            .forEach(candidates::add);
    }

    private void advanceFlightPlan(int expansions, long budgetNanos) {
        if (!player.isFallFlying()) {
            fail("Gliding ended before a safe corridor could be verified.");
            return;
        }
        ElytraReservePolicy.Supplies supplies = ElytraSupplies.read(player);
        ElytraReservePolicy.Decision preliminary = ElytraReservePolicy.assess(options.elytraReserves(), supplies,
            new ElytraReservePolicy.Leg(new ElytraReservePolicy.Cost(0, 1), false, null), true, List.of());
        if (preliminary.action() != CONTINUE || ++planningTicks > MAX_PLANNING_TICKS || landings.isEmpty()) {
            seekDiversion(preliminary.action() == CONTINUE ? ElytraReservePolicy.Reason.NO_CONFIRMED_EXIT : preliminary.reason(), null);
            return;
        }
        boolean voidAhead = crossesVoid(List.of(position(), position().add(player.getDeltaMovement().scale(8))));
        if (flightSearch == null) {
            Vec3 velocity = player.getDeltaMovement();
            if (horizontalSpeed(velocity) < MIN_SPEED) {
                seekDiversion(ElytraReservePolicy.Reason.NO_CONFIRMED_EXIT, null);
                return;
            }
            Node candidate = landings.get(landingIndex);
            if (!goal.matches(candidate) && plannedFinish == null) {
                if (voidAhead) {
                    seekDiversion(ElytraReservePolicy.Reason.NO_CONFIRMED_EXIT, null);
                    return;
                }
                checkLandingConnection(candidate, expansions, budgetNanos);
                return;
            }
            landingSurface = candidate;
            landing = landingPosition(candidate);
            flightSearch = new GlideSearch(position(), velocity, landing, options.search().horizontalRange(),
                options.search().maxVisited(), this::clearSweep);
        }
        flightSearch.advance(expansions, budgetNanos);
        detail = "Checking glide corridor · " + flightSearch.visited() + " positions.";
        if (flightSearch.complete() && acceptFlightPlan(flightSearch.path())) return;
        if (voidAhead) seekDiversion(ElytraReservePolicy.Reason.NO_CONFIRMED_EXIT, null);
        else if (flightSearch.complete()) nextLanding();
    }

    private void checkLandingConnection(Node candidate, int expansions, long budgetNanos) {
        if (landingConnection == null) landingConnection = new IncrementalSearch(terrain, candidate, goal, finishConfig());
        IncrementalSearch.Result result = landingConnection.advance(expansions, budgetNanos);
        detail = "Checking the walking connection from a nearby landing.";
        if (result.status() == IncrementalSearch.Status.SEARCHING) return;
        landingConnection = null;
        if (result.status() == IncrementalSearch.Status.FOUND) plannedFinish = result.path();
        else nextLanding();
    }

    private void nextLanding() {
        flightSearch = null;
        plannedFinish = null;
        if (++landingIndex >= landings.size()) {
            seekDiversion(ElytraReservePolicy.Reason.NO_CONFIRMED_EXIT, null);
        }
    }

    private boolean acceptFlightPlan(List<Vec3> path) {
        if (path.size() < 2) return false;
        Vec3 feet = position();
        Vec3 velocity = player.getDeltaMovement();
        for (int i = 1; i < path.size(); i++) {
            Vec3 next = path.get(i);
            double dx = next.x - feet.x;
            double dz = next.z - feet.z;
            double distance = Math.hypot(dx, dz);
            if (distance < 2 || next.y > feet.y || feet.y - next.y > distance * .85) continue;
            if (dx * velocity.x + dz * velocity.z < distance * horizontalSpeed(velocity) * .55
                || !clearSweep(feet, next, .72)) continue;
            List<Vec3> verified = new ArrayList<>(path.size() - i + 1);
            verified.add(feet);
            verified.addAll(path.subList(i, path.size()));
            if (!landingAvailable(landingSurface, verified.get(verified.size() - 2))) return false;
            flightPath = List.copyOf(verified);
            crossesVoid = crossesVoid(flightPath);
            ElytraReservePolicy.Decision decision = assessFlight(feet, velocity);
            if (decision.action() != CONTINUE) {
                seekDiversion(decision.reason(), remainingApproach(feet, velocity));
                return true;
            }
            waypoints = plannedFinish == null ? flightPath : combinedPath(plannedFinish);
            status = Status.MOVING;
            detail = "Glide corridor verified · " + (flightPath.size() - 1) + " legs.";
            return true;
        }
        return false;
    }

    private ElytraReservePolicy.Decision assessFlight(Vec3 feet, Vec3 velocity) {
        int travelTicks = 0;
        Vec3 from = feet;
        for (int index = leg; index < flightPath.size() - 1; index++) {
            Vec3 next = flightPath.get(index);
            travelTicks += estimateTicks(from, next, velocity);
            from = next;
        }
        int exitTicks = estimateTicks(from, landing, velocity) + 8;
        var exit = landingEstimate(landingSurface, exitTicks, 0);
        var legBudget = new ElytraReservePolicy.Leg(cost(0, travelTicks), crossesVoid, exit);
        return ElytraReservePolicy.assess(options.elytraReserves(), ElytraSupplies.read(player), legBudget, true, List.of());
    }

    private Approach remainingApproach(Vec3 feet, Vec3 velocity) {
        if (landingSurface == null || flightPath.size() <= leg
            || !landingAvailable(landingSurface, flightPath.get(flightPath.size() - 2))) return null;
        List<Vec3> remaining = new ArrayList<>();
        remaining.add(feet);
        remaining.addAll(flightPath.subList(leg, flightPath.size()));
        int ticks = 8;
        for (int index = 1; index < remaining.size(); index++) {
            Vec3 from = remaining.get(index - 1);
            Vec3 to = remaining.get(index);
            if (!clearSweep(from, to, .72)) return null;
            ticks += estimateTicks(from, to, velocity);
        }
        return new Approach(landingSurface, List.copyOf(remaining), landingEstimate(landingSurface, ticks, 0), null);
    }

    private void seekDiversion(ElytraReservePolicy.Reason reason, Approach existing) {
        if (player.onGround() && terrain.startAtPlayer() != null) {
            fail("Landed safely after " + diversionReason(reason) + ".");
            return;
        }
        if (!player.isFallFlying()) {
            fail("Gliding ended while looking for a safe landing after " + diversionReason(reason) + ".");
            return;
        }
        Vec3 feet = position();
        Vec3 velocity = player.getDeltaMovement();
        ElytraReservePolicy.Supplies supplies = ElytraSupplies.read(player);
        List<Approach> approaches = diversionApproaches(feet, velocity, supplies, existing);
        var noExit = new ElytraReservePolicy.Leg(cost(0, 0), true, null);
        var decision = ElytraReservePolicy.assess(options.elytraReserves(), supplies, noExit, true,
            approaches.stream().map(Approach::estimate).toList());
        if (decision.action() == LAND) {
            Approach best = approaches.stream().filter(approach -> approach.estimate().equals(decision.landing())).findFirst().orElseThrow();
            commitDiversion(reason, best);
            return;
        }
        if (existing != null && diversion != null) {
            detail = "Continuing the only verified landing after " + diversionReason(reason) + ".";
            return;
        }
        diversion = reason;
        pendingBoost = null;
        flightPath = waypoints = List.of();
        if (++diversionAttempts < 4) {
            status = Status.PLANNING;
            detail = "Looking for a reachable landing after " + diversionReason(reason) + ".";
        } else {
            fail("No verified landing fits current supplies after " + diversionReason(reason) + ". Take control now.");
        }
    }

    public void requestVitalityLanding() {
        if (diversion != null || !player.isFallFlying()) return;
        Approach established = status == Status.MOVING ? remainingApproach(position(), player.getDeltaMovement()) : null;
        // Keep steering an already verified corridor if a nearer safe landing cannot be predicted.
        if (status == Status.MOVING && established == null) return;
        seekDiversion(ElytraReservePolicy.Reason.LOW_VITALITY, established);
    }

    private void commitDiversion(ElytraReservePolicy.Reason reason, Approach approach) {
        diversion = reason;
        diversionAttempts = slowTicks = 0;
        landingSurface = approach.surface();
        landing = landingPosition(landingSurface);
        flightPath = waypoints = approach.path();
        pendingBoost = approach.rocket();
        predictedTouchdown = level.getGameTime() + approach.estimate().ticksToTouchdown() + 8;
        lastDiversionCheck = level.getGameTime();
        leg = 1;
        status = Status.MOVING;
        detail = "Diverting to verified ground after " + diversionReason(reason) + ".";
    }

    private List<Approach> diversionApproaches(Vec3 feet, Vec3 velocity, ElytraReservePolicy.Supplies supplies, Approach existing) {
        List<Approach> results = new ArrayList<>();
        if (existing != null && ElytraReservePolicy.canFinish(supplies, existing.estimate().cost())) results.add(existing);
        if (supplies.durability() <= ElytraReservePolicy.MINIMUM_GLIDING_DURABILITY) return results;
        int minBoostRemaining = (int) Math.max(0, minimumBoostUntil - level.getGameTime());
        int boostRemaining = (int) Math.max(0, boostedUntil - level.getGameTime());
        ElytraSupplies.Rocket rocket = supplies.rockets() > 0 && boostRemaining == 0 && level.getGameTime() >= rocketRetryAfter
            && level.getEntitiesOfClass(FireworkRocketEntity.class, player.getBoundingBox().inflate(.6)).isEmpty()
            ? ElytraSupplies.availableRocket(player) : null;
        List<Node> candidates = nearbySurfaces(feet, velocity);
        for (Node candidate : candidates) {
            Vec3 target = landingPosition(candidate);
            if (!landingAvailable(candidate, feet) || !clearSweep(feet, target, .62)) continue;
            int early = predictDirect(feet, velocity, candidate, minBoostRemaining);
            if (early > 0) {
                int late = minBoostRemaining == boostRemaining ? early : predictDirect(feet, velocity, candidate, boostRemaining);
                if (late <= 0) continue;
                var estimate = landingEstimate(candidate, Math.max(early, late) + 5, 0);
                if (ElytraReservePolicy.canFinish(supplies, estimate.cost())) {
                    results.add(new Approach(candidate, List.of(feet, target), estimate, null));
                }
            }
        }
        // Try a rocket only when no verified glide fits. Check the full range of possible boost lengths.
        if (results.isEmpty() && rocket != null) {
            for (Node candidate : candidates) {
                if (!landingAvailable(candidate, feet)) continue;
                int fastest = Integer.MAX_VALUE;
                int slowest = 0;
                for (int duration = rocket.minimumBoostTicks(); duration <= rocket.maximumBoostTicks(); duration++) {
                    int ticks = predictDirect(feet, velocity, candidate, duration);
                    if (ticks < 0) {
                        slowest = 0;
                        break;
                    }
                    fastest = Math.min(fastest, ticks);
                    slowest = Math.max(slowest, ticks);
                }
                if (slowest > 0 && ElytraReservePolicy.canFinish(supplies, cost(1, slowest + 8))) {
                    Vec3 target = landingPosition(candidate);
                    var estimate = new ElytraReservePolicy.Landing(candidate.x(), candidate.y16(), candidate.z(),
                        fastest, cost(1, slowest + 8), true);
                    results.add(new Approach(candidate, List.of(feet, target), estimate, rocket));
                }
            }
        }
        return results;
    }

    private List<Node> nearbySurfaces(Vec3 feet, Vec3 velocity) {
        double heading = horizontalSpeed(velocity) >= MIN_SPEED
            ? Math.atan2(velocity.x, velocity.z) : Math.toRadians(-player.getYRot());
        Set<Node> surfaces = new LinkedHashSet<>();
        for (int distance : DIVERSION_DISTANCES) {
            for (int offset : DIVERSION_HEADINGS) {
                double direction = heading + Math.toRadians(offset);
                int x = floor(feet.x + Math.sin(direction) * distance);
                int z = floor(feet.z + Math.cos(direction) * distance);
                if (!Node.validColumn(x) || !Node.validColumn(z) || !level.hasChunk(x >> 4, z >> 4)) continue;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                int maximum = Math.min(floor((feet.y - .25) * 16), (top + 1) * 16);
                int minimum = Math.max(level.getMinY() * 16, floor((feet.y - distance * .85 - 1) * 16));
                terrain.floors(x, z, minimum, maximum).stream().filter(terrain::canFinish)
                    .max(Comparator.comparingInt(Node::y16)).ifPresent(surfaces::add);
            }
        }
        return surfaces.stream().sorted(Comparator.comparingInt(node -> estimateTicks(feet, landingPosition(node), velocity)))
            .limit(MAX_DIVERSION_PREDICTIONS).toList();
    }

    private int predictDirect(Vec3 start, Vec3 velocity, Node surface, int boostTicks) {
        return predictDirect(start, start, velocity, surface, boostTicks, 0);
    }

    private int predictDirect(Vec3 start, Vec3 feet, Vec3 velocity, Node surface, int boostTicks, int boostDelay) {
        Vec3 target = landingPosition(surface);
        double length = horizontalDistance(start, target);
        if (length < 2 || feet.y <= target.y) return -1;
        double gravity = player.getGravity();
        boolean slowFalling = player.hasEffect(MobEffects.SLOW_FALLING);
        var look = new ElytraFlightPhysics.Look(player.getYRot(), player.getXRot());
        for (int tick = 0; tick < MAX_PREDICTION_TICKS; tick++) {
            double speed = horizontalSpeed(velocity);
            double progress = horizontalProgress(feet, start, target);
            Vec3 nearest = start.lerp(target, clamp(progress, 0, 1));
            if (feet.y < target.y || feet.y < nearest.y - 2.1 || horizontalDistance(feet, nearest) > Math.max(3.6, speed * 3)
                || progress > 1.20 && feet.y > target.y + 2) return -1;
            Vec3 next = start.lerp(target, clamp(progress + Math.max(3.5, speed * 5) / length, 0, 1));
            boolean nearGround = nearTouchdown(feet, target, speed);
            look = ElytraFlightPhysics.steer(feet, velocity, start, target, next, nearest, nearGround, look);
            double effectiveGravity = slowFalling && velocity.y <= 0 ? Math.min(gravity, .01) : gravity;
            velocity = ElytraFlightPhysics.velocity(velocity, look, effectiveGravity, tick >= boostDelay && tick < boostDelay + boostTicks);
            next = feet.add(velocity);
            if (next.y <= target.y) {
                Vec3 touchdown = feet.lerp(next, (feet.y - target.y) / (feet.y - next.y));
                double drift = horizontalDistance(touchdown, target);
                return drift <= Math.max(1.2, Math.min(2.1, speed * 1.4))
                    && clearSweep(feet, touchdown, .54) && touchdownAvailable(touchdown, surface.y16()) ? tick + 1 : -1;
            }
            if (!clearSweep(feet, next, .54)) return -1;
            feet = next;
        }
        return -1;
    }

    private boolean landingAvailable(Node surface, Vec3 from) {
        if (!terrain.canFinish(surface)) return false;
        Vec3 feet = landingPosition(surface);
        if (!touchdownAvailable(feet, surface.y16()) || !clearSweep(feet, feet, .65)) return false;
        double length = horizontalDistance(from, feet);
        if (length < 1.0E-5) return false;
        for (int distance = 1; distance <= 3; distance++) {
            double x = feet.x + (feet.x - from.x) * distance / length;
            double z = feet.z + (feet.z - from.z) * distance / length;
            List<Node> runway = terrain.floors(floor(x), floor(z), surface.y16() - 8, surface.y16());
            if (runway.stream().noneMatch(node -> terrain.canFinish(node) && touchdownAvailable(
                new Vec3(x, node.y() + LANDING_GAP, z), node.y16()))) return false;
        }
        return true;
    }

    private boolean touchdownAvailable(Vec3 feet, int y16) {
        boolean supported = terrain.floors(floor(feet.x), floor(feet.z), y16, y16).stream().anyMatch(terrain::canFinish);
        if (!supported) return false;
        var dimensions = player.getDimensions(Pose.STANDING);
        double half = Math.max(dimensions.width() * .5, .31);
        AABB body = new AABB(feet.x - half, feet.y, feet.z - half,
            feet.x + half, feet.y + dimensions.height(), feet.z + half);
        var exclusions = Navigation.activeExclusions(options.avoidMobs());
        return level.noBlockCollision(player, body) && level.noEntityCollision(player, body)
            && exclusions.blockingAt(new RouteExclusions.Point(feet.x, feet.y, feet.z),
                new RouteExclusions.Footprint(half * 2, dimensions.height())).isEmpty();
    }

    private boolean crossesVoid(List<Vec3> path) {
        for (int index = 1; index < path.size(); index++) {
            Vec3 from = path.get(index - 1);
            Vec3 to = path.get(index);
            int samples = Math.max(1, (int) Math.ceil(horizontalDistance(from, to) / 4));
            for (int sample = 0; sample <= samples; sample++) {
                Vec3 point = from.lerp(to, (double) sample / samples);
                int x = floor(point.x), z = floor(point.z);
                if (!level.hasChunk(x >> 4, z >> 4)
                    || level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) <= level.getMinY()) return true;
            }
        }
        return false;
    }

    private static int estimateTicks(Vec3 from, Vec3 to, Vec3 velocity) {
        double speed = clamp(horizontalSpeed(velocity) * .7, .10, .55);
        double descent = Math.max(0, from.y - to.y);
        return (int) Math.ceil(Math.max(horizontalDistance(from, to) / speed, descent / .13));
    }

    private static ElytraReservePolicy.Cost cost(int rockets, int ticks) {
        return ElytraReservePolicy.Cost.forFlight(rockets, ticks, TICKS_PER_WEAR);
    }

    private static ElytraReservePolicy.Landing landingEstimate(Node node, int ticks, int rockets) {
        return new ElytraReservePolicy.Landing(node.x(), node.y16(), node.z(), ticks, cost(rockets, ticks), true);
    }

    private static String diversionReason(ElytraReservePolicy.Reason reason) {
        return switch (reason) {
            case ROCKET_RESERVE -> "reaching the rocket reserve";
            case DURABILITY_RESERVE -> "reaching the Elytra durability reserve";
            case NO_CONFIRMED_EXIT -> "losing the verified arrival corridor";
            case LOW_VITALITY -> "reaching a health or food minimum";
            case READY -> "rechecking flight safety";
        };
    }

    @Override
    public Input input() {
        if (status != Status.MOVING) return Input.EMPTY;
        if (groundFollower != null) return followGround();
        if (groundSearch != null) return Input.EMPTY;
        if (player.onGround()) {
            finishLanding();
            return Input.EMPTY;
        }
        if (!player.isFallFlying()) {
            fail("Gliding ended before reaching the verified landing.");
            return Input.EMPTY;
        }
        steerFlight();
        return Input.EMPTY;
    }

    private void steerFlight() {
        Vec3 feet = position();
        Vec3 velocity = player.getDeltaMovement();
        double speed = horizontalSpeed(velocity);
        slowTicks = speed < MIN_SPEED ? slowTicks + 1 : 0;
        if (slowTicks > 10) {
            seekDiversion(ElytraReservePolicy.Reason.NO_CONFIRMED_EXIT, null);
            return;
        }

        Vec3 from = flightPath.get(leg - 1);
        Vec3 to = flightPath.get(leg);
        double progress = horizontalProgress(feet, from, to);
        double distance = horizontalDistance(feet, to);
        if (leg < flightPath.size() - 1 && progress >= .78 && distance < Math.max(2.8, speed * 3.4)) {
            from = to;
            to = flightPath.get(++leg);
            progress = horizontalProgress(feet, from, to);
        }
        double legDistance = horizontalDistance(from, to);
        Vec3 nearest = from.lerp(to, clamp(progress, 0, 1));
        boolean finalLeg = leg == flightPath.size() - 1;
        boolean nearTouchdown = finalLeg && nearTouchdown(feet, landing, speed);
        double tolerance = Math.max(3.6, speed * 3.0);
        if (legDistance < 1.0E-5 || horizontalDistance(feet, nearest) > tolerance || feet.y < nearest.y - 2.1
            || finalLeg && progress > 1.20 && feet.y > landing.y + 2
            || !landingAvailable(landingSurface, flightPath.get(flightPath.size() - 2))) {
            seekDiversion(ElytraReservePolicy.Reason.NO_CONFIRMED_EXIT, null);
            return;
        }
        if (!maintainBudget(feet, velocity, nearTouchdown)) return;

        double ahead = clamp(progress + Math.max(3.5, speed * 5) / legDistance, 0, 1);
        Vec3 target = from.lerp(to, ahead);
        Vec3 predicted = feet.add(velocity.scale(2.5));
        if (nearTouchdown && predicted.y < landing.y) {
            predicted = new Vec3(predicted.x, landing.y, predicted.z);
        }
        if (!clearSweep(feet, predicted, flightHalfWidth + .16)
            || !level.noEntityCollision(player, sweptBox(feet, predicted, flightHalfWidth + .16))
            || !nearTouchdown && !clearSweep(feet, target, .46)) {
            seekDiversion(ElytraReservePolicy.Reason.NO_CONFIRMED_EXIT, null);
            return;
        }

        var look = ElytraFlightPhysics.steer(feet, velocity, from, to, target, nearest, nearTouchdown,
            new ElytraFlightPhysics.Look(player.getYRot(), player.getXRot()));
        if (nearTouchdown) pendingBoost = null;
        if (pendingBoost != null && !useVerifiedBoost(feet, velocity, look)) return;
        applyLook(look);
        if (diversion != null) detail = "Landing after " + diversionReason(diversion) + ".";
        else detail = nearTouchdown ? "Landing on verified ground." : "Gliding leg " + leg + "/" + (flightPath.size() - 1) + ".";
    }

    private boolean maintainBudget(Vec3 feet, Vec3 velocity, boolean nearTouchdown) {
        if (diversion == null) {
            long now = level.getGameTime();
            if (crossesVoid && !nearTouchdown && now - lastVoidCheck >= 6) {
                lastVoidCheck = now;
                if (remainingApproach(feet, velocity) == null) {
                    seekDiversion(ElytraReservePolicy.Reason.NO_CONFIRMED_EXIT, null);
                    return false;
                }
            }
            var decision = assessFlight(feet, velocity);
            if (decision.action() == CONTINUE) return true;
            seekDiversion(decision.reason(), remainingApproach(feet, velocity));
            return false;
        }
        long now = level.getGameTime();
        if (pendingBoost != null || nearTouchdown || now - lastDiversionCheck < 4) return true;
        int remaining = (int) Math.max(1, predictedTouchdown - now);
        int needed = Math.max(remaining, estimateTicks(feet, landing, velocity));
        if (ElytraReservePolicy.canFinish(ElytraSupplies.read(player), cost(0, needed))) return true;
        lastDiversionCheck = now;
        List<Vec3> previousPath = flightPath;
        seekDiversion(ElytraReservePolicy.Reason.DURABILITY_RESERVE, remainingApproach(feet, velocity));
        return status == Status.MOVING && previousPath == flightPath;
    }

    private boolean useVerifiedBoost(Vec3 feet, Vec3 velocity, ElytraFlightPhysics.Look look) {
        var rocket = pendingBoost;
        pendingBoost = null;
        Vec3 start = flightPath.getFirst();
        int maximumTicks = 0;
        for (int duration = rocket.minimumBoostTicks(); duration <= rocket.maximumBoostTicks(); duration++) {
            int immediate = predictDirect(start, feet, velocity, landingSurface, duration, 0);
            int delayed = predictDirect(start, feet, velocity, landingSurface, duration, 2);
            if (immediate < 0 || delayed < 0) {
                maximumTicks = 0;
                break;
            }
            maximumTicks = Math.max(maximumTicks, Math.max(immediate, delayed));
        }
        if (maximumTicks > 0 && ElytraReservePolicy.canFinish(ElytraSupplies.read(player), cost(1, maximumTicks + 8))) {
            applyLook(look);
            if (rocket.use(player)) {
                minimumBoostUntil = level.getGameTime() + rocket.minimumBoostTicks();
                boostedUntil = level.getGameTime() + rocket.maximumBoostTicks() + 2;
                predictedTouchdown = level.getGameTime() + maximumTicks + 8;
                return true;
            }
        }
        rocketRetryAfter = level.getGameTime() + 10;
        seekDiversion(ElytraReservePolicy.Reason.NO_CONFIRMED_EXIT, null);
        return false;
    }

    private void applyLook(ElytraFlightPhysics.Look look) {
        player.setYRot((float) look.yaw());
        player.setXRot((float) look.pitch());
    }

    private static boolean nearTouchdown(Vec3 feet, Vec3 target, double speed) {
        return horizontalDistance(feet, target) <= Math.max(2.0, speed * 3.2) && feet.y <= target.y + 2;
    }
    private void finishLanding() {
        Node start = terrain.startAtPlayer();
        if (start == null || horizontalDistance(position(), landing) > MAX_LANDING_DRIFT) {
            fail("Landed outside the verified arrival area.");
            return;
        }
        if (diversion != null) {
            fail("Landed safely after " + diversionReason(diversion) + ".");
            return;
        }
        if (atGoal()) {
            arrive();
            return;
        }
        groundSearch = new IncrementalSearch(terrain, start, goal, finishConfig());
        walkingStart = start;
        detail = "Landed. Checking a short walking finish.";
    }

    private IncrementalSearch.Config finishConfig() {
        IncrementalSearch.Config search = options.search();
        return new IncrementalSearch.Config(search.diagonals(), search.jumping(), search.maxDrop(),
            Math.min(24, search.horizontalRange()), Math.min(1_500, search.maxVisited()), false, search.searchMode(), search.gapJumps());
    }

    private void advanceGroundPlan(int expansions, long budgetNanos) {
        IncrementalSearch.Result result = groundSearch.advance(expansions, budgetNanos);
        if (result.status() == IncrementalSearch.Status.SEARCHING) return;
        groundSearch = null;
        if (result.status() != IncrementalSearch.Status.FOUND) {
            fail("Landed safely. " + result.reason());
            return;
        }
        waypoints = combinedPath(result.path());
        groundFollower = result.path().isEmpty() ? new PathFollower(terrain, player, walkingStart, options.steerView())
            : new PathFollower(terrain, player, result.path(), options.steerView());
        detail = "Walking to the destination.";
    }

    private List<Vec3> combinedPath(List<Step> finish) {
        List<Vec3> combined = new ArrayList<>(flightPath);
        for (Step step : finish) {
            Node to = step.to();
            combined.add(new Vec3(to.centerX(), to.y(), to.centerZ()));
        }
        return List.copyOf(combined);
    }

    private Input followGround() {
        Input input = groundFollower.tick();
        switch (groundFollower.status()) {
            case ARRIVED -> arrive();
            case BLOCKED -> fail("Landed safely. " + groundFollower.message());
            case MOVING -> detail = "Walking · " + groundFollower.completedSteps() + "/" + groundFollower.totalSteps() + " moves.";
        }
        return status == Status.MOVING ? input : Input.EMPTY;
    }

    private boolean clearSweep(Vec3 from, Vec3 to, double margin) {
        var exclusions = Navigation.activeExclusions(options.avoidMobs());
        var fromPoint = new RouteExclusions.Point(from.x, from.y + .025, from.z);
        var toPoint = new RouteExclusions.Point(to.x, to.y + .025, to.z);
        if (exclusions.blockingSegment(fromPoint, toPoint,
            new RouteExclusions.Footprint(Math.max(margin, flightHalfWidth) * 2, flightHeight + .095)).isPresent()) return false;
        int pieces = Math.max(1, (int) Math.ceil(from.distanceTo(to) / 1.75));
        Vec3 previous = from;
        for (int i = 1; i <= pieces; i++) {
            Vec3 next = from.lerp(to, (double) i / pieces);
            if (!clearBox(sweptBox(previous, next, margin))) return false;
            previous = next;
        }
        return true;
    }

    private AABB sweptBox(Vec3 from, Vec3 to, double margin) {
        double radius = Math.max(margin, flightHalfWidth);
        return new AABB(Math.min(from.x, to.x) - radius, Math.min(from.y, to.y) + .025, Math.min(from.z, to.z) - radius,
            Math.max(from.x, to.x) + radius, Math.max(from.y, to.y) + flightHeight + .12, Math.max(from.z, to.z) + radius);
    }

    private boolean clearBox(AABB area) {
        if (area.minY < level.getMinY() || area.maxY > Node.MAX_Y16 / 16.0
            || !level.getWorldBorder().isWithinBounds(area.minX, area.minZ)
            || !level.getWorldBorder().isWithinBounds(area.maxX, area.maxZ)) return false;
        int minX = floor(area.minX), maxX = floor(area.maxX);
        int minY = floor(area.minY), maxY = Math.min(level.getMaxY(), floor(area.maxY));
        int minZ = floor(area.minZ), maxZ = floor(area.maxZ);
        for (int x = minX >> 4; x <= maxX >> 4; x++) {
            for (int z = minZ >> 4; z <= maxZ >> 4; z++) {
                if (!level.hasChunk(x, z)) return false;
            }
        }
        if (!level.noBlockCollision(player, area)) return false;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    var state = level.getBlockState(block.set(x, y, z));
                    if (state.isAir()) continue;
                    if (!state.getFluidState().isEmpty() || state.is(BlockTags.FIRE) || state.is(Blocks.COBWEB)
                        || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.END_PORTAL)
                        || state.is(Blocks.END_GATEWAY) || state.is(Blocks.SWEET_BERRY_BUSH)) return false;
                }
            }
        }
        return true;
    }

    private boolean atGoal() {
        int x = floor(player.getX());
        int z = floor(player.getZ());
        int y16 = (int) Math.round(player.getY() * 16);
        return player.onGround() && Node.validColumn(x) && Node.validColumn(z)
            && y16 >= Node.MIN_Y16 && y16 <= Node.MAX_Y16 && goal.matches(new Node(x, y16, z));
    }

    private Vec3 position() {
        return new Vec3(player.getX(), player.getY(), player.getZ());
    }

    private static Vec3 landingPosition(Node node) {
        return new Vec3(node.centerX(), node.y() + LANDING_GAP, node.centerZ());
    }

    private static double horizontalProgress(Vec3 position, Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return ((position.x - from.x) * dx + (position.z - from.z) * dz) / (dx * dx + dz * dz);
    }

    private static double horizontalDistance(Vec3 first, Vec3 second) {
        return Math.hypot(first.x - second.x, first.z - second.z);
    }

    private static double horizontalSpeed(Vec3 velocity) {
        return Math.hypot(velocity.x, velocity.z);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private void arrive() {
        status = Status.ARRIVED;
        detail = "Destination reached.";
    }

    private void fail(String message) {
        status = Status.FAILED;
        detail = message;
    }

    @Override public Status status() { return status; }
    @Override public Vec2 walkDirection() {
        return status == Status.MOVING && groundFollower != null ? groundFollower.walkDirection() : null;
    }
    @Override public String detail() { return detail; }
    @Override public List<Vec3> waypoints() { return waypoints; }
}
