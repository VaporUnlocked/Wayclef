package dev.soprano.game.transport;

import net.minecraft.world.phys.Vec3;

/** Client-side forecast only. The game remains responsible for movement and collision. */
final class ElytraFlightPhysics {
    record Look(double yaw, double pitch) { }

    private ElytraFlightPhysics() { }

    static Look steer(Vec3 feet, Vec3 velocity, Vec3 from, Vec3 to, Vec3 target, Vec3 nearest,
                      boolean landing, Look current) {
        Vec3 lead = feet.add(velocity.scale(1.7));
        double dx = target.x - lead.x;
        double dz = target.z - lead.z;
        if (landing || Math.hypot(dx, dz) < .5) {
            dx = to.x - from.x;
            dz = to.z - from.z;
        }
        double desiredYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double yawDelta = Math.IEEEremainder(desiredYaw - current.yaw(), 360);
        double slope = (to.y - from.y) / Math.hypot(to.x - from.x, to.z - from.z);
        double verticalSpeed = Math.clamp(slope * velocity.horizontalDistance() + (nearest.y - feet.y) * .14, -.88, -.045);
        double desiredPitch = Math.clamp(7 + (velocity.y - verticalSpeed) * 92, -27, 46);
        if (landing) desiredPitch = Math.clamp(desiredPitch, -17, 25);
        double pitchDelta = desiredPitch - current.pitch();
        return new Look(current.yaw() + Math.clamp(yawDelta, -14, 14), current.pitch() + Math.clamp(pitchDelta, -9, 9));
    }

    static Vec3 velocity(Vec3 velocity, Look look, double gravity, boolean boosted) {
        double yaw = Math.toRadians(look.yaw());
        double pitch = Math.toRadians(look.pitch());
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        double cosPitch = Math.cos(pitch);
        double sinPitch = Math.sin(pitch);
        if (boosted) {
            velocity = velocity.scale(.5).add(forwardX * cosPitch * .85, -sinPitch * .85, forwardZ * cosPitch * .85);
        }
        double speed = velocity.horizontalDistance();
        double lift = cosPitch * cosPitch;
        double vertical = velocity.y + gravity * (-1 + lift * .75);
        double descentThrust = Math.max(0, -vertical * .1 * lift);
        double climbThrust = Math.max(0, -speed * sinPitch * .04);
        double horizontalThrust = descentThrust - climbThrust;
        return new Vec3((velocity.x * .9 + forwardX * (speed * .1 + horizontalThrust * .9)) * .99,
            (vertical + descentThrust + climbThrust * 3.2) * .98,
            (velocity.z * .9 + forwardZ * (speed * .1 + horizontalThrust * .9)) * .99);
    }
}
