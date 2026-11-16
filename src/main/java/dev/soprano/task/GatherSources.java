package dev.soprano.task;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.soprano.game.ToolDurability;
import dev.soprano.map.MapVolume;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.advancements.predicates.TagPredicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.RegistryOps;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Shearable;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.phys.AABB;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/** Finds loaded item sources and interprets the loot definitions available to this client. */
final class GatherSources {
    private static final int RADIUS = 24;
    private static final int MAX_CANDIDATES = 24;
    private static final int HUNT_RADIUS = 5;
    private static final int MAX_LOOT_DEPTH = 12;
    private static final int LOWER_LAYER_COST = 2_304;
    private static final int SIDE_ACCESS_COST = 65_536;
    private static final int BURIED_COST = 131_072;
    private static final int MISSING_TOOL_COST = 262_144;

    record Source(JobSpec step, double cost, String key, List<WorldJob.Target> prepared, List<Item> tools) {
        Source(JobSpec step, double cost, String key) { this(step, cost, key, List.of(), List.of()); }
    }

    private record BlockSource(BlockPos position, BlockState state, double cost, String key, boolean batchable, List<Item> tools) { }
    private record FishingContext(Holder<Biome> biome, Boolean openWater) { }
    private record LootContext(BlockState state, ItemStack tool, boolean playerKill, Mob mob, LocalPlayer player,
                               FishingContext fishing) {
        LootContext(BlockState state, ItemStack tool, boolean playerKill, Mob mob, LocalPlayer player) {
            this(state, tool, playerKill, mob, player, null);
        }
    }
    private record LootRule(Predicate<Holder<Item>> item, Condition test, boolean cooksOnFire) { }

    private enum Decision {
        YES, POSSIBLE, NO, UNKNOWN;

        boolean mayDrop() { return this == YES || this == POSSIBLE; }

        Decision and(Decision other) {
            if (this == NO || other == NO) return NO;
            if (this == UNKNOWN || other == UNKNOWN) return UNKNOWN;
            return this == YES && other == YES ? YES : POSSIBLE;
        }

        Decision or(Decision other) {
            if (this == YES || other == YES) return YES;
            if (this == UNKNOWN || other == UNKNOWN) return UNKNOWN;
            return this == NO && other == NO ? NO : POSSIBLE;
        }

        Decision invert() {
            return switch (this) {
                case YES -> NO;
                case NO -> YES;
                default -> this;
            };
        }
    }

    private interface Condition {
        Condition ALWAYS = context -> Decision.YES;
        Condition NEVER = context -> Decision.NO;
        Condition UNKNOWN = context -> Decision.UNKNOWN;

        Decision evaluate(LootContext context);

        default Condition and(Condition other) { return context -> evaluate(context).and(other.evaluate(context)); }
        default Condition or(Condition other) { return context -> evaluate(context).or(other.evaluate(context)); }
        default Condition invert() { return context -> evaluate(context).invert(); }
    }

    private static final class LootSources {
        private static final Map<Block, List<LootRule>> BLOCKS;
        private static final Map<EntityType<?>, List<LootRule>> MOBS;
        private static final Map<EntityType<?>, List<LootRule>> SHEARING;

        static {
            LootReader reader = new LootReader();
            BLOCKS = index(BuiltInRegistries.BLOCK, Block::getLootTable, reader);
            MOBS = index(BuiltInRegistries.ENTITY_TYPE, EntityType::getDefaultLootTable, reader);
            Map<EntityType<?>, List<LootRule>> shearing = new HashMap<>();
            for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
                Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
                List<LootRule> loot = reader.table(Identifier.fromNamespaceAndPath(id.getNamespace(), "shearing/" + id.getPath()));
                if (!loot.isEmpty()) shearing.put(type, loot);
            }
            SHEARING = Map.copyOf(shearing);
        }

        private static <T> Map<T, List<LootRule>> index(Registry<T> registry, Function<T, Optional<ResourceKey<LootTable>>> key,
                                                     LootReader reader) {
            Map<T, List<LootRule>> sources = new HashMap<>();
            for (T type : registry) {
                var table = key.apply(type);
                if (table.isEmpty()) continue;
                List<LootRule> rules = reader.table(table.get().identifier());
                if (!rules.isEmpty()) sources.put(type, rules);
            }
            return Map.copyOf(sources);
        }
    }

    private GatherSources() { }

    private static final class FishingLoot {
        static final List<LootRule> RULES = new LootReader().table(BuiltInLootTables.FISHING.identifier());
    }

    private static final class BarterLoot {
        static final List<LootRule> RULES = new LootReader().table(BuiltInLootTables.PIGLIN_BARTERING.identifier());
    }

    static boolean canBarter(Item item) {
        if (item == Items.GOLD_INGOT) return false;
        var context = new LootContext(null, ItemStack.EMPTY, false, null, null);
        for (LootRule rule : BarterLoot.RULES) {
            if (rule.item().test(item.builtInRegistryHolder()) && produces(List.of(rule), context)) return true;
        }
        return false;
    }

    static boolean canFish(Item item, ItemStack rod, Holder<Biome> biome, Boolean openWater) {
        var context = new LootContext(null, rod, false, null, null, new FishingContext(biome, openWater));
        for (LootRule rule : FishingLoot.RULES) {
            try {
                if (rule.item().test(item.builtInRegistryHolder()) && rule.test().evaluate(context).mayDrop()) return true;
            } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException unsupportedCondition) {
                // Loot conditions using information exclusive to the server stay unknown.
            }
        }
        return false;
    }

    static List<Source> find(ClientLevel level, LocalPlayer player, BlockPos center, Item item, int deficit, int lookahead,
                             Predicate<String> excluded) {
        if (deficit < 1) return List.of();
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        if (id == null) return List.of();
        MapVolume.Box area = WorldJob.nearbyArea(center, RADIUS, level.getMinY(), level.getMaxY());
        AABB search = GatherHunt.loadedMobBounds(level, player);
        List<Source> choices = new ArrayList<>();
        findDrops(level, player, item, id, search, excluded, choices);
        if (choices.isEmpty()) {
            findBlocks(level, player, area, center.getY(), item, id, Math.min(deficit, lookahead), excluded, choices);
            findMobs(level, player, item, id, search, deficit, excluded, JobSpec.HuntLoot.ANY, choices);
            if (GatherHunt.shearingToolSlot(player) >= 0) {
                findMobs(level, player, item, id, search, deficit, excluded, JobSpec.HuntLoot.SHEAR, choices);
            }
        }
        return choices.stream().sorted(Comparator.comparingDouble(Source::cost).thenComparing(Source::key)).toList();
    }

    static Source findCooked(ClientLevel level, LocalPlayer player, Item raw, Item result, int deficit,
                             Predicate<String> excluded) {
        if (!GatherHunt.hasCookingWeapon(player)) return null;
        var choices = new ArrayList<Source>();
        findMobs(level, player, raw, BuiltInRegistries.ITEM.getKey(result), GatherHunt.loadedMobBounds(level, player),
            deficit, excluded, JobSpec.HuntLoot.COOKED, choices);
        return choices.isEmpty() ? null : choices.getFirst();
    }

    private static void findDrops(ClientLevel level, LocalPlayer player, Item item, Identifier id, AABB search,
                                  Predicate<String> excluded, List<Source> choices) {
        level.getEntitiesOfClass(ItemEntity.class, search, entity -> entity.isAlive() && entity.getItem().is(item)
                && level.getWorldBorder().isWithinBounds(entity.blockPosition())
                && !excluded.test("drop:" + id + ":" + entity.getUUID())).stream()
            .sorted(Comparator.comparingDouble(entity -> entity.distanceToSqr(player))).limit(MAX_CANDIDATES)
            .forEach(entity -> {
                // Collection picks up the full stack and checks the inventory delta afterward.
                MapVolume.Box box = WorldJob.nearbyArea(entity.blockPosition(), 1, level.getMinY(), level.getMaxY());
                JobSpec step = JobSpec.parse("collect " + id + " " + entity.getItem().getCount(), box, null, null);
                choices.add(new Source(step, entity.distanceToSqr(player), "drop:" + id + ":" + entity.getUUID()));
            });
    }

    private static <T> Map<T, List<LootRule>> matchingLoot(Map<T, List<LootRule>> sources, Item item) {
        Map<T, List<LootRule>> matching = new HashMap<>();
        for (var entry : sources.entrySet()) {
            var rules = entry.getValue().stream().filter(rule -> rule.item().test(item.builtInRegistryHolder())).toList();
            if (!rules.isEmpty()) matching.put(entry.getKey(), rules);
        }
        return matching;
    }

    private static void findBlocks(ClientLevel level, LocalPlayer player, MapVolume.Box area, int surfaceY, Item item, Identifier id,
                                   int lookahead, Predicate<String> excluded, List<Source> choices) {
        var blocks = matchingLoot(LootSources.BLOCKS, item);
        if (blocks.isEmpty()) return;
        Predicate<BlockState> matches = state -> blocks.containsKey(state.getBlock()) && !(state.getBlock() instanceof CraftingTableBlock);
        int reserve = ToolDurability.configuredReserve();
        Comparator<BlockSource> ranking = Comparator.comparingDouble(BlockSource::cost).thenComparing(BlockSource::position);
        int candidates = Math.max(MAX_CANDIDATES, lookahead);
        PriorityQueue<BlockSource> best = new PriorityQueue<>(candidates, ranking.reversed());
        Map<BlockState, List<Item>> compatibleTools = new HashMap<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        var body = player.position();
        for (int cx = area.minX() >> 4; cx <= area.maxX() >> 4; cx++) {
            int minX = Math.max(area.minX(), cx << 4), maxX = Math.min(area.maxX(), (cx << 4) + 15);
            for (int cz = area.minZ() >> 4; cz <= area.maxZ() >> 4; cz++) {
                int minZ = Math.max(area.minZ(), cz << 4), maxZ = Math.min(area.maxZ(), (cz << 4) + 15);
                var chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                for (int cy = area.maxY() >> 4; cy >= area.minY() >> 4; cy--) {
                    var section = chunk.getSections()[cy - (level.getMinY() >> 4)];
                    if (section.hasOnlyAir() || !section.maybeHas(matches)) continue;
                    int minY = Math.max(area.minY(), cy << 4), maxY = Math.min(area.maxY(), (cy << 4) + 15);
                    for (int y = maxY; y >= minY; y--) {
                        for (int x = minX; x <= maxX; x++) {
                            for (int z = minZ; z <= maxZ; z++) {
                                BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                                var rules = blocks.get(state.getBlock());
                                if (rules == null || state.getBlock() instanceof CraftingTableBlock) continue;
                                cursor.set(x, y, z);
                                double distance = cursor.distToCenterSqr(body);
                                if (best.size() == candidates && distance > best.element().cost()) continue;
                                if (!WorldJob.breakable(level, cursor, state)) continue;
                                if (!level.mayInteract(player, cursor) || JobQueue.supportsPlayer(level, player, cursor)) continue;
                                int access = accessCost(level, cursor);
                                if (access < 0) continue;
                                boolean batchable = access == 0 && state.getBlock().asItem() == item;
                                if (access == 0) access = LOWER_LAYER_COST * Math.max(0, surfaceY - y);
                                double cost = distance + access;
                                if (best.size() == candidates && cost > best.element().cost()) continue;
                                String key = "block:" + id + ":" + cursor.asLong();
                                if (excluded.test(key)) continue;
                                int slot = bestToolForDrop(level, player, cursor, state, reserve, rules);
                                List<Item> tools = List.of();
                                if (slot < 0) {
                                    tools = compatibleTools.computeIfAbsent(state, target -> GatherMiningTools
                                        .compatible(level, player, cursor, target, reserve).stream()
                                        .filter(tool -> produces(rules, new LootContext(target, tool.getDefaultInstance(), false, null, player)))
                                        .toList());
                                    if (tools.isEmpty()) continue;
                                    cost += MISSING_TOOL_COST;
                                }
                                var source = new BlockSource(cursor.immutable(), state, cost, key, batchable, tools);
                                if (best.size() == candidates && ranking.compare(source, best.element()) >= 0) continue;
                                if (best.size() == candidates) best.remove();
                                best.add(source);
                            }
                        }
                    }
                }
            }
        }
        if (best.isEmpty()) return;
        List<BlockSource> ordered = best.stream().sorted(ranking).toList();
        BlockSource first = ordered.getFirst();
        BlockPos at = first.position();
        var block = first.state().getBlock();
        List<BlockSource> batch = first.batchable() && lookahead > 1 ? ordered.stream().filter(source -> source.batchable()
            && source.state().getBlock() == block && source.tools().equals(first.tools())
            && source.position().getY() == at.getY()).limit(lookahead).toList() : List.of(first);
        int minX = at.getX(), maxX = minX, minZ = at.getZ(), maxZ = minZ;
        List<WorldJob.Target> prepared = new ArrayList<>(batch.size());
        for (BlockSource source : batch) {
            BlockPos pos = source.position();
            minX = Math.min(minX, pos.getX());
            maxX = Math.max(maxX, pos.getX());
            minZ = Math.min(minZ, pos.getZ());
            maxZ = Math.max(maxZ, pos.getZ());
            prepared.add(new WorldJob.Target(pos, source.state(), null, null));
        }
        var box = new MapVolume.Box(minX, at.getY(), minZ, maxX, at.getY(), maxZ);
        JobSpec step = JobSpec.parse("mine " + BuiltInRegistries.BLOCK.getKey(block) + " " + batch.size(), box, null, null);
        choices.add(new Source(step, first.cost(), first.key(), List.copyOf(prepared), first.tools()));
    }

    static int bestToolForDrop(ClientLevel level, LocalPlayer player, BlockPos pos, BlockState state, int reserve, Item item) {
        var rules = LootSources.BLOCKS.getOrDefault(state.getBlock(), List.of()).stream()
            .filter(rule -> rule.item().test(item.builtInRegistryHolder())).toList();
        return rules.isEmpty() ? -1 : bestToolForDrop(level, player, pos, state, reserve, rules);
    }

    private static int bestToolForDrop(ClientLevel level, LocalPlayer player, BlockPos pos, BlockState state,
                                       int reserve, List<LootRule> rules) {
        return JobTools.bestInventorySlot(player, level, pos, state, reserve,
            stack -> produces(rules, new LootContext(state, stack, false, null, player)));
    }

    private static int accessCost(ClientLevel level, BlockPos at) {
        boolean exposedTop = false;
        boolean exposedSide = false;
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = at.relative(direction);
            if (!WorldJob.loaded(level, neighbor)) return -1;
            BlockState adjacent = level.getBlockState(neighbor);
            if (!adjacent.getFluidState().isEmpty()
                || direction == Direction.UP && adjacent.getBlock() instanceof FallingBlock) return -1;
            if (!adjacent.getCollisionShape(level, neighbor).isEmpty()) continue;
            if (direction == Direction.UP) exposedTop = true;
            else if (direction != Direction.DOWN) exposedSide = true;
        }
        if (exposedTop) return 0;
        return exposedSide ? SIDE_ACCESS_COST : BURIED_COST;
    }

    private static boolean produces(List<LootRule> rules, LootContext context) {
        for (LootRule rule : rules) {
            try {
                if (rule.test().evaluate(context).mayDrop()) return true;
            } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException unsupportedCondition) {
                // Resource packs can use conditions that cannot be evaluated on the client.
            }
        }
        return false;
    }

    private static void findMobs(ClientLevel level, LocalPlayer player, Item wanted, Identifier item, AABB search, int deficit,
                                 Predicate<String> excluded, JobSpec.HuntLoot mode, List<Source> choices) {
        boolean shear = mode == JobSpec.HuntLoot.SHEAR;
        var types = matchingLoot(shear ? LootSources.SHEARING : LootSources.MOBS, wanted);
        if (types.isEmpty()) return;
        ItemStack tool = shear ? player.getInventory().getItem(GatherHunt.shearingToolSlot(player)) : player.getMainHandItem();
        String prefix = shear ? "shear:" : "mob:";
        level.getEntitiesOfClass(Mob.class, search, mob -> types.containsKey(mob.getType())
                && (shear ? eligibleShearing(mob) : eligibleMob(mob))
                && mobLoot(types.get(mob.getType()), new LootContext(null, tool, !shear, mob, player), mode) != null
                && level.getWorldBorder().isWithinBounds(mob.blockPosition())
                && !excluded.test(prefix + item + ":" + mob.getUUID())).stream()
            .sorted(Comparator.comparingDouble(mob -> mob.distanceToSqr(player))).limit(MAX_CANDIDATES)
            .forEach(mob -> {
                String entity = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString();
                JobSpec.HuntLoot loot = mobLoot(types.get(mob.getType()), new LootContext(null, tool, !shear, mob, player), mode);
                String command = "hunt " + item + " " + entity + " " + Math.min(32_768, deficit) + " "
                    + HUNT_RADIUS + " " + mob.getUUID() + " " + loot.name();
                JobSpec step = JobSpec.parse(command, null, null, mob.blockPosition());
                double distance = mob.distanceToSqr(player);
                double cost = shear ? -1 / (1 + distance) : distance + 16;
                choices.add(new Source(step, cost, prefix + item + ":" + mob.getUUID()));
            });
    }

    private static JobSpec.HuntLoot mobLoot(List<LootRule> rules, LootContext context, JobSpec.HuntLoot mode) {
        if (mode == JobSpec.HuntLoot.SHEAR) return produces(rules, context) ? mode : null;
        if (mode == JobSpec.HuntLoot.COOKED) return produces(rules.stream().filter(LootRule::cooksOnFire).toList(), context) ? mode : null;
        if (produces(rules.stream().filter(rule -> !rule.cooksOnFire()).toList(), context)) return JobSpec.HuntLoot.ANY;
        return !context.mob().isOnFire() && produces(rules, context) ? JobSpec.HuntLoot.RAW : null;
    }

    static boolean eligibleShearing(Mob mob) {
        return mob.isAlive() && !mob.isBaby() && !mob.isPassenger() && !mob.isVehicle() && !mob.hasCustomName()
            && mob instanceof Shearable shearable && shearable.readyForShearing();
    }

    static boolean stillProduces(Mob mob, LocalPlayer player, Item wanted, JobSpec.HuntLoot mode) {
        boolean shear = mode == JobSpec.HuntLoot.SHEAR;
        if (shear ? !eligibleShearing(mob) : !eligibleMob(mob)) return false;
        if (mode == JobSpec.HuntLoot.COOKED) return true;
        if (mode == JobSpec.HuntLoot.RAW && mob.isOnFire()) return false;
        var table = (shear ? LootSources.SHEARING : LootSources.MOBS).get(mob.getType());
        if (table == null) return false;
        var context = new LootContext(null, shear ? Items.SHEARS.getDefaultInstance() : player.getMainHandItem(), !shear, mob, player);
        return produces(table.stream().filter(rule -> rule.item().test(wanted.builtInRegistryHolder())).toList(), context);
    }

    static boolean eligibleMob(Mob mob) {
        return mob.getType().getDefaultLootTable().isPresent() && mob.isAlive() && !mob.isBaby()
            && mob.isAttackable() && !mob.isInvulnerable() && !mob.hasCustomName()
            && !mob.isPassenger() && !mob.isVehicle() && !mob.isLeashed()
            && !(mob instanceof TamableAnimal pet && pet.isTame())
            && !(mob instanceof net.minecraft.world.entity.OwnableEntity owned && owned.getOwnerReference() != null);
    }

    private static final class LootReader {
        private final Map<String, Optional<JsonObject>> resources = new HashMap<>();
        private final Map<Identifier, List<LootRule>> tables = new HashMap<>();

        List<LootRule> table(Identifier id) {
            return tables.computeIfAbsent(id, key -> {
                List<LootRule> rules = new ArrayList<>();
                var json = read("loot_table", key);
                try {
                    if (json != null) addTable(json, Condition.ALWAYS, false, 0, rules);
                } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException unsupportedTable) {
                    return List.of();
                }
                return List.copyOf(rules);
            });
        }

        private void addTable(JsonObject json, Condition parent, boolean cooked, int depth, List<LootRule> rules) {
            if (depth > MAX_LOOT_DEPTH) return;
            boolean tableCooks = cooked || cooksOnFire(json);
            for (var element : listOf(json.get("pools"))) {
                if (!element.isJsonObject()) continue;
                var pool = element.getAsJsonObject();
                if (!positive(pool.get("rolls"))) continue;
                var poolTest = parent.and(holderCondition(pool, depth));
                boolean poolCooks = tableCooks || cooksOnFire(pool);
                for (var entry : listOf(pool.get("entries"))) addEntry(entry, poolTest, poolCooks, depth + 1, rules);
            }
        }

        private void addEntry(JsonElement raw, Condition parent, boolean parentCooks, int depth, List<LootRule> rules) {
            if (depth > MAX_LOOT_DEPTH || !raw.isJsonObject()) return;
            JsonObject entry = raw.getAsJsonObject();
            var test = parent.and(holderCondition(entry, depth));
            if (!positive(entry.get("weight"))) return;
            boolean cooked = parentCooks || cooksOnFire(entry);
            switch (type(entry)) {
                case "item" -> {
                    var item = id(entry.get("name"));
                    if (item == null) break;
                    var selector = registrySelector(entry.get("name"), Registries.ITEM);
                    if (item.equals(Identifier.withDefaultNamespace("book"))) {
                        Condition enchantment = bookEnchantment(entry, depth);
                        rules.add(new LootRule(holder -> holder.is(Items.ENCHANTED_BOOK.builtInRegistryHolder().key()),
                            test.and(enchantment), cooked));
                        test = test.and(enchantment.invert());
                    }
                    rules.add(new LootRule(selector, test, cooked));
                }
                case "tag" -> {
                    Predicate<Holder<Item>> tagged = entry.has("items") ? registrySelector(entry.get("items"), Registries.ITEM)
                        : registrySelectorTag(entry.get("name"), Registries.ITEM);
                    rules.add(new LootRule(tagged, test, cooked));
                }
                case "alternatives" -> {
                    var alternatives = test;
                    for (var child : listOf(entry.get("children"))) {
                        if (!child.isJsonObject()) break;
                        addEntry(child, alternatives, cooked, depth + 1, rules);
                        alternatives = alternatives.and(holderCondition(child.getAsJsonObject(), depth).invert());
                    }
                }
                case "group", "sequence" -> {
                    var sequence = test;
                    for (var child : listOf(entry.get("children"))) {
                        if (!child.isJsonObject()) break;
                        addEntry(child, sequence, cooked, depth + 1, rules);
                        if (type(entry).equals("sequence")) sequence = sequence.and(holderCondition(child.getAsJsonObject(), depth));
                    }
                }
                case "loot_table" -> {
                    JsonElement value = field(entry, "value", "name");
                    if (value == null) return;
                    JsonObject referenced = value.isJsonObject() ? value.getAsJsonObject() : read("loot_table", id(value));
                    if (referenced != null) addTable(referenced, test, cooked, depth + 1, rules);
                }
                default -> { } // Dynamic or custom entries can require server-side loot context.
            }
        }

        private Condition bookEnchantment(JsonObject holder, int depth) {
            Condition enchantment = Condition.NEVER;
            for (JsonElement raw : listOf(field(holder, "modifier", "functions"))) {
                if (!raw.isJsonObject()) continue;
                var value = raw.getAsJsonObject();
                var operation = id(field(value, "type", "function"));
                if (Identifier.withDefaultNamespace("enchant_with_levels").equals(operation)
                    || Identifier.withDefaultNamespace("enchant_randomly").equals(operation)) {
                    enchantment = enchantment.or(holderCondition(value, depth));
                }
            }
            return enchantment;
        }

        private static boolean cooksOnFire(JsonObject holder) {
            for (JsonElement modifier : listOf(field(holder, "modifier", "functions"))) {
                if (!modifier.isJsonObject()) continue;
                var value = modifier.getAsJsonObject();
                if (Identifier.withDefaultNamespace("furnace_smelt").equals(id(field(value, "type", "function")))
                    && containsFireTest(field(value, "condition", "conditions"), 0)) return true;
            }
            return false;
        }

        private static boolean containsFireTest(JsonElement check, int depth) {
            if (check == null || depth > MAX_LOOT_DEPTH) return false;
            if (check.isJsonArray()) return check.getAsJsonArray().asList().stream().anyMatch(value -> containsFireTest(value, depth + 1));
            if (check.isJsonPrimitive()) return check.getAsString().equals("#minecraft:smelts_loot");
            if (!check.isJsonObject()) return false;
            var value = check.getAsJsonObject();
            if (Identifier.withDefaultNamespace("inverted").equals(id(value.get("condition")))) return false;
            return new com.google.gson.JsonPrimitive(true).equals(value.get("is_on_fire"))
                || value.entrySet().stream().anyMatch(entry -> containsFireTest(entry.getValue(), depth + 1));
        }

        private Condition holderCondition(JsonObject json, int depth) {
            return condition(field(json, "condition", "conditions"), depth + 1);
        }

        private Condition condition(JsonElement json, int depth) {
            if (depth > MAX_LOOT_DEPTH) return Condition.UNKNOWN;
            if (json == null) return Condition.ALWAYS;
            if (json.isJsonArray()) return terms(json, depth, true);
            if (json.isJsonPrimitive()) {
                JsonObject reference = read("predicate", id(json));
                return reference == null ? Condition.UNKNOWN : condition(reference, depth + 1);
            }
            if (!json.isJsonObject()) return Condition.UNKNOWN;
            JsonObject value = json.getAsJsonObject();
            return switch (type(value)) {
                case "survives_explosion" -> Condition.ALWAYS;
                case "killed_by_player" -> context -> context.playerKill() ? Decision.YES : Decision.NO;
                case "random_chance" -> chance(value.get("chance"));
                case "table_bonus" -> listOf(value.get("chances")).stream().anyMatch(LootReader::positive)
                    ? context -> Decision.POSSIBLE : Condition.NEVER;
                case "random_chance_with_enchanted_bonus", "random_chance_with_looting" -> context -> Decision.POSSIBLE;
                case "inverted" -> condition(value.get("term"), depth + 1).invert();
                case "all_of" -> terms(value.get("terms"), depth, true);
                case "any_of" -> terms(value.get("terms"), depth, false);
                case "match_block", "block_state_property" -> blockCondition(value);
                case "match_tool" -> toolCondition(value.get("predicate"));
                case "entity_properties" -> entityCondition(value);
                case "location_check" -> fishingBiomeCondition(value);
                case "damage_source_properties" -> meleeCondition(value.get("predicate"));
                case "reference" -> condition(value.get("name"), depth + 1);
                default -> Condition.UNKNOWN;
            };
        }

        private static Condition entityCondition(JsonObject condition) {
            var target = condition.get("entity");
            if (target == null || !target.isJsonPrimitive()) return Condition.UNKNOWN;
            var predicate = condition.get("predicate");
            return context -> {
                if (context.fishing() != null && target.getAsString().equals("this")) {
                    if (predicate == null) return Decision.YES;
                    if (!predicate.isJsonObject() || predicate.getAsJsonObject().size() != 1) return Decision.UNKNOWN;
                    var hook = predicate.getAsJsonObject().get("minecraft:type_specific/fishing_hook");
                    if (hook == null || !hook.isJsonObject() || hook.getAsJsonObject().size() != 1) return Decision.UNKNOWN;
                    var expected = hook.getAsJsonObject().get("in_open_water");
                    if (expected == null || !expected.isJsonPrimitive() || !expected.getAsJsonPrimitive().isBoolean()) {
                        return Decision.UNKNOWN;
                    }
                    Boolean open = context.fishing().openWater();
                    return open == null ? Decision.POSSIBLE : open == expected.getAsBoolean() ? Decision.YES : Decision.NO;
                }
                var entity = switch (target.getAsString()) {
                    case "this" -> context.mob();
                    case "attacker", "direct_attacker", "attacking_player" -> context.playerKill() ? context.player() : null;
                    default -> null;
                };
                return entityMatches(predicate, entity);
            };
        }

        private static Condition fishingBiomeCondition(JsonObject condition) {
            JsonElement raw = condition.get("predicate");
            if (raw == null || !raw.isJsonObject()) return Condition.UNKNOWN;
            JsonObject predicate = raw.getAsJsonObject();
            if (predicate.size() != 1 || !predicate.has("biomes")) return Condition.UNKNOWN;
            Predicate<Holder<Biome>> matches = registrySelector(predicate.get("biomes"), Registries.BIOME);
            return context -> {
                if (context.fishing() == null) return Decision.UNKNOWN;
                Holder<Biome> biome = context.fishing().biome();
                return biome == null ? Decision.POSSIBLE : matches.test(biome) ? Decision.YES : Decision.NO;
            };
        }

        private static Decision entityMatches(JsonElement predicate, Entity entity) {
            Boolean matches = GatherEntityPredicate.matches(predicate, entity);
            return matches == null ? Decision.UNKNOWN : matches ? Decision.YES : Decision.NO;
        }

        private static Condition meleeCondition(JsonElement predicate) {
            if (predicate == null) return Condition.ALWAYS;
            if (!predicate.isJsonObject()) return Condition.UNKNOWN;
            JsonObject test = predicate.getAsJsonObject();
            var tagCodec = TagPredicate.codec(Registries.DAMAGE_TYPE).listOf();
            var fields = Set.of("is_direct", "source_entity", "direct_entity", "tags");
            boolean understood = fields.containsAll(test.keySet());
            return context -> {
                LocalPlayer player = context.player();
                if (!context.playerKill() || player == null) return Decision.UNKNOWN;
                var damage = player.damageSources().playerAttack(player);
                if (test.has("is_direct") && test.get("is_direct").getAsBoolean() != damage.isDirect()) return Decision.NO;
                var matching = entityMatches(test.get("source_entity"), damage.getEntity())
                    .and(entityMatches(test.get("direct_entity"), damage.getDirectEntity()));
                if (test.has("tags")) {
                    var ops = RegistryOps.create(JsonOps.INSTANCE, player.level().registryAccess());
                    var tags = tagCodec.parse(ops, test.get("tags")).result();
                    if (tags.isEmpty()) return matching.and(Decision.UNKNOWN);
                    if (tags.orElseThrow().stream().anyMatch(tag -> !tag.matches(damage.typeHolder()))) return Decision.NO;
                }
                return understood ? matching : matching.and(Decision.UNKNOWN);
            };
        }

        private Condition terms(JsonElement json, int depth, boolean all) {
            Condition combined = all ? Condition.ALWAYS : Condition.NEVER;
            for (var entry : listOf(json)) {
                var test = condition(entry, depth + 1);
                combined = all ? combined.and(test) : combined.or(test);
            }
            return combined;
        }

        private static Condition chance(JsonElement probability) {
            if (probability == null) return Condition.UNKNOWN;
            if (!probability.isJsonPrimitive()) return context -> Decision.POSSIBLE;
            return probability.getAsDouble() >= 1 ? Condition.ALWAYS
                : probability.getAsDouble() > 0 ? context -> Decision.POSSIBLE : Condition.NEVER;
        }

        private static Condition blockCondition(JsonObject json) {
            Predicate<Holder<Block>> block = registrySelector(field(json, "blocks", "block"), Registries.BLOCK);
            JsonElement properties = field(json, "state", "properties");
            Map<String, JsonElement> tests = properties == null ? Map.of() : Map.copyOf(properties.getAsJsonObject().asMap());
            return context -> {
                var state = context.state();
                if (state == null || !block.test(state.getBlock().builtInRegistryHolder())) return Decision.NO;
                for (var test : tests.entrySet()) {
                    Property<?> property = state.getBlock().getStateDefinition().getProperty(test.getKey());
                    if (property == null) return Decision.NO;
                    String actual = stateName(state, property);
                    var expected = test.getValue();
                    if (expected.isJsonPrimitive()) {
                        if (!actual.equals(expected.getAsString())) return Decision.NO;
                    } else if (expected.isJsonObject()) {
                        try {
                            if (!within(Integer.parseInt(actual), expected)) return Decision.NO;
                        } catch (NumberFormatException unsupported) {
                            return Decision.UNKNOWN;
                        }
                    } else return Decision.UNKNOWN;
                }
                return Decision.YES;
            };
        }

        private static <T extends Comparable<T>> String stateName(BlockState state, Property<T> property) {
            return property.getName(state.getValue(property));
        }

        private static Condition toolCondition(JsonElement predicate) {
            if (predicate == null) return Condition.ALWAYS;
            if (!predicate.isJsonObject()) return Condition.UNKNOWN;
            JsonObject json = predicate.getAsJsonObject();
            var items = registrySelector(json.get("items"), Registries.ITEM);
            JsonElement count = json.get("count");
            JsonElement subpredicates = json.get("predicates");
            var enchants = subpredicates == null ? null : subpredicates.getAsJsonObject().get("minecraft:enchantments");
            boolean unknown = json.keySet().stream().anyMatch(key -> !key.equals("items") && !key.equals("count") && !key.equals("predicates"))
                || subpredicates != null && subpredicates.getAsJsonObject().keySet().stream().anyMatch(key -> !key.equals("minecraft:enchantments"));
            return context -> {
                ItemStack tool = context.tool();
                if (tool == null) return Decision.UNKNOWN;
                if (!items.test(tool.getItem().builtInRegistryHolder()) || !within(tool.getCount(), count)) return Decision.NO;
                for (var check : listOf(enchants)) {
                    var enchant = check.getAsJsonObject();
                    var types = registrySelector(enchant.get("enchantments"), Registries.ENCHANTMENT);
                    JsonElement levels = enchant.get("levels");
                    if (tool.getEnchantments().entrySet().stream().noneMatch(actual -> types.test(actual.getKey()) && within(actual.getIntValue(), levels))) {
                        return Decision.NO;
                    }
                }
                return unknown ? Decision.UNKNOWN : Decision.YES;
            };
        }

        private static boolean within(int number, JsonElement range) {
            if (range == null) return true;
            if (range.isJsonPrimitive()) return number == range.getAsInt();
            if (!range.isJsonObject()) return false;
            JsonObject bounds = range.getAsJsonObject();
            return (!bounds.has("min") || number >= bounds.get("min").getAsInt())
                && (!bounds.has("max") || number <= bounds.get("max").getAsInt());
        }

        private JsonObject read(String folder, Identifier id) {
            if (id == null) return null;
            String path = "data/" + id.getNamespace() + "/" + folder + "/" + id.getPath() + ".json";
            return resources.computeIfAbsent(path, LootReader::readResource).orElse(null);
        }

        private static Optional<JsonObject> readResource(String path) {
            try (InputStream stream = openResource(path)) {
                if (stream == null) return Optional.empty();
                var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
                return json.isJsonObject() ? Optional.of(json.getAsJsonObject()) : Optional.empty();
            } catch (IOException | RuntimeException inaccessible) {
                return Optional.empty();
            }
        }

        private static InputStream openResource(String path) throws IOException {
            InputStream resource = GatherSources.class.getResourceAsStream("/" + path);
            if (resource != null) return resource;
            for (var container : FabricLoader.getInstance().getAllMods()) {
                var candidate = container.findPath(path);
                if (candidate.isPresent() && Files.isRegularFile(candidate.get())) return Files.newInputStream(candidate.get());
            }
            return null;
        }

        private static JsonElement field(JsonObject json, String first, String alternative) {
            return json.has(first) ? json.get(first) : json.get(alternative);
        }

        private static List<JsonElement> listOf(JsonElement value) {
            if (value == null || value.isJsonNull()) return List.of();
            return value.isJsonArray() ? value.getAsJsonArray().asList() : List.of(value);
        }

        private static String type(JsonObject json) {
            JsonElement value = json.get("type");
            if (value == null || !value.isJsonPrimitive()) return "";
            return value.getAsString().replaceFirst("^minecraft:", "");
        }

        private static Identifier id(JsonElement json) {
            return json != null && json.isJsonPrimitive() ? Identifier.tryParse(json.getAsString()) : null;
        }

        private static boolean positive(JsonElement value) {
            return value == null || !value.isJsonPrimitive() || value.getAsDouble() > 0;
        }

        private static <T> Predicate<Holder<T>> registrySelectorTag(JsonElement json, ResourceKey<? extends Registry<T>> registry) {
            var tag = id(json);
            return tag == null ? held -> false : held -> held.is(TagKey.create(registry, tag));
        }

        private static <T> Predicate<Holder<T>> registrySelector(JsonElement json, ResourceKey<? extends Registry<T>> registry) {
            if (json == null) return held -> true;
            List<Predicate<Holder<T>>> selectors = new ArrayList<>();
            for (var selector : listOf(json)) {
                if (!selector.isJsonPrimitive()) continue;
                String text = selector.getAsString();
                if (text.startsWith("#")) {
                    var tag = Identifier.tryParse(text.substring(1));
                    if (tag != null) selectors.add(held -> held.is(TagKey.create(registry, tag)));
                } else {
                    var id = id(selector);
                    if (id != null) selectors.add(held -> held.is(ResourceKey.create(registry, id)));
                }
            }
            return held -> selectors.stream().anyMatch(selector -> selector.test(held));
        }
    }
}
