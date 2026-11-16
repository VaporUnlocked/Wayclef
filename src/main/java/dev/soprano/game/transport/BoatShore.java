package dev.soprano.game.transport;

import dev.soprano.game.MinecraftTerrain;
import dev.soprano.path.Goal;
import dev.soprano.path.Node;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.vehicle.DismountHelper;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.Vec3;

final class BoatShore {
    private static final int[] HEADINGS = {0, -45, 45, -90, 90, -135, 135, 180};

    record Exit(Vec3 feet, Node start, float yaw) { }

    private final ClientLevel level;
    private final LocalPlayer player;
    private final AbstractBoat boat;
    private final MinecraftTerrain ground;
    private final Goal goal;

    BoatShore(ClientLevel level, LocalPlayer player, AbstractBoat boat, MinecraftTerrain ground, Goal goal) {
        this.level = level;
        this.player = player;
        this.boat = boat;
        this.ground = ground;
        this.goal = goal;
    }

    Exit at(double boatX, double boatZ, boolean checkYaw) {
        float aim = (float) Math.toDegrees(Math.atan2(boatX - goal.x() - .5, goal.z() + .5 - boatZ));
        Exit best = null;
        double nearest = Double.POSITIVE_INFINITY;
        for (int turn : HEADINGS) {
            float yaw = Mth.wrapDegrees(aim + turn);
            if (checkYaw && Math.abs(Mth.wrapDegrees(yaw - boat.getYRot())) > 95) continue;
            Exit candidate = facing(boatX, boatZ, yaw);
            if (candidate == null) continue;
            double distance = Math.hypot(candidate.feet().x - goal.x() - .5, candidate.feet().z - goal.z() - .5);
            if (distance < nearest) {
                best = candidate;
                nearest = distance;
            }
        }
        return best;
    }

    /** Predict the standing dismount without invoking the boat method that changes the passenger's pose. */
    Exit facing(double boatX, double boatZ, float yaw) {
        double radians = yaw * (float) (Math.PI / 180.0);
        double dx = -Mth.sin(radians), dz = Mth.cos(radians);
        double escape = (boat.getBbWidth() * Mth.SQRT_OF_TWO + player.getBbWidth() + 1.0E-5) * .5;
        double factor = escape / Math.max(Math.abs(dx), Math.abs(dz));
        double x = boatX + dx * factor, z = boatZ + dz * factor;
        BlockPos upper = BlockPos.containing(x, boat.getBoundingBox().maxY, z);
        BlockPos lower = upper.below();
        if (!level.getChunkSource().hasChunk(upper.getX() >> 4, upper.getZ() >> 4) || level.isWaterAt(lower)) return null;
        Exit exit = floor(x, z, upper, yaw);
        return exit != null ? exit : floor(x, z, lower, yaw);
    }

    private Exit floor(double x, double z, BlockPos block, float yaw) {
        double height = level.getBlockFloorHeight(block);
        if (!DismountHelper.isBlockFloorValid(height)) return null;
        Vec3 feet = new Vec3(x, block.getY() + height, z);
        if (!DismountHelper.canDismountTo(level, feet, player, Pose.STANDING)) return null;
        int y16 = (int) Math.round(feet.y * 16);
        for (Node node : ground.floors(block.getX(), block.getZ(), y16, y16)) {
            if (ground.canApproachFrom(x, feet.y, z, node)) return new Exit(feet, node, yaw);
        }
        return null;
    }
}
