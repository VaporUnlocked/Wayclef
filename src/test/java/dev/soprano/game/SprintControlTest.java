package dev.soprano.game;

import dev.soprano.path.Move;
import dev.soprano.path.Node;
import dev.soprano.path.Step;
import java.util.List;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

public final class SprintControlTest {
    public static void main(String[] args) {
        Node a = new Node(0, 16, 0), b = new Node(1, 16, 0), c = new Node(2, 16, 0);
        Step first = new Step(a, b, Move.WALK), next = new Step(b, c, Move.WALK);
        Vec3 start = new Vec3(a.centerX(), a.y(), a.centerZ());
        Vec3 nearEnd = new Vec3(1.9, 1, .5);
        check(SprintControl.hasRunway(List.of(first, next), 0, start, 1.05, edge -> true),
            "straight verified continuation provides a sprint runway");
        check(!SprintControl.hasRunway(List.of(first, next), 0, start, 1.05, edge -> edge.equals(first)),
            "a newly blocked runway cannot start sprinting");
        check(!SprintControl.hasRunway(List.of(first, next), 1, nearEnd, .75, edge -> true),
            "sprint releases while normal walking can approach the destination");
        check(!SprintControl.hasRunway(List.of(first, new Step(b, new Node(1, 16, 1), Move.WALK)), 0, start, 1.05, edge -> true),
            "a corner is not counted as straight sprint runway");
        check(!SprintControl.hasRunway(List.of(first, new Step(b, new Node(2, 8, 0), Move.WALK)), 0, start, 1.05, edge -> true),
            "sprint releases before a verified stair or slab descent");
        check(!SprintControl.hasRunway(List.of(first, new Step(b, new Node(3, 16, 0), Move.JUMP)), 0, start, 1.05, edge -> true),
            "sprint does not alter the verified launch physics of a parkour step");
        check(SprintControl.stoppingRunway(.2, .13, .88, true) > SprintControl.stoppingRunway(.2, .13, .55, true),
            "slippery surfaces require more runway before a turn or stop");
        Input diagonal = new Input(true, false, true, false, false, false, false);
        Input sprint = SprintControl.withSprint(diagonal);
        check(sprint.forward() && sprint.left() && sprint.sprint() && !sprint.jump() && !sprint.shift()
            && !sprint.backward() && !sprint.right(), "the only changed key flag is vanilla sprint");
        System.out.println("Soprano sprint policy passed 8 checks.");
    }

    private static void check(boolean accepted, String message) {
        if (!accepted) throw new AssertionError(message);
    }
}
