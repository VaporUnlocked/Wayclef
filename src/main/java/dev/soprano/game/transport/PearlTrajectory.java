package dev.soprano.game.transport;

import dev.soprano.game.MinecraftTerrain;
import dev.soprano.path.Node;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.List;

/** Predicts the vanilla pearl's air trajectory without moving the player or their camera. */
public final class PearlTrajectory {
    public record Aim(float yaw, float pitch, Vec3 impact, int ticks) { }

    record Impact(Vec3 previous, Vec3 position, BlockHitResult block, int ticks) { }
    private record Angles(float yaw, float pitch) { }
    private record Offset(float yaw, float pitch) { }

    private static final int MAX_TICKS = 72;
    private static final float DRAG = .99F;
    private static final double GRAVITY = .03;
    private static final double SPEED = 1.5;
    private static final double[] DISTANCE = new double[MAX_TICKS + 1];
    private static final double[] FALL = new double[MAX_TICKS + 1];
    private static final List<Offset> SPREAD = List.of(new Offset(1.1F, 0), new Offset(-1.1F, 0),
        new Offset(0, 1.1F), new Offset(0, -1.1F));
    private static final int[] ESCAPE_YAW_OFFSETS = {0, -17, 17, -35, 35, -55, 55};
    private static final int[] ESCAPE_PITCHES = {-11, -4, -20, 4, -28, 12};

    static {
        double motion = 1;
        for (int tick = 1; tick <= MAX_TICKS; tick++) {
            motion *= DRAG;
            DISTANCE[tick] = DISTANCE[tick - 1] + motion;
            FALL[tick] = FALL[tick - 1] - GRAVITY * DISTANCE[tick];
        }
    }

    private PearlTrajectory() { }

    /** Checks a shot from the player's real pose, including inherited movement. */
    public static Aim aimAtGateway(ClientLevel level, LocalPlayer player, BlockPos gateway) {
        return gatewayAim(level, player, launchPoint(player), inheritedMotion(player), gateway);
    }

    /** Checks a future standing shot from a dry staging point. */
    public static Aim aimAtGateway(ClientLevel level, LocalPlayer player, Vec3 standingFeet, BlockPos gateway) {
        return gatewayAim(level, player, standingFeet.add(0, player.getEyeHeight(Pose.STANDING) - .1, 0), Vec3.ZERO, gateway);
    }

    private static Aim gatewayAim(ClientLevel level, LocalPlayer player, Vec3 origin, Vec3 inherited, BlockPos gateway) {
        if (!loaded(level, gateway) || !level.getBlockState(gateway).is(Blocks.END_GATEWAY)) return null;
        Vec3 center = Vec3.atCenterOf(gateway);
        if (origin.distanceToSqr(center) > 24 * 24 || origin.distanceToSqr(center) < 1) return null;
        AABB aperture = new AABB(gateway).deflate(.075);
        for (Vec3 motion : aimVectors(origin, inherited, center)) {
            Angles angles = angles(motion);
            Impact nominal = trace(level, player, origin, inherited, angles.yaw(), angles.pitch(), aperture);
            if (nominal == null || nominal.block() != null) continue;
            boolean clear = true;
            for (Offset offset : SPREAD) {
                Impact spread = trace(level, player, origin, inherited, angles.yaw() + offset.yaw(),
                    angles.pitch() + offset.pitch(), aperture);
                if (spread == null || spread.block() != null) {
                    clear = false;
                    break;
                }
            }
            if (clear) return new Aim(angles.yaw(), angles.pitch(), nominal.position(), nominal.ticks());
        }
        return null;
    }

    /** Finds a dry supported landing in the first checked direction away from a threat. */
    public static Aim aimAway(ClientLevel level, LocalPlayer player, Vec3 away, boolean avoidMobs) {
        Vec3 origin = launchPoint(player);
        Vec3 inherited = inheritedMotion(player);
        Vec3 preferred = away.horizontalDistanceSqr() < .01 ? player.getLookAngle() : away;
        float yaw = (float) Math.toDegrees(Math.atan2(-preferred.x, preferred.z));
        var terrain = new MinecraftTerrain(level, player, avoidMobs);
        terrain.beginSearchSlice();
        try {
            for (int offset : ESCAPE_YAW_OFFSETS) {
                for (int pitch : ESCAPE_PITCHES) {
                    Impact impact = impact(level, player, origin, inherited, yaw + offset, pitch);
                    if (!landable(level, terrain, origin, impact)) continue;
                    boolean clear = true;
                    for (Offset spread : SPREAD) {
                        Impact sample = impact(level, player, origin, inherited,
                            yaw + offset + spread.yaw(), pitch + spread.pitch());
                        if (!landable(level, terrain, origin, sample)) {
                            clear = false;
                            break;
                        }
                    }
                    if (clear) return new Aim(yaw + offset, pitch, impact.previous(), impact.ticks());
                }
            }
        } finally {
            terrain.endSearchSlice();
        }
        return null;
    }

    private static boolean landable(ClientLevel level, MinecraftTerrain terrain, Vec3 origin, Impact impact) {
        if (impact == null || impact.block() == null || impact.block().getDirection() != Direction.UP
            || !level.getFluidState(impact.block().getBlockPos()).isEmpty()) return false;
        Vec3 at = impact.previous();
        if (Math.hypot(at.x - origin.x, at.z - origin.z) < 7 || at.y - origin.y < -5) return false;
        BlockPos block = BlockPos.containing(at);
        int low = Math.max(Node.MIN_Y16, (int) Math.floor((at.y - 2) * 16));
        int high = Math.min(Node.MAX_Y16, (int) Math.ceil((at.y + .05) * 16));
        return terrain.clearBodyAt(at.x, at.y, at.z) && terrain.floors(block.getX(), block.getZ(), low, high).stream()
            .anyMatch(landing -> terrain.canFinish(landing) && terrain.clearBodyAt(at.x, landing.y(), at.z));
    }

    static Vec3 launchPoint(LocalPlayer player) { return player.getEyePosition().subtract(0, .1, 0); }

    static Vec3 inheritedMotion(LocalPlayer player) {
        Vec3 motion = player.getKnownMovement();
        return player.onGround() ? new Vec3(motion.x, 0, motion.z) : motion;
    }

    static Impact impact(ClientLevel level, LocalPlayer player, Vec3 origin, Vec3 inherited, float yaw, float pitch) {
        return trace(level, player, origin, inherited, yaw, pitch, null);
    }

    private static Impact trace(ClientLevel level, LocalPlayer player, Vec3 origin, Vec3 inherited,
                                float yaw, float pitch, AABB aperture) {
        Vec3 previous = origin;
        Vec3 velocity = Vec3.directionFromRotation(pitch, yaw).scale(SPEED).add(inherited);
        for (int tick = 1; tick <= MAX_TICKS; tick++) {
            velocity = advance(velocity);
            Vec3 next = previous.add(velocity);
            if (!loaded(level, previous, next)) return null;
            BlockHitResult block = level.clip(new ClipContext(previous, next, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.ANY, CollisionContext.empty()));
            boolean blocked = block.getType() != HitResult.Type.MISS;
            Vec3 stop = blocked ? block.getLocation() : next;
            AABB span = new AABB(previous, stop).inflate(.3);
            for (var entity : level.getEntities(player, span, entity -> entity.canBeHitByProjectile()
                && !player.isPassengerOfSameVehicle(entity))) {
                AABB body = entity.getBoundingBox().inflate(.3);
                if (body.contains(previous) || body.clip(previous, stop).isPresent()) return null;
            }
            if (blocked) return new Impact(previous, stop, block, tick);
            if (aperture != null && (aperture.contains(previous) || aperture.contains(next) || aperture.clip(previous, next).isPresent())) {
                return new Impact(previous, aperture.clip(previous, next).orElse(next), null, tick);
            }
            previous = next;
        }
        return null;
    }

    private static boolean loaded(ClientLevel level, Vec3 a, Vec3 b) {
        return level.getWorldBorder().isWithinBounds(a.x, a.z) && level.getWorldBorder().isWithinBounds(b.x, b.z)
            && loaded(level, BlockPos.containing(a)) && loaded(level, BlockPos.containing(b))
            && level.getChunkSource().hasChunk(Mth.floor(a.x) >> 4, Mth.floor(b.z) >> 4)
            && level.getChunkSource().hasChunk(Mth.floor(b.x) >> 4, Mth.floor(a.z) >> 4);
    }

    private static boolean loaded(ClientLevel level, BlockPos block) {
        return !level.isOutsideBuildHeight(block) && level.getChunkSource().hasChunk(block.getX() >> 4, block.getZ() >> 4);
    }

    static Vec3 advance(Vec3 velocity) { return velocity.subtract(0, GRAVITY, 0).scale(DRAG); }

    static List<Vec3> aimVectors(Vec3 origin, Vec3 inherited, Vec3 target) {
        List<Vec3> aims = new ArrayList<>(2);
        double previous = Double.POSITIVE_INFINITY;
        for (int tick = 1; tick <= MAX_TICKS; tick++) {
            double delta = required(origin, inherited, target, tick, 1).length() - SPEED;
            if (Math.signum(delta) != Math.signum(previous)) {
                double start = 0, end = 1;
                for (int refinement = 0; refinement < 16; refinement++) {
                    double fraction = (start + end) / 2;
                    double middle = required(origin, inherited, target, tick, fraction).length() - SPEED;
                    if (Math.signum(middle) == Math.signum(previous)) start = fraction;
                    else end = fraction;
                }
                aims.add(required(origin, inherited, target, tick, (start + end) / 2).normalize());
            }
            previous = delta;
        }
        return aims;
    }

    private static Vec3 required(Vec3 origin, Vec3 inherited, Vec3 target, int tick, double fraction) {
        double distance = Mth.lerp(fraction, DISTANCE[tick - 1], DISTANCE[tick]);
        double fall = Mth.lerp(fraction, FALL[tick - 1], FALL[tick]);
        return target.subtract(origin).subtract(0, fall, 0).scale(1 / distance).subtract(inherited);
    }

    private static Angles angles(Vec3 vector) {
        float yaw = (float) Math.toDegrees(Math.atan2(-vector.x, vector.z));
        float pitch = (float) Math.toDegrees(Math.atan2(-vector.y, vector.horizontalDistance()));
        return new Angles(yaw, pitch);
    }
}
