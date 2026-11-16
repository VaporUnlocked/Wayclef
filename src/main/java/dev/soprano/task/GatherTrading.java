package dev.soprano.task;

import dev.soprano.inventory.InventoryJobs;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.entity.monster.piglin.PiglinArmPose;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** Plans trades using offers received from the active server's merchant menus. */
final class GatherTrading {
    private static final int MAX_SCOUTS = 6;

    record Opportunity(JobSpec action, String key, Map<Item, Integer> payments) { }

    private final ClientLevel level;
    private final LocalPlayer player;
    private final Map<UUID, MerchantOffers> known = new HashMap<>();
    private final Set<UUID> inspected = new HashSet<>();

    GatherTrading(ClientLevel level, LocalPlayer player) {
        this.level = level;
        this.player = player;
    }

    List<Opportunity> opportunities(Item result, int deficit, boolean canScout, Predicate<String> excluded) {
        String item = InventoryJobs.itemId(result);
        var merchants = level.getEntitiesOfClass(AbstractVillager.class, GatherHunt.loadedMobBounds(level, player), merchant ->
            merchant.isAlive() && !merchant.isBaby() && level.getWorldBorder().isWithinBounds(merchant.blockPosition())
                && (canScout || known.containsKey(merchant.getUUID())));
        var nearby = merchants.stream().sorted(Comparator.comparingDouble(merchant -> player.distanceToSqr(merchant))).toList();
        var offers = new ArrayList<Opportunity>();
        for (AbstractVillager merchant : nearby) {
            UUID id = merchant.getUUID();
            List<MerchantOffer> knownOffers = known.getOrDefault(id, new MerchantOffers());
            JobSpec action = JobSpec.parse("trade " + item + " " + deficit + " " + id, null, null, null);
            for (MerchantOffer offer : knownOffers) {
                if (!produces(offer, result)) continue;
                Map<Item, Integer> payment = payments(offer);
                String key = "trade:" + id + ":" + item + ":" + payment.entrySet().stream()
                    .map(e -> InventoryJobs.itemId(e.getKey()) + "=" + e.getValue()).sorted().reduce((a, b) -> a + "," + b).orElse("");
                if (!payment.isEmpty() && !excluded.test(key)) offers.add(new Opportunity(action, key, payment));
            }
        }
        offers.sort(Comparator.comparingInt(o -> missing(o.payments())));
        if (canScout && inspected.size() < MAX_SCOUTS) {
            for (AbstractVillager merchant : nearby) {
                UUID id = merchant.getUUID();
                String key = "merchant-scout:" + id;
                if (inspected.contains(id) || excluded.test(key)) continue;
                JobSpec action = JobSpec.parse("trade " + item + " " + deficit + " " + id, null, null, null);
                offers.add(new Opportunity(action, key, Map.of()));
                break;
            }
        }
        if (PiglinAi.isWearingSafeArmor(player) && GatherSources.canBarter(result)) {
            level.getEntitiesOfClass(Piglin.class, GatherHunt.loadedMobBounds(level, player),
                    piglin -> canOffer(piglin) && level.getWorldBorder().isWithinBounds(piglin.blockPosition())
                        && !excluded.test(barterKey(piglin, item)))
                .stream().min(Comparator.comparingDouble(piglin -> player.distanceToSqr(piglin)))
                .ifPresent(piglin -> offers.add(new Opportunity(
                    JobSpec.parse("barter " + item + " 1 " + piglin.getUUID(), null, null, null),
                    barterKey(piglin, item), Map.of(Items.GOLD_INGOT, 1))));
        }
        return List.copyOf(offers);
    }

    void opening(UUID merchant) { inspected.add(merchant); }

    void observe(UUID merchant, MerchantOffers offers) { known.put(merchant, offers.copy()); }

    static boolean canOffer(Piglin piglin) {
        return piglin.isAlive() && piglin.isAdult() && !piglin.isConverting() && !piglin.isAggressive() && !admiring(piglin);
    }

    static boolean admiring(Piglin piglin) {
        return piglin.getOffhandItem().is(Items.GOLD_INGOT) || piglin.getArmPose() == PiglinArmPose.ADMIRING_ITEM;
    }

    private static String barterKey(Piglin piglin, String item) { return "barter:" + piglin.getUUID() + ":" + item; }

    private int missing(Map<Item, Integer> payment) {
        return payment.entrySet().stream().mapToInt(e -> Math.max(0,
            e.getValue() - InventoryJobs.count(player, InventoryJobs.itemId(e.getKey())))).sum();
    }

    private static Map<Item, Integer> payments(MerchantOffer offer) {
        var first = offer.getItemCostA();
        var second = offer.getItemCostB().orElse(null);
        if (!ordinary(first) || second != null && !ordinary(second)) return Map.of();
        var required = new LinkedHashMap<Item, Integer>();
        required.put(first.item().value(), offer.getCostA().getCount());
        if (second != null) required.merge(second.item().value(), second.count(), Integer::sum);
        return java.util.Collections.unmodifiableMap(required);
    }

    private static boolean ordinary(ItemCost cost) { return cost.test(cost.item().value().getDefaultInstance()); }

    static boolean produces(MerchantOffer offer, Item result) {
        return !offer.isOutOfStock() && offer.getResult().is(result) && !offer.getCostA().is(result) && !offer.getCostB().is(result);
    }

    static int payable(LocalPlayer player, MerchantOffers offers, Item result) {
        int chosen = -1, minimum = Integer.MAX_VALUE;
        for (int i = 0; i < offers.size(); i++) {
            MerchantOffer offer = offers.get(i);
            if (!produces(offer, result) || !canPay(player, offer)) continue;
            int total = offer.getCostA().getCount() + offer.getCostB().getCount();
            if (total < minimum) {
                chosen = i;
                minimum = total;
            }
        }
        return chosen;
    }

    static boolean canPay(LocalPlayer player, MerchantOffer offer) {
        ItemCost first = offer.getItemCostA(), second = offer.getItemCostB().orElse(null);
        int firstOnly = 0, secondOnly = 0, either = 0;
        for (var stack : player.getInventory().getNonEquipmentItems()) {
            boolean matchesFirst = first.test(stack);
            boolean matchesSecond = second != null && second.test(stack);
            if (matchesFirst && matchesSecond) either += stack.getCount();
            else if (matchesFirst) firstOnly += stack.getCount();
            else if (matchesSecond) secondOnly += stack.getCount();
        }
        return Math.max(0, offer.getCostA().getCount() - firstOnly)
            + Math.max(0, offer.getCostB().getCount() - secondOnly) <= either;
    }
}
