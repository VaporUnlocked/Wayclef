package dev.soprano.task;

import dev.soprano.inventory.StorageKnowledge;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.ChestBlock;

import java.util.function.Predicate;

/** Chooses an accessible block of one storage inventory. */
final class GatherStorage {
    private record Candidate(BlockPos at, int amount, double distance) { }

    private GatherStorage() { }

    static JobSpec findKnown(ClientLevel level, LocalPlayer player, String item, int amount,
                             Predicate<String> rejected, Predicate<BlockPos> occupied) {
        return find(level, player, item, amount, rejected, key -> false, occupied, false);
    }

    static JobSpec findUnknown(ClientLevel level, LocalPlayer player, String item, int amount,
                               Predicate<String> rejected, Predicate<String> visited, Predicate<BlockPos> occupied) {
        return find(level, player, item, amount, rejected, visited, occupied, true);
    }

    private static JobSpec find(ClientLevel level, LocalPlayer player, String item, int amount,
                                Predicate<String> rejected, Predicate<String> visited, Predicate<BlockPos> occupied, boolean unknown) {
        Candidate nearest = null;
        for (var location : StorageKnowledge.locations(level)) {
            var counts = StorageKnowledge.counts(level, location);
            if (unknown != (counts == null) || visited.test(location.key()) || occupied.test(location.first())
                || rejected.test(key(item, location.key()))) continue;
            int available = counts == null ? amount : Math.min(amount, counts.getOrDefault(item, 0));
            if (available == 0) continue;
            nearest = closer(nearest, level, player, location, location.first(), available);
            if (location.second() != null) {
                nearest = closer(nearest, level, player, location, location.second(), available);
            }
        }
        return nearest == null ? null : JobSpec.parse("take " + item + " " + nearest.amount(), null, nearest.at(), null);
    }

    private static Candidate closer(Candidate nearest, ClientLevel level, LocalPlayer player,
                                    StorageKnowledge.Location location, BlockPos at, int amount) {
        double distance = player.position().distanceToSqr(at.getX() + .5, at.getY() + .5, at.getZ() + .5);
        if (nearest != null && (distance > nearest.distance() || distance == nearest.distance() && at.compareTo(nearest.at()) >= 0)) {
            return nearest;
        }
        if (!level.getWorldBorder().isWithinBounds(at) || !level.mayInteract(player, at)
            || !location.equals(StorageKnowledge.locationAt(level, at))) return nearest;
        if (location.block() instanceof ChestBlock && ChestBlock.isChestBlockedAt(level, at)) return nearest;
        return new Candidate(at, amount, distance);
    }

    static boolean known(ClientLevel level, BlockPos at) {
        var location = StorageKnowledge.locationAt(level, at);
        return location != null && StorageKnowledge.counts(level, location) != null;
    }

    static String containerKey(ClientLevel level, BlockPos at) {
        var location = StorageKnowledge.locationAt(level, at);
        return location == null ? "storage:" + at.asLong() : location.key();
    }

    static String key(ClientLevel level, String item, BlockPos at) { return key(item, containerKey(level, at)); }

    private static String key(String item, String container) { return container + ":" + item; }
}
