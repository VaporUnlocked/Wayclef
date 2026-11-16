package dev.soprano.game.transport;

import java.util.List;
import net.minecraft.world.phys.Vec3;

public final class GlideSearchTest {
    private static final Vec3 START = new Vec3(0, 100, 0);
    private static final Vec3 VELOCITY = new Vec3(0, -.10, 1);

    public static void main(String[] args) {
        Vec3 landing = new Vec3(0, 70, 80);
        var direct = new GlideSearch(START, VELOCITY, landing, 128, 2_048, (from, to, margin) -> true);
        require(run(direct).equals(List.of(START, landing)), "direct descent");

        GlideSearch.Clearance obstruction = (from, to, margin) -> {
            for (int i = 0; i <= 40; i++) {
                Vec3 point = from.lerp(to, i / 40.0);
                if (Math.abs(point.x) < 10 + margin && point.z > 22 && point.z < 36) return false;
            }
            return true;
        };
        var detour = new GlideSearch(START, VELOCITY, landing, 128, 2_048, obstruction);
        var path = run(detour);
        require(path.size() > 2, "verified detour");
        require(path.getLast().equals(landing), "land at the planned endpoint");
        for (int i = 1; i < path.size(); i++) {
            require(path.get(i).y < path.get(i - 1).y, "every leg descends");
            require(obstruction.clear(path.get(i - 1), path.get(i), .62), "every leg clears the obstruction");
        }

        require(run(new GlideSearch(START, VELOCITY, new Vec3(0, 104, 50), 128, 2_048,
            (from, to, margin) -> true)).isEmpty(), "no artificial climb");
        require(run(new GlideSearch(START, VELOCITY, landing, 32, 2_048,
            (from, to, margin) -> true)).isEmpty(), "bound range");
        require(run(new GlideSearch(START, VELOCITY, landing, 128, 2_048,
            (from, to, margin) -> false)).isEmpty(), "do not cross unknown air");

        var nodeLimited = new GlideSearch(START, VELOCITY, landing, 128, 1, obstruction);
        require(run(nodeLimited).isEmpty() && nodeLimited.visited() == 1, "respect configured visit budget");

        var gradual = new GlideSearch(START, VELOCITY, landing, 128, 2_048, obstruction);
        gradual.advance(8, 0);
        require(gradual.visited() == 0, "respect zero work budget");
        gradual.advance(1, 100_000_000);
        require(!gradual.complete() && gradual.visited() == 1, "incremental search");
        require(run(gradual).equals(path), "incremental and full search agree");
        System.out.println("Elytra search checks passed.");
    }

    private static List<Vec3> run(GlideSearch search) {
        for (int i = 0; i < 300 && !search.complete(); i++) search.advance(16, 100_000_000);
        require(search.complete(), "bounded search completes");
        return search.path();
    }

    private static void require(boolean value, String name) {
        if (!value) throw new AssertionError(name);
    }
}
