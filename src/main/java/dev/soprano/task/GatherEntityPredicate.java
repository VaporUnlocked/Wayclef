package dev.soprano.task;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.advancements.predicates.entity.EntityPredicate;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Evaluates the part of a vanilla entity predicate backed by the client's live entity data. */
final class GatherEntityPredicate {
    private static final int MAX_CACHE_ENTRIES = 512;
    private static final int MAX_DEPTH = 8;
    private static final Set<String> LOCAL_FIELDS = Set.of("entity_type", "flags", "components", "equipment", "effects",
        "type_specific/sheep", "type_specific/cube_mob");
    private static final Map<JsonElement, Prepared> CACHE = new LinkedHashMap<>(32, .75f, true);
    private static RegistryAccess cachedRegistries;

    @FunctionalInterface
    private interface Test { Boolean matches(Entity entity); }

    private record Prepared(List<Test> tests, boolean complete) implements Test {
        @Override public Boolean matches(Entity entity) {
            boolean known = complete;
            for (Test test : tests) {
                Boolean result = test.matches(entity);
                if (Boolean.FALSE.equals(result)) return false;
                if (result == null) known = false;
            }
            return known ? Boolean.TRUE : null;
        }
    }

    private GatherEntityPredicate() { }

    /** Returns null when a matching entity cannot be confirmed from client data. */
    static Boolean matches(JsonElement predicate, Entity entity) {
        if (entity == null) return null;
        if (predicate == null) return true;
        return cached(predicate, entity.level().registryAccess()).matches(entity);
    }

    private static synchronized Prepared cached(JsonElement predicate, RegistryAccess registries) {
        if (cachedRegistries != registries) {
            CACHE.clear();
            cachedRegistries = registries;
        }
        Prepared previous = CACHE.get(predicate);
        if (previous != null) return previous;
        Prepared prepared = compile(predicate, RegistryOps.create(JsonOps.INSTANCE, registries), 0);
        if (CACHE.size() >= MAX_CACHE_ENTRIES) CACHE.remove(CACHE.keySet().iterator().next());
        CACHE.put(predicate.deepCopy(), prepared);
        return prepared;
    }

    private static Prepared compile(JsonElement predicate, RegistryOps<JsonElement> ops, int depth) {
        if (!predicate.isJsonObject() || depth >= MAX_DEPTH) return new Prepared(List.of(), false);
        List<Test> tests = new ArrayList<>();
        boolean complete = true;
        for (var part : predicate.getAsJsonObject().entrySet()) {
            Identifier key = Identifier.tryParse(part.getKey());
            Test test = key != null && key.getNamespace().equals(Identifier.DEFAULT_NAMESPACE)
                ? compilePart(key, part.getValue(), ops, depth) : null;
            if (test == null) complete = false;
            else tests.add(test);
        }
        return new Prepared(List.copyOf(tests), complete);
    }

    private static Test compilePart(Identifier key, JsonElement data, RegistryOps<JsonElement> ops, int depth) {
        String path = key.getPath();
        if (path.equals("vehicle")) {
            Prepared vehiclePredicate = compile(data, ops, depth + 1);
            return entity -> {
                Entity vehicle = entity.getVehicle();
                return vehicle == null ? Boolean.FALSE : vehiclePredicate.matches(vehicle);
            };
        }
        if (path.equals("passenger")) {
            Prepared passengerPredicate = compile(data, ops, depth + 1);
            return entity -> {
                boolean unknown = false;
                for (Entity passenger : entity.getPassengers()) {
                    Boolean matches = passengerPredicate.matches(passenger);
                    if (Boolean.TRUE.equals(matches)) return true;
                    if (matches == null) unknown = true;
                }
                return unknown ? null : Boolean.FALSE;
            };
        }
        if (!LOCAL_FIELDS.contains(path)) return null;
        JsonObject field = new JsonObject();
        field.add(key.toString(), data);
        try {
            // These vanilla parts inspect the supplied entity and do not consult server level or origin.
            EntityPredicate decoded = EntityPredicate.CODEC.parse(ops, field).result().orElse(null);
            return decoded == null ? null : entity -> evaluate(decoded, entity);
        } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException unavailable) {
            return null;
        }
    }

    private static Boolean evaluate(EntityPredicate predicate, Entity entity) {
        try {
            return predicate.matches(null, null, entity);
        } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException | NullPointerException unavailable) {
            return null;
        }
    }
}
