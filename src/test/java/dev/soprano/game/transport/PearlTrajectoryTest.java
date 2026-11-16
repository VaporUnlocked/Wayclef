package dev.soprano.game.transport;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Covers the continuous aiming solution against the discrete Java throwable motion. */
public final class PearlTrajectoryTest {
    public static void main(String[] args) {
        Vec3 first = PearlTrajectory.advance(new Vec3(1.5, 0, 0));
        near(first.x, 1.485, "horizontal drag");
        near(first.y, -.0297, "gravity precedes drag");
        hits(new Vec3(.5, 65.52, .5), Vec3.ZERO, new Vec3(8.5, 66.5, .5));
        hits(new Vec3(-2.5, 65.52, -3.5), new Vec3(.09, 0, -.04), new Vec3(14.5, 63.5, -7.5));
        if (!PearlTrajectory.aimVectors(Vec3.ZERO, Vec3.ZERO, new Vec3(250, 10, 0)).isEmpty()) {
            throw new AssertionError("An out-of-range target got a launch angle.");
        }
        System.out.println("Pearl trajectories passed.");
    }

    private static void hits(Vec3 origin, Vec3 inherited, Vec3 target) {
        var vectors = PearlTrajectory.aimVectors(origin, inherited, target);
        if (vectors.isEmpty()) throw new AssertionError("No angle to " + target);
        var aperture = new AABB(target, target).inflate(.0001);
        for (Vec3 aim : vectors) {
            Vec3 velocity = aim.scale(1.5).add(inherited);
            Vec3 position = origin;
            boolean crossed = false;
            for (int tick = 0; tick < 72; tick++) {
                velocity = PearlTrajectory.advance(velocity);
                Vec3 next = position.add(velocity);
                if (aperture.clip(position, next).isPresent()) {
                    crossed = true;
                    break;
                }
                position = next;
            }
            if (!crossed) throw new AssertionError("Projectile missed solved target " + target + ".");
        }
    }

    private static void near(double actual, double expected, String message) {
        if (Math.abs(actual - expected) > .00001) throw new AssertionError(message + ": " + actual);
    }
}
