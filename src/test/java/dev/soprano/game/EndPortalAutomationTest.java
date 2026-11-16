package dev.soprano.game;

import dev.soprano.game.StrongholdTracker.Sight;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;

public final class EndPortalAutomationTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var center = new BlockPos(15, 60, 16);
        var positions = new HashMap<BlockPos, BlockState>();
        for (var socket : EndPortalRing.sockets(center)) {
            positions.put(socket.at(), Blocks.END_PORTAL_FRAME.defaultBlockState().setValue(EndPortalFrameBlock.FACING, socket.facing()));
        }
        var ring = EndPortalRing.find(center.east(2), positions::get);
        check(ring.center().equals(center) && ring.sockets().size() == 12, "Any rim block locates the complete ring");
        check(!ring.clearOfOpening(Vec3.atCenterOf(center), .35), "Never use a socket from inside the opening");
        check(ring.clearOfOpening(Vec3.atCenterOf(center.east(3)), .35), "The outer walkway is eligible");
        var damaged = center.north(2);
        positions.put(damaged, positions.get(damaged).setValue(EndPortalFrameBlock.FACING, Direction.NORTH));
        check(!ring.matches(positions::get), "A backwards socket invalidates the ring");
        positions.remove(damaged);
        check(!ring.matches(positions::get), "An unloaded socket invalidates the ring");

        Vec3 stronghold = new Vec3(120, 0, -110);
        Sight first = sight(new Vec3(10, 70, 10), stronghold);
        Sight second = sight(new Vec3(40, 77, 90), stronghold);
        check(StrongholdTracker.intersection(first, second).equals(new BlockPos(120, 77, -110)), "Two forward bearings find the estimate");
        check(StrongholdTracker.intersection(first, new Sight(second.origin(), first.direction())) == null,
            "Nearly parallel bearings do not generate a distant estimate");
        check(StrongholdTracker.intersection(first, new Sight(second.origin(), second.direction().reverse())) == null,
            "An intersection behind a throw is rejected");
        System.out.println("Soprano End portal ring and stronghold bearing checks passed.");
    }

    private static Sight sight(Vec3 origin, Vec3 target) {
        return new Sight(origin, new Vec3(target.x - origin.x, 0, target.z - origin.z).normalize());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
