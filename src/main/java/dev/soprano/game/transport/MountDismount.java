package dev.soprano.game.transport;

import dev.soprano.game.MinecraftTerrain;
import dev.soprano.path.Node;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.vehicle.DismountHelper;
import net.minecraft.world.phys.Vec3;

final class MountDismount {
    record Exit(Vec3 feet, Node start) { }

    private final ClientLevel level;
    private final LocalPlayer player;
    private final AbstractHorse mount;
    private final MinecraftTerrain ground;

    MountDismount(ClientLevel level, LocalPlayer player, AbstractHorse mount, MinecraftTerrain ground) {
        this.level = level;
        this.player = player;
        this.mount = mount;
        this.ground = ground;
    }

    /** Check the actual side selected by the mount before allowing a normal sneak dismount. */
    Exit at(Vec3 position, float yaw) {
        float side = player.getMainArm() == HumanoidArm.RIGHT ? 90 : -90;
        Vec3 dominant = beside(position, yaw + side);
        Vec3 opposite = beside(position, yaw - side);
        if (!loaded(dominant) || !loaded(opposite)) return null;
        Vec3 first = floor(dominant);
        Vec3 feet = first != null ? first : floor(opposite);
        if (feet == null) return null;
        BlockPos block = BlockPos.containing(feet);
        int y16 = (int) Math.round(feet.y * 16);
        for (Node node : ground.floors(block.getX(), block.getZ(), y16, y16)) {
            if (ground.canApproachFrom(feet.x, feet.y, feet.z, node)) return new Exit(feet, node);
        }
        return null;
    }

    private Vec3 beside(Vec3 position, float yaw) {
        double radians = yaw * (float) (Math.PI / 180.0);
        double dx = -Mth.sin(radians), dz = Mth.cos(radians);
        double clearance = (mount.getBbWidth() + player.getBbWidth() + 1.0E-5) * .5;
        double factor = clearance / Math.max(Math.abs(dx), Math.abs(dz));
        return position.add(dx * factor, 0, dz * factor);
    }

    private boolean loaded(Vec3 position) {
        var block = BlockPos.containing(position);
        return level.hasChunk(block.getX() >> 4, block.getZ() >> 4);
    }

    private Vec3 floor(Vec3 position) {
        BlockPos base = BlockPos.containing(position);
        double top = position.y + mount.getBbHeight() + .75;
        for (var pose : player.getDismountPoses()) {
            for (BlockPos block = base; block.getY() < top; block = block.above()) {
                double height = level.getBlockFloorHeight(block);
                if (block.getY() + height > top) break;
                if (!DismountHelper.isBlockFloorValid(height)) continue;
                Vec3 feet = new Vec3(position.x, block.getY() + height, position.z);
                if (DismountHelper.canDismountTo(level, feet, player, pose)) return feet;
            }
        }
        return null;
    }
}
