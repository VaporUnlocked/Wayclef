package dev.soprano.game;

import dev.soprano.SopranoClient;
import dev.soprano.host.ClientEvents;
import dev.soprano.map.PortalCoordinates;
import dev.soprano.path.Node;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/** Measures locally thrown Eyes of Ender using ordinary server-tracked entity updates. */
public final class StrongholdTracker implements ClientEvents.Listener {
    private static final StrongholdTracker INSTANCE = new StrongholdTracker();
    private static final int SPAWN_WINDOW = 60;
    private static final int SAMPLE_WINDOW = 26;
    private static final double MIN_BASELINE = 16;
    private static final double MIN_ANGLE = Math.sin(Math.toRadians(2));

    record Sight(Vec3 origin, Vec3 direction) { }
    private record Throw(Vec3 origin, Set<Integer> existing, int deadline) { }
    private record Flight(Throw launch, EyeOfEnder eye, Vec3 from, int tick) { }

    private ClientLevel level;
    private Sight first;
    private Throw waiting;
    private Flight flight;
    private BlockPos estimate;
    private int tick;

    private StrongholdTracker() { }
    public static StrongholdTracker get() { return INSTANCE; }
    public void initialize() { ClientEvents.subscribe(this); }

    public String start() {
        var current = Minecraft.getInstance().level;
        if (current == null || !current.dimension().identifier().toString().equals(PortalCoordinates.OVERWORLD)) {
            throw new IllegalStateException("Start an Eye of Ender survey in the Overworld.");
        }
        disconnected();
        level = current;
        return "Throw an Eye of Ender into open space to record the first bearing.";
    }

    public BlockPos estimate() {
        if (estimate == null || Minecraft.getInstance().level != level) {
            throw new IllegalStateException("Record two usable Eye of Ender bearings first.");
        }
        return estimate;
    }

    @Override public void disconnected() {
        level = null;
        first = null;
        waiting = null;
        flight = null;
        estimate = null;
        tick = 0;
    }

    @Override
    public void packetSent(Connection connection, Packet<?> packet) {
        Minecraft mc = Minecraft.getInstance();
        if (level == null || estimate != null || waiting != null || flight != null || !mc.isSameThread()
            || mc.level != level || mc.player == null || !(packet instanceof ServerboundUseItemPacket use)
            || !mc.player.getItemInHand(use.hand()).is(Items.ENDER_EYE)) return;
        Vec3 origin = mc.player.position();
        if (first != null && horizontalDistanceSquared(origin, first.origin()) < MIN_BASELINE * MIN_BASELINE) {
            SopranoClient.feedback("Move sideways at least 16 blocks from the first throw to get another bearing.");
            return;
        }
        var existing = new HashSet<Integer>();
        for (EyeOfEnder eye : level.getEntitiesOfClass(EyeOfEnder.class, new AABB(origin, origin).inflate(12))) {
            existing.add(eye.getId());
        }
        waiting = new Throw(origin, Set.copyOf(existing), tick + SPAWN_WINDOW);
    }

    @Override
    public void tickPost() {
        if (level == null) return;
        if (level != Minecraft.getInstance().level) { disconnected(); return; }
        tick++;
        if (waiting != null) findEye();
        if (flight != null) measure();
    }

    private void findEye() {
        var launch = waiting;
        if (tick > launch.deadline()) {
            waiting = null;
            SopranoClient.feedback("No Eye of Ender flight was observed. Try another throw in open space.");
            return;
        }
        var candidates = level.getEntitiesOfClass(EyeOfEnder.class, new AABB(launch.origin(), launch.origin()).inflate(4),
            eye -> !launch.existing().contains(eye.getId()) && eye.isAlive());
        if (candidates.size() != 1) return;
        EyeOfEnder eye = candidates.getFirst();
        flight = new Flight(launch, eye, eye.position(), tick);
        waiting = null;
    }

    private void measure() {
        Flight sample = flight;
        if (!sample.eye().isAlive() || tick - sample.tick() > SAMPLE_WINDOW) {
            flight = null;
            SopranoClient.feedback("The Eye of Ender did not travel far enough for a bearing.");
            return;
        }
        Vec3 moved = sample.eye().position().subtract(sample.from());
        if (tick - sample.tick() < 7 || moved.horizontalDistanceSqr() < 1.5 * 1.5) return;
        flight = null;
        var sight = new Sight(sample.launch().origin(), new Vec3(moved.x, 0, moved.z).normalize());
        if (first == null) {
            first = sight;
            SopranoClient.feedback("First bearing recorded. Move about 64 blocks sideways, then throw another Eye of Ender.");
            return;
        }
        estimate = intersection(first, sight);
        if (estimate == null) {
            SopranoClient.feedback("The bearings do not meet reliably. Move farther sideways and take another reading.");
            return;
        }
        SopranoClient.feedback("Estimated stronghold column " + estimate.getX() + ", " + estimate.getZ()
            + ". Use .wayclef stronghold go or .wayclef stronghold mark.");
    }

    static BlockPos intersection(Sight a, Sight b) {
        if (horizontalDistanceSquared(a.origin(), b.origin()) < MIN_BASELINE * MIN_BASELINE) return null;
        double denominator = cross(a.direction(), b.direction());
        if (Math.abs(denominator) < MIN_ANGLE) return null;
        Vec3 delta = b.origin().subtract(a.origin());
        double alongA = cross(delta, b.direction()) / denominator;
        double alongB = cross(delta, a.direction()) / denominator;
        if (alongA < -2 || alongB < -2) return null;
        Vec3 result = a.origin().add(a.direction().scale(alongA));
        if (!Double.isFinite(result.x) || !Double.isFinite(result.z)
            || result.x < Node.MIN_COLUMN || result.x > Node.MAX_COLUMN
            || result.z < Node.MIN_COLUMN || result.z > Node.MAX_COLUMN) return null;
        return new BlockPos((int) Math.round(result.x), (int) Math.floor(b.origin().y), (int) Math.round(result.z));
    }

    private static double cross(Vec3 a, Vec3 b) { return a.x * b.z - a.z * b.x; }
    private static double horizontalDistanceSquared(Vec3 a, Vec3 b) { return a.subtract(b).horizontalDistanceSqr(); }
}
