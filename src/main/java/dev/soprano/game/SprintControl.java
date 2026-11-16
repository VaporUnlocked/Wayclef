package dev.soprano.game;

import dev.soprano.path.Move;
import dev.soprano.path.Step;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/** Requests vanilla sprint only while a level walking runway can absorb the resulting momentum. */
final class SprintControl {
    private static final double SPRINT_SPEED_SCALE = 1.3;
    private static final double STEERING_ALIGNMENT = .97;
    private static final int MAX_LOOKAHEAD_EDGES = 6;

    private SprintControl() { }

    static Input request(Input intent, Vec2 direction, List<Step> path, int active, MinecraftTerrain terrain, LocalPlayer player) {
        if (direction == null || active >= path.size() || !intent.forward() || intent.backward()
            || intent.shift() || intent.jump() || direction.y < .65f || !canUseSprint(player)) return intent;
        Step step = path.get(active);
        if (!levelWalk(step) || Math.abs(player.getY() - step.from().y()) > WalkingSweep.EPSILON) return intent;

        Vec2 heading = PathFollower.walkingDirection(step.to().x() - step.from().x(),
            step.to().z() - step.from().z(), player.getYRot());
        if (direction.x * heading.x + direction.y * heading.y < STEERING_ALIGNMENT) return intent;
        Vec3 feet = player.position();
        Vec3 velocity = player.getDeltaMovement();
        double drag = terrain.groundDrag();
        double friction = drag / .91;
        double speed = player.getSpeed() * (player.isSprinting() ? 1 : SPRINT_SPEED_SCALE);
        double acceleration = speed * (.216 / (friction * friction * friction));
        double runway = stoppingRunway(Math.hypot(velocity.x, velocity.z), acceleration, drag, player.isSprinting());
        if (!hasRunway(path, active, feet, runway, edge -> terrain.closedDoorOnStep(edge) == null
            && terrain.canTraverse(edge.from(), edge.to(), Move.WALK) && terrain.isClearOfEntities(edge.to()))) return intent;

        double yaw = Math.toRadians(player.getYRot());
        double dx = direction.x * Math.cos(yaw) - direction.y * Math.sin(yaw);
        double dz = direction.x * Math.sin(yaw) + direction.y * Math.cos(yaw);
        Vec3 predicted = feet.add(velocity.x + dx * acceleration, 0, velocity.z + dz * acceleration);
        return terrain.canRoundWalk(feet, predicted) ? withSprint(intent) : intent;
    }

    private static boolean canUseSprint(LocalPlayer player) {
        return player.onGround() && !player.isPassenger() && !player.isUsingItem() && !player.isMovingSlowly()
            && !player.isInWater() && !player.isInLava() && !player.onClimbable() && !player.getAbilities().flying
            && (player.getFoodData().hasEnoughFood() || player.getAbilities().mayfly);
    }

    static double stoppingRunway(double speed, double acceleration, double drag, boolean sprinting) {
        double projectedCoast = (speed + acceleration) / (1 - drag);
        return Math.max(sprinting ? .75 : 1.05, projectedCoast + .18);
    }

    static boolean hasRunway(List<Step> path, int active, Vec3 feet, double required, Predicate<Step> verified) {
        Step first = path.get(active);
        if (!levelWalk(first)) return false;
        int dx = first.to().x() - first.from().x(), dz = first.to().z() - first.from().z();
        double length = Math.hypot(dx, dz);
        if (length < 1e-5) return false;
        double remaining = Math.clamp(((first.to().centerX() - feet.x) * dx
            + (first.to().centerZ() - feet.z) * dz) / length, 0, length);

        for (int i = active; i < Math.min(path.size(), active + MAX_LOOKAHEAD_EDGES); i++) {
            Step edge = path.get(i);
            if (!levelWalk(edge) || edge.to().x() - edge.from().x() != dx || edge.to().z() - edge.from().z() != dz
                || i != active && !path.get(i - 1).to().equals(edge.from()) || !verified.test(edge)) return false;
            if (i != active) remaining += length;
            if (remaining >= required) return true;
        }
        return false;
    }

    private static boolean levelWalk(Step step) {
        return step.move() == Move.WALK && step.from().y16() == step.to().y16();
    }

    static Input withSprint(Input intent) {
        return new Input(intent.forward(), intent.backward(), intent.left(), intent.right(), intent.jump(), intent.shift(), true);
    }
}
