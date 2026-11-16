package dev.soprano.map;

import dev.soprano.SopranoClient;
import dev.soprano.gpu.DiscreteCompute;

/** Vulkan broad phase. A GPU hint can only discard known-air sections before the first potential hit. */
final class GpuMapAirPrefix {
    private static final int HEADER = 25;
    private static final int WORK_GROUP_SIZE = 64;
    private static final int MIN_PIXELS = 16_384;
    private static final int MAX_SECTIONS = 1_300_000;
    private static final long RETRY_DELAY = 10_000_000_000L;
    private static final String SHADER = """
        #version 450
        layout(local_size_x = 64) in;
        layout(set = 0, binding = 0, std430) buffer MapData { int words[]; } data;

        const int HEADER = 25;
        const float MAX_TIME = 8192.0;
        const float ERROR_PAD = 0.5;

        float number(int slot) { return intBitsToFloat(data.words[slot]); }
        vec3 vectorAt(int slot) { return vec3(number(slot), number(slot + 1), number(slot + 2)); }

        bool clipAxis(float origin, float direction, float lower, float upper, inout float near, inout float far) {
            if (abs(direction) < 0.0000001) return origin >= lower && origin <= upper;
            float first = (lower - origin) / direction;
            float last = (upper - origin) / direction;
            near = max(near, min(first, last));
            far = min(far, max(first, last));
            return near < far;
        }

        bool knownAir(vec3 low, vec3 high, ivec3 size) {
            ivec3 first = max(ivec3(0), ivec3(floor(low / 16.0)));
            ivec3 last = min(size - 1, ivec3(floor(high / 16.0)));
            for (int z = first.z; z <= last.z; z++) {
                for (int x = first.x; x <= last.x; x++) {
                    for (int y = first.y; y <= last.y; y++) {
                        int slot = HEADER + (z * size.x + x) * size.y + y;
                        if (data.words[slot] != 0) return false;
                    }
                }
            }
            return true;
        }

        void main() {
            int pixel = int(gl_GlobalInvocationID.x);
            int width = data.words[0];
            int height = data.words[1];
            if (pixel >= width * height) return;
            float scale = number(23);
            float horizontal = (float(pixel % width) + 0.5 - float(width) * 0.5) * scale;
            float vertical = (float(height) * 0.5 - float(pixel / width) - 0.5) * scale;
            vec3 origin = vectorAt(12);
            vec3 up = vectorAt(18);
            vec3 right = vec3(number(21), 0.0, number(22));
            vec3 direction = vectorAt(15) + up * vertical + right * horizontal;
            vec3 spread = number(24) * scale * (abs(up) + abs(right));

            // The expanded box is entered before any exact center or coverage ray.
            vec3 inflation = vec3(ERROR_PAD) + spread * MAX_TIME;
            vec3 lower = vectorAt(6) - inflation;
            vec3 upper = vectorAt(9) + inflation;
            float near = 0.0;
            float far = MAX_TIME;
            if (!clipAxis(origin.x, direction.x, lower.x, upper.x, near, far)
                || !clipAxis(origin.y, direction.y, lower.y, upper.y, near, far)
                || !clipAxis(origin.z, direction.z, lower.z, upper.z, near, far)) return;

            ivec3 size = ivec3(data.words[2], data.words[3], data.words[4]);
            float stepTime = 12.0 / max(abs(direction.x), max(abs(direction.y), abs(direction.z)));
            float time = near;
            for (int interval = 0; interval < 2048 && time < far; interval++) {
                float next = min(time + stepTime, far);
                vec3 start = origin + direction * time;
                vec3 end = origin + direction * next;
                vec3 padding = vec3(ERROR_PAD) + spread * next;
                if (!knownAir(min(start, end) - padding, max(start, end) + padding, size)) break;
                time = next;
            }
            data.words[data.words[5] + pixel] = floatBitsToInt(time);
        }
        """;

    record Hint(int[] words, int offset, String device) {
        double at(int pixel) {
            float value = Float.intBitsToFloat(words[offset + pixel]);
            return Float.isFinite(value) && value > 0 && value <= 8192 ? value : 0;
        }
    }

    private record Grid(MapVolume volume, MapVolume.Box bounds, int xSize, int ySize, int zSize,
                        int baseX, int baseY, int baseZ, int[] occupied, int emptyCount) { }

    private Grid lastGrid;
    private long nextAttempt;

    Hint trace(MapSnapshot.Volume volume, MapVolume.Box bounds, VoxelRenderer.Projection projection,
               int width, int height, boolean refineEdges) {
        if (width * height < MIN_PIXELS || height < 64 || width > height * 4) return null;
        long now = System.nanoTime();
        if (nextAttempt != 0 && now - nextAttempt < 0) return null;
        try {
            if (!DiscreteCompute.mayRun()) return null;
        } catch (LinkageError unavailable) {
            nextAttempt = now + RETRY_DELAY;
            return null;
        }
        Grid grid = grid(volume, bounds);
        if (grid == null || grid.emptyCount() < 8) return null;
        double originX = projection.originX() - grid.baseX();
        double originY = projection.originY() - grid.baseY();
        double originZ = projection.originZ() - grid.baseZ();
        if (Math.abs(originX) > 8192 || Math.abs(originY) > 8192 || Math.abs(originZ) > 8192) return null;
        int offset = HEADER + grid.occupied().length;
        int[] storage = new int[offset + width * height];
        storage[0] = width;
        storage[1] = height;
        storage[2] = grid.xSize();
        storage[3] = grid.ySize();
        storage[4] = grid.zSize();
        storage[5] = offset;
        numbers(storage, 6, bounds.minX() - grid.baseX(), bounds.minY() - grid.baseY(), bounds.minZ() - grid.baseZ(),
            bounds.maxX() - grid.baseX() + 1, bounds.maxY() - grid.baseY() + 1, bounds.maxZ() - grid.baseZ() + 1,
            originX, originY, originZ, projection.forwardX(), projection.forwardY(), projection.forwardZ(),
            projection.upX(), projection.upY(), projection.upZ(), projection.rightX(), projection.rightZ(), projection.scale(),
            refineEdges ? .375 : 0);
        System.arraycopy(grid.occupied(), 0, storage, HEADER, grid.occupied().length);
        try {
            var result = DiscreteCompute.run(SHADER, storage, Math.ceilDiv(width * height, WORK_GROUP_SIZE), 1, new int[0]);
            int[] words = result.storage();
            if (words.length != storage.length) throw new IllegalStateException("Unexpected Vulkan map result size.");
            return new Hint(words, offset, result.deviceName());
        } catch (RuntimeException | LinkageError unavailable) {
            nextAttempt = now + RETRY_DELAY;
            SopranoClient.LOG.debug("Dedicated Vulkan map broad phase unavailable. Using the exact CPU renderer.", unavailable);
            return null;
        }
    }

    private Grid grid(MapSnapshot.Volume volume, MapVolume.Box bounds) {
        if (lastGrid != null && lastGrid.volume() == volume && lastGrid.bounds().equals(bounds)) return lastGrid;
        int minX = bounds.minX() >> 4, maxX = bounds.maxX() >> 4;
        int minY = bounds.minY() >> 4, maxY = bounds.maxY() >> 4;
        int minZ = bounds.minZ() >> 4, maxZ = bounds.maxZ() >> 4;
        int width = maxX - minX + 1, height = maxY - minY + 1, length = maxZ - minZ + 1;
        long sections = (long) width * height * length;
        if (sections < 8 || sections > MAX_SECTIONS) return null;
        int[] occupied = new int[(int) sections];
        int index = 0, empty = 0;
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    boolean knownAir = volume.sectionState(x, y, z) == 0;
                    occupied[index++] = knownAir ? 0 : 1;
                    if (knownAir) empty++;
                }
            }
        }
        lastGrid = new Grid(volume, bounds, width, height, length, minX << 4, minY << 4, minZ << 4, occupied, empty);
        return lastGrid;
    }

    private static void numbers(int[] storage, int start, double... values) {
        for (int i = 0; i < values.length; i++) storage[start + i] = Float.floatToRawIntBits((float) values[i]);
    }
}
