package dev.soprano.task;

import dev.soprano.map.MapVolume;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

record JobSpec(Kind kind, String command, MapVolume.Box region, BlockPos target, List<String> ids,
               Map<String, Integer> amounts, int limit, int width, int height, int grade, int torchSpacing,
               boolean replant, int nearbyRadius, boolean targetsAll, UUID entityId, HuntLoot huntLoot) {
    enum Kind { MINE, COLLECT, CROPS, TREES, QUARRY, TUNNEL, CRAFT, FURNACE, UNLOAD, RESTOCK, RETURN,
        GATHER, HUNT, SETUP, TAKE, FILLBUCKET, CAPTURE, STONECUT, SMITH, FISH, CAMPFIRE, BREW, TRADE, ENCHANT, BARTER, BREED }
    enum HuntLoot { ANY, RAW, COOKED, SHEAR }

    private static final int MAX_REGION = 32_768;
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    static JobSpec parse(String command, MapVolume.Box region, BlockPos target, BlockPos nearbyOrigin) {
        String normalized = command.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (normalized.isEmpty() || normalized.length() > 384 || normalized.contains("|")) {
            throw new IllegalArgumentException("Enter a job of up to 384 characters.");
        }
        String[] fields = normalized.split(" ");
        Kind kind;
        try {
            kind = Kind.valueOf(fields[0].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Unknown job: " + fields[0]);
        }
        List<String> ids = List.of();
        Map<String, Integer> amounts = Map.of();
        int limit = 1, width = 0, height = 0, grade = 0, torchSpacing = 0, nearbyRadius = 0;
        boolean replant = false, targetsAll = false;
        UUID entityId = null;
        HuntLoot huntLoot = HuntLoot.ANY;
        switch (kind) {
            case MINE, COLLECT -> {
                arity(fields, 3, 4);
                ids = ids(fields[1]);
                targetsAll = fields[2].equals("all");
                limit = targetsAll ? MAX_REGION : number(fields[2], 1, MAX_REGION, "Item or block count");
                if (fields.length == 4) nearbyRadius = nearbyRadius(fields[3]);
            }
            case CROPS -> {
                arity(fields, 2, 3);
                limit = number(fields[1], 1, MAX_REGION, "Harvest limit");
                if (fields.length == 3) nearbyRadius = nearbyRadius(fields[2]);
            }
            case CRAFT, SMITH -> {
                arity(fields, 3, 3);
                ids = List.of(id(fields[1]));
                limit = number(fields[2], 1, MAX_REGION, "Item count");
            }
            case BREW -> {
                arity(fields, 2, 4);
                if (fields[1].equals("item")) {
                    arity(fields, 3, 4);
                    ids = List.of(id(fields[2]));
                    if (fields.length == 4) limit = number(fields[3], 1, 64, "Brew amount");
                } else {
                    ids = List.of(fields.length == 4 ? id(fields[3]) : "minecraft:potion", id(fields[1]));
                    if (fields.length >= 3) limit = number(fields[2], 1, 64, "Brew amount");
                }
            }
            case ENCHANT -> {
                arity(fields, 2, 4);
                ids = List.of(id(fields[1]));
                limit = 0;
                if (fields.length >= 3 && (fields.length == 4 || fields[2].matches("[0-9]+"))) {
                    limit = number(fields[2], 1, 3, "Enchanting offer");
                    if (fields.length == 4) ids = List.of(id(fields[1]), id(fields[3]));
                } else if (fields.length == 3) ids = List.of(id(fields[1]), id(fields[2]));
            }
            case STONECUT, CAMPFIRE -> {
                arity(fields, 4, 4);
                ids = List.of(id(fields[1]), id(fields[2]));
                if (ids.get(0).equals(ids.get(1))) throw new IllegalArgumentException(
                    "Choose different " + (kind == Kind.CAMPFIRE ? "campfire" : "stonecutting") + " input and output items.");
                limit = kind == Kind.CAMPFIRE ? number(fields[3], 1, 4, "Campfire input count")
                    : number(fields[3], 1, MAX_REGION, "Stonecutting input count");
            }
            case GATHER -> {
                arity(fields, 3, 3);
                ids = List.of(id(fields[1]));
                limit = number(fields[2], 1, MAX_REGION, "Gather amount");
                nearbyRadius = 24;
            }
            case HUNT -> {
                arity(fields, 5, 7);
                var targets = new ArrayList<String>();
                targets.add(id(fields[1]));
                targets.addAll(ids(fields[2]));
                ids = List.copyOf(targets);
                limit = number(fields[3], 1, MAX_REGION, "Drop count");
                nearbyRadius = nearbyRadius(fields[4]);
                if (fields.length >= 6) entityId = UUID.fromString(fields[5]);
                if (fields.length == 7) huntLoot = HuntLoot.valueOf(fields[6].toUpperCase(Locale.ROOT));
            }
            case SETUP -> {
                arity(fields, 2, 2);
                ids = List.of(id(fields[1]));
            }
            case TAKE -> {
                arity(fields, 3, 3);
                ids = List.of(id(fields[1]));
                limit = number(fields[2], 1, MAX_REGION, "Storage transfer amount");
            }
            case FILLBUCKET -> {
                arity(fields, 2, 2);
                ids = List.of(id(fields[1]));
            }
            case TRADE, BARTER -> {
                arity(fields, 4, 4);
                ids = List.of(id(fields[1]));
                limit = number(fields[2], 1, MAX_REGION, "Trade output count");
                entityId = UUID.fromString(fields[3]);
            }
            case BREED -> {
                arity(fields, 3, 3);
                ids = List.of(id(fields[1]));
                limit = number(fields[2], 1, MAX_REGION, "Offspring count");
            }
            case FISH -> {
                arity(fields, 2, 2);
                ids = List.of(id(fields[1]));
            }
            case CAPTURE -> {
                arity(fields, 3, 3);
                ids = List.of(id(fields[1]));
                nearbyRadius = nearbyRadius(fields[2]);
            }
            case TREES -> {
                arity(fields, 3, 4);
                ids = ids(fields[1]);
                limit = number(fields[2], 1, MAX_REGION, "Log limit");
                replant = fields.length == 4;
                if (replant && !fields[3].equals("replant")) throw new IllegalArgumentException("Expected replant.");
            }
            case QUARRY -> {
                arity(fields, 2, 3);
                limit = number(fields[1], 1, MAX_REGION, "Quarry limit");
                if (fields.length == 3) ids = ids(fields[2]);
            }
            case TUNNEL -> {
                arity(fields, 6, 6);
                width = number(fields[1], 1, 9, "Width");
                height = number(fields[2], 2, 9, "Height");
                grade = number(fields[3], -100, 100, "Grade percent");
                torchSpacing = number(fields[4], 0, 64, "Torch spacing");
                limit = number(fields[5], 1, MAX_REGION, "Tunnel break limit");
            }
            case FURNACE -> {
                arity(fields, 5, 5);
                ids = List.of(id(fields[1]), id(fields[2]), id(fields[3]));
                limit = number(fields[4], 1, 32_768, "Furnace input count");
            }
            case UNLOAD, RESTOCK -> {
                arity(fields, 2, 3);
                var requests = new LinkedHashMap<String, Integer>();
                for (String entry : fields[1].split(",", -1)) {
                    String[] pair = entry.split("=", -1);
                    if (pair.length != 2 || requests.putIfAbsent(id(pair[0]),
                        number(pair[1], 1, 32_768, "Transfer count")) != null) {
                        throw new IllegalArgumentException("Use unique item=count pairs separated by commas.");
                    }
                }
                if (requests.size() > 16) throw new IllegalArgumentException("A storage job accepts up to 16 item types.");
                amounts = Map.copyOf(requests);
                limit = fields.length == 3 ? number(fields[2], 1, 32, "Container checks") : 12;
            }
            case RETURN -> arity(fields, 1, 1);
        }

        if (nearbyRadius > 0) {
            if (nearbyOrigin == null) throw new IllegalArgumentException("A nearby job needs a starting position.");
            region = null;
            target = nearbyOrigin;
        } else {
            switch (kind) {
                case MINE, COLLECT, CROPS, TREES, QUARRY -> checkRegion(region);
                default -> region = null;
            }
            switch (kind) {
                case TUNNEL, FURNACE, STONECUT, SMITH, TAKE, FILLBUCKET, FISH, CAMPFIRE, BREW, ENCHANT -> {
                    if (target == null) throw new IllegalArgumentException("Select a target block first.");
                }
                case CRAFT -> { }
                default -> target = null;
            }
        }
        return new JobSpec(kind, normalized, region, target == null ? null : target.immutable(), ids, amounts,
            limit, width, height, grade, torchSpacing, replant, nearbyRadius, targetsAll, entityId, huntLoot);
    }

    String encode() {
        String box = region == null ? "" : region.x1() + "," + region.y1() + "," + region.z1() + ","
            + region.x2() + "," + region.y2() + "," + region.z2();
        String point = target == null ? "" : target.getX() + "," + target.getY() + "," + target.getZ();
        return command + "|" + box + "|" + point;
    }

    static JobSpec decode(String encoded) {
        if (encoded == null) throw new IllegalArgumentException("A saved job step is missing.");
        String[] fields = encoded.split("\\|", -1);
        if (fields.length != 3) throw new IllegalArgumentException("Invalid saved job step.");
        MapVolume.Box box = null;
        if (!fields[1].isEmpty()) {
            int[] a = coordinates(fields[1], 6);
            box = new MapVolume.Box(a[0], a[1], a[2], a[3], a[4], a[5]);
        }
        BlockPos pos = null;
        if (!fields[2].isEmpty()) {
            int[] a = coordinates(fields[2], 3);
            pos = new BlockPos(a[0], a[1], a[2]);
        }
        return parse(fields[0], box, pos, pos);
    }

    private static int nearbyRadius(String value) {
        return number(value, 1, 32, "Nearby radius");
    }

    private static int[] coordinates(String value, int length) {
        String[] fields = value.split(",", -1);
        if (fields.length != length) throw new IllegalArgumentException("Invalid saved job coordinates.");
        return Arrays.stream(fields).mapToInt(Integer::parseInt).toArray();
    }

    private static void checkRegion(MapVolume.Box region) {
        if (region == null) throw new IllegalArgumentException("Set both region corners first.");
        long x = (long) region.maxX() - region.minX() + 1;
        long y = (long) region.maxY() - region.minY() + 1;
        long z = (long) region.maxZ() - region.minZ() + 1;
        if (x > MAX_REGION || y > MAX_REGION || z > MAX_REGION || x * y * z > MAX_REGION) {
            throw new IllegalArgumentException("A job region can contain up to " + MAX_REGION + " blocks.");
        }
    }

    private static void arity(String[] fields, int minimum, int maximum) {
        if (fields.length < minimum || fields.length > maximum) {
            throw new IllegalArgumentException("Incorrect number of arguments for " + fields[0] + ".");
        }
    }

    private static int number(String value, int minimum, int maximum, String field) {
        try {
            int number = Integer.parseInt(value);
            if (number >= minimum && number <= maximum) return number;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(field + " must be between " + minimum + " and " + maximum + ".");
    }

    private static List<String> ids(String values) {
        return Arrays.stream(values.split(",", -1)).map(JobSpec::id).distinct().toList();
    }

    private static String id(String value) {
        String id = value.contains(":") ? value : "minecraft:" + value;
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid registry ID: " + value);
        return id;
    }
}
