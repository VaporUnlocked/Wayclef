package dev.soprano.task;

import dev.soprano.game.Navigation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Feeds compatible loaded adults and waits for a new baby to confirm each pair. */
final class BreedJob {
    private enum Phase { FIND_PAIR, WALK_FIRST, FEED_FIRST, WALK_SECOND, FEED_SECOND, WAIT_FOR_BABY }

    private static final int RANGE = 24;
    private static final double MAX_MATE_DISTANCE_SQR = 64;
    private final ClientLevel level;
    private final LocalPlayer player;
    private final MultiPlayerGameMode mode;
    private final Navigation.Options walk;
    private final Object owner;
    private final Predicate<ItemStack> equip;
    private final EntityType<?> species;
    private final int limit;
    private final Set<UUID> used = new HashSet<>();
    private Phase phase = Phase.FIND_PAIR;
    private int phaseTick, fedBefore, bred;
    private Animal first, second;
    private AABB nursery;
    private Set<UUID> existingBabies = Set.of();
    private ItemStack food = ItemStack.EMPTY;
    private GatherMobApproach approach;
    private String status = "Looking for a pair of adults.";

    BreedJob(ClientLevel level, LocalPlayer player, MultiPlayerGameMode mode, JobSpec spec, Object owner,
             Navigation.Options walk, Predicate<ItemStack> equip) {
        this.level = level;
        this.player = player;
        this.mode = mode;
        this.owner = owner;
        this.walk = walk;
        this.equip = equip;
        species = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(spec.ids().getFirst()))
            .orElseThrow(() -> new IllegalArgumentException("Unknown animal " + spec.ids().getFirst() + "."));
        limit = spec.limit();
    }

    String status() { return status; }

    boolean tick() {
        switch (phase) {
            case FIND_PAIR -> findPair();
            case WALK_FIRST -> feed(first, Phase.FEED_FIRST);
            case WALK_SECOND -> feed(second, Phase.FEED_SECOND);
            case FEED_FIRST -> feedConfirmed(Phase.WALK_SECOND);
            case FEED_SECOND -> feedConfirmed(Phase.WAIT_FOR_BABY);
            case WAIT_FOR_BABY -> {
                if (level.getEntitiesOfClass(Animal.class, nursery, animal -> animal.getType() == species
                    && animal.isAlive() && animal.isBaby() && !existingBabies.contains(animal.getUUID())).isEmpty()) {
                    if (elapsed() > 240) throw new IllegalStateException("The two adults were fed, but no offspring arrived.");
                    return false;
                }
                bred++;
                used.add(first.getUUID());
                used.add(second.getUUID());
                enter(Phase.FIND_PAIR, "Confirmed " + bred + " / " + limit + " offspring.");
            }
        }
        return bred >= limit;
    }

    private void findPair() {
        var area = player.getBoundingBox().inflate(RANGE);
        var adults = level.getEntitiesOfClass(Animal.class, area, this::adult).stream()
            .sorted(Comparator.comparingDouble(animal -> animal.distanceToSqr(player))).limit(32).toList();
        if (adults.size() < 2) throw new IllegalStateException("Two loaded adults are needed. Confirmed " + bred + " / " + limit + " offspring.");
        for (int i = 0; i < adults.size(); i++) {
            Animal a = adults.get(i);
            for (int j = i + 1; j < adults.size(); j++) {
                Animal b = adults.get(j);
                if (a.distanceToSqr(b) > MAX_MATE_DISTANCE_SQR || !a.hasLineOfSight(b)) continue;
                ItemStack feed = feedFor(a, b);
                if (feed.isEmpty()) continue;
                first = a;
                second = b;
                food = feed;
                nursery = a.getBoundingBox().minmax(b.getBoundingBox()).inflate(6);
                existingBabies = level.getEntitiesOfClass(Animal.class, nursery, animal -> animal.getType() == species && animal.isBaby())
                    .stream().map(Animal::getUUID).collect(Collectors.toSet());
                approach = new GatherMobApproach(level, player, first, owner, walk);
                enter(Phase.WALK_FIRST, "Walking to the first adult.");
                return;
            }
        }
        throw new IllegalStateException("No nearby pair can see each other and accept two matching feed items.");
    }

    private boolean adult(Animal animal) {
        return animal.getType() == species && animal.isAlive() && !animal.isBaby() && !used.contains(animal.getUUID())
            && !animal.isInLove() && WorldJob.loaded(level, animal.blockPosition());
    }

    private ItemStack feedFor(Animal first, Animal second) {
        List<ItemStack> held = player.getInventory().getNonEquipmentItems();
        for (ItemStack stack : held) {
            if (!stack.isEmpty() && first.isFood(stack) && second.isFood(stack) && count(stack) >= 2) return stack.copyWithCount(1);
        }
        return ItemStack.EMPTY;
    }

    private void feed(Animal animal, Phase awaiting) {
        if (!adult(animal) || elapsed() > 600) throw new IllegalStateException("An adult is no longer available for feeding.");
        var hit = approach.tick();
        if (hit == null) return;
        if (count(food) < 1) throw new IllegalStateException("The breeding food is no longer carried.");
        if (!equip.test(food)) return;
        fedBefore = count(food);
        mode.interact(player, animal, hit, InteractionHand.MAIN_HAND);
        enter(awaiting, "Waiting for the animal to accept the food.");
    }

    private void feedConfirmed(Phase next) {
        if (count(food) >= fedBefore) {
            if (elapsed() >= 120) throw new IllegalStateException("The adult did not accept the breeding food.");
            return;
        }
        if (next == Phase.WALK_SECOND) {
            approach = new GatherMobApproach(level, player, second, owner, walk);
            enter(next, "Walking to the second adult.");
        } else enter(next, "Waiting for the pair's offspring.");
    }

    private int count(ItemStack wanted) {
        return player.getInventory().getNonEquipmentItems().stream()
            .filter(stack -> ItemStack.isSameItemSameComponents(wanted, stack)).mapToInt(ItemStack::getCount).sum();
    }

    private int elapsed() { return player.tickCount - phaseTick; }

    private void enter(Phase next, String message) {
        phase = next;
        phaseTick = player.tickCount;
        status = message;
    }

    void suspend() { if (approach != null) approach.suspend(); }
}
