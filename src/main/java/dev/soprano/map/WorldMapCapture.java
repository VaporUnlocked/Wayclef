package dev.soprano.map;

import dev.soprano.game.MinecraftTerrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Surveys received chunks on the client thread and publishes compact, immutable voxel sections. */
public final class WorldMapCapture implements MapVolume {
    private static final int SECTION_SIZE = 16;
    private static final int SECTION_VOLUME = 4096;
    private static final int MAX_VIEW_DISTANCE = 32;
    private static final int COLUMN_LOOKUPS_PER_TICK = 18;
    private static final int UPPER_SECTIONS_PER_TICK = 4;
    private static final int SECTIONS_PER_TICK = 7;
    private static final long NANOS_PER_TICK = 3_000_000L;
    private static final int COVERED_TERRAIN = 1;
    private static final Heightmap.Types SURFACE = Heightmap.Types.WORLD_SURFACE;
    private static final MapMaterial COVERED = new MapMaterial("Covered terrain", tile(0xff555a61, 13));
    private static final MapSection EMPTY = MapSection.uniform(0);
    private static final MapSection BURIED = MapSection.uniform(COVERED_TERRAIN);

    private final Minecraft minecraft;
    private final ClientLevel level;
    private final LocalPlayer player;
    private final MapCoverage coverage;
    private final SurveyCache cache;
    private final boolean resurvey;
    private final int minSectionX, minSectionY, minSectionZ, sectionColumns, sectionsHigh;
    private final int minX, minY, minZ, maxX, maxY, maxZ;
    private final List<Column> columns;
    private final AtomicReferenceArray<MapSection> sections;
    private final ArrayDeque<Column> unavailable = new ArrayDeque<>();
    private final ArrayDeque<ColumnWork> deeper = new ArrayDeque<>();
    private final ArrayDeque<MapSnapshot.Volume> restoring = new ArrayDeque<>();
    private final List<SurveyCache.Section> unsaved = new ArrayList<>();
    private final List<Future<List<SurveyCache.Section>>> writes = new ArrayList<>();
    private final Map<BlockState, Integer> materialIds = new IdentityHashMap<>();
    private final BlockPos.MutableBlockPos colorPosition = new BlockPos.MutableBlockPos();

    private volatile MapMaterial[] materials = {COVERED, COVERED};
    private volatile long revision;
    private MapSnapshot.Volume frozen;
    private final ArrayDeque<Future<List<MapSnapshot.Volume>>> loading = new ArrayDeque<>();
    private int nextColumn, loadedColumns, publishedSections, unavailableToRetry = -1;
    private int changedSections, reusedColumns;

    public WorldMapCapture(Minecraft minecraft) {
        this(minecraft, null);
    }

    public WorldMapCapture(Minecraft minecraft, MapCoverage coverage) {
        this(minecraft, coverage, null, false);
    }

    public WorldMapCapture(Minecraft minecraft, MapCoverage coverage, SurveyCache cache, boolean resurvey) {
        this(minecraft, coverage, cache, resurvey, null);
    }

    public WorldMapCapture(Minecraft minecraft, MapCoverage coverage, SurveyCache cache, boolean resurvey, Box focus) {
        this.minecraft = Objects.requireNonNull(minecraft);
        this.coverage = coverage;
        this.cache = cache;
        this.resurvey = resurvey;
        level = Objects.requireNonNull(minecraft.level, "No client world to survey");
        player = Objects.requireNonNull(minecraft.player, "No player to locate the survey");
        BlockPos position = player.blockPosition();
        int centerX = position.getX() >> 4, centerZ = position.getZ() >> 4;
        int radius = Math.clamp(minecraft.options.getEffectiveRenderDistance(), 0, MAX_VIEW_DISTANCE);
        sectionColumns = radius * 2 + 1;
        minSectionX = centerX - radius;
        minSectionZ = centerZ - radius;
        minX = minSectionX * SECTION_SIZE;
        minZ = minSectionZ * SECTION_SIZE;
        maxX = minX + sectionColumns * SECTION_SIZE - 1;
        maxZ = minZ + sectionColumns * SECTION_SIZE - 1;
        minY = level.getMinY();
        maxY = minY + level.getHeight() - 1;
        minSectionY = minY >> 4;
        sectionsHigh = (maxY >> 4) - minSectionY + 1;
        sections = new AtomicReferenceArray<>(sectionColumns * sectionColumns * sectionsHigh);
        List<Column> nearby = surveyOrder(centerX, centerZ, radius);
        if (focus == null) {
            columns = nearby;
            if (cache != null) loading.addLast(cache.loadWindow(minSectionX, minSectionZ, maxX >> 4, maxZ >> 4));
        } else {
            int left = Math.max(minSectionX, focus.minX() >> 4), right = Math.min(maxX >> 4, focus.maxX() >> 4);
            int top = Math.max(minSectionZ, focus.minZ() >> 4), bottom = Math.min(maxZ >> 4, focus.maxZ() >> 4);
            columns = nearby.stream().filter(column -> column.x >= left && column.x <= right
                && column.z >= top && column.z <= bottom).toList();
            if (cache != null && !columns.isEmpty()) loading.addLast(cache.loadWindow(left, top, right, bottom));
        }
    }

    public boolean valid(Minecraft client) {
        return client == minecraft && client.level == level && client.player == player;
    }

    public boolean nearPlayer(Minecraft client) {
        if (!valid(client)) return false;
        BlockPos position = player.blockPosition();
        int centerX = minSectionX + sectionColumns / 2;
        int centerZ = minSectionZ + sectionColumns / 2;
        int padding = Math.max(1, sectionColumns / 5);
        return Math.abs((position.getX() >> 4) - centerX) <= padding && Math.abs((position.getZ() >> 4) - centerZ) <= padding;
    }

    /** Advances the survey without requesting chunks or retaining live chunk objects. */
    public void tick() {
        if (!valid(minecraft)) return;
        if (restoreNext()) return;
        long started = System.nanoTime();
        int previous = changedSections;
        int surfaceReads = 0;
        for (int lookups = 0; lookups < COLUMN_LOOKUPS_PER_TICK && surfaceReads < UPPER_SECTIONS_PER_TICK; lookups++) {
            if (lookups > 0 && System.nanoTime() - started >= NANOS_PER_TICK) break;
            Column next = nextDiscovery();
            if (next == null) break;
            if (!resurvey && retainedColumn(next)) {
                reusedColumns++;
                continue;
            }
            LevelChunk chunk = loaded(next.x, next.z);
            if (chunk == null) {
                if (unavailableToRetry < 0) unavailable.addLast(next);
                continue;
            }
            loadedColumns++;
            if (coverage != null) coverage.observe(next.x, next.z);
            int upperSection = publishEmptySections(next, chunk);
            if (upperSection >= 0) {
                ColumnWork work = new ColumnWork(next, upperSection, exposure(next, chunk));
                captureNext(work, chunk);
                surfaceReads++;
                if (work.nextSection >= 0) deeper.addLast(work);
            }
        }

        for (int count = surfaceReads; count < SECTIONS_PER_TICK && !deeper.isEmpty(); count++) {
            if (surfaceReads + count > 0 && System.nanoTime() - started >= NANOS_PER_TICK) break;
            ColumnWork work = deeper.removeFirst();
            LevelChunk chunk = loaded(work.column.x, work.column.z);
            if (chunk == null) continue;
            captureNext(work, chunk);
            if (work.nextSection >= 0) deeper.addLast(work);
        }
        if (changedSections != previous) revision++;
    }

    public boolean settled() {
        return loading.isEmpty() && restoring.isEmpty() && nextColumn == columns.size()
            && unavailableToRetry == 0 && deeper.isEmpty();
    }

    public String progressText() {
        if (!loading.isEmpty() || !restoring.isEmpty()) return "Opening retained terrain…";
        if (cache != null && cache.problem() != null) return cache.problem();
        if (reusedColumns != 0 && nextColumn >= columns.size() && deeper.isEmpty() && unavailableToRetry <= 0)
            return (loadedColumns + reusedColumns) + " surveyed chunks  ·  " + reusedColumns + " retained";
        if (nextColumn < columns.size() || unavailableToRetry > 0) {
            return "Surveying nearby terrain · " + loadedColumns + " loaded chunks";
        }
        if (!deeper.isEmpty()) {
            return "Surveying below the surface · " + publishedSections + " sections";
        }
        return loadedColumns + " loaded chunks surveyed"
            + (loadedColumns == columns.size() ? "" : " · " + (columns.size() - loadedColumns) + " unreceived");
    }

    @Override public int minX() { return minX; }
    @Override public int minY() { return minY; }
    @Override public int minZ() { return minZ; }
    @Override public int maxX() { return maxX; }
    @Override public int maxY() { return maxY; }
    @Override public int maxZ() { return maxZ; }
    @Override public long revision() { return revision; }

    @Override public int blockAt(int x, int y, int z) {
        if (y < minY || y > maxY) return -1;
        MapSection section = section(x >> 4, y >> 4, z >> 4);
        return section == null ? -1 : section.materialAt(index(x & 15, y & 15, z & 15));
    }

    @Override public int sectionState(int sectionX, int sectionY, int sectionZ) {
        MapSection section = section(sectionX, sectionY, sectionZ);
        return section == null ? -1 : section.solidCount() == 0 ? 0 : 1;
    }

    @Override public boolean hasSectionViews() { return true; }

    @Override public SectionView sectionView(int x, int y, int z) {
        return section(x, y, z);
    }

    @Override public MapMaterial material(int id) {
        MapMaterial[] published = materials;
        return id > 0 && id < published.length ? published[id] : COVERED;
    }

    /** Freezes one terrain publication on the client thread without decoding its sections. */
    public MapSnapshot.Volume freeze() {
        if (frozen == null || frozen.revision() != revision) {
            MapSection[] publication = new MapSection[sections.length()];
            for (int i = 0; i < publication.length; i++) publication[i] = sections.get(i);
            frozen = new MapSnapshot.Volume(new Box(minX, minY, minZ, maxX, maxY, maxZ),
                publication, materials, revision);
        }
        return frozen;
    }

    /** Counts received voxels and non-air voxels, including fluids. */
    public AreaStats stats(Box box) {
        return MapSnapshot.Volume.count(this, box);
    }

    public boolean flushSurvey() {
        if (cache == null) return true;
        List<SurveyCache.Section> retries = new ArrayList<>();
        for (var iterator = writes.iterator(); iterator.hasNext();) {
            var future = iterator.next();
            if (!future.isDone()) continue;
            try { retries.addAll(future.get()); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
            catch (ExecutionException failure) { dev.soprano.SopranoClient.LOG.warn("Survey save failed", failure); }
            iterator.remove();
        }
        if (retries.isEmpty() && unsaved.isEmpty()) return writes.isEmpty();
        retries.addAll(unsaved);
        writes.add(cache.save(List.copyOf(retries), materials, minY, maxY));
        unsaved.clear();
        return false;
    }

    public void restoreSurvey(Box region) {
        if (cache == null || !valid(minecraft)) return;
        int left = Math.max(minSectionX, region.minX() >> 4), right = Math.min(maxX >> 4, region.maxX() >> 4);
        int top = Math.max(minSectionZ, region.minZ() >> 4), bottom = Math.min(maxZ >> 4, region.maxZ() >> 4);
        if (left <= right && top <= bottom) loading.addLast(cache.loadWindow(left, top, right, bottom));
    }

    private boolean restoreNext() {
        if (restoring.isEmpty() && !loading.isEmpty()) {
            if (!loading.getFirst().isDone()) return true;
            try { restoring.addAll(loading.removeFirst().get()); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (ExecutionException failure) { dev.soprano.SopranoClient.LOG.warn("Survey restore failed", failure); }
        }
        MapSnapshot.Volume volume = restoring.pollFirst();
        if (volume == null) return false;
        SurveyCache.Palette palette = new SurveyCache.Palette(materials);
        int[] materialMap = new int[volume.materialCount()];
        for (int id = 1; id < materialMap.length; id++) materialMap[id] = palette.id(volume.material(id));
        materials = palette.array();
        for (int z = Math.max(minSectionZ, volume.minZ() >> 4); z <= Math.min(maxZ >> 4, volume.maxZ() >> 4); z++) {
            for (int x = Math.max(minSectionX, volume.minX() >> 4); x <= Math.min(maxX >> 4, volume.maxX() >> 4); x++) {
                boolean any = false;
                for (int y = Math.max(minSectionY, volume.minY() >> 4); y <= Math.min(maxY >> 4, volume.maxY() >> 4); y++) {
                    MapSection section = (MapSection) volume.sectionView(x, y, z);
                    if (section == null) continue;
                    if (sections.compareAndSet(slot(x, y, z), null, section.remap(id -> materialMap[id]))) publishedSections++;
                    any = true;
                }
                if (any && coverage != null) coverage.observe(x, z);
            }
        }
        revision++;
        return true;
    }

    private boolean retainedColumn(Column column) {
        for (int y = 0; y < sectionsHigh; y++) {
            if (section(column.x, minSectionY + y, column.z) == null) return false;
        }
        return true;
    }

    private Column nextDiscovery() {
        if (nextColumn < columns.size()) return columns.get(nextColumn++);
        if (unavailableToRetry < 0) unavailableToRetry = unavailable.size();
        if (unavailableToRetry == 0) return null;
        unavailableToRetry--;
        return unavailable.removeFirst();
    }

    private LevelChunk loaded(int x, int z) {
        if (x < minSectionX || x >= minSectionX + sectionColumns || z < minSectionZ || z >= minSectionZ + sectionColumns) return null;
        return level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
    }

    private int publishEmptySections(Column column, LevelChunk chunk) {
        LevelChunkSection[] live = chunk.getSections();
        int upper = -1;
        for (int i = 0; i < Math.min(live.length, sectionsHigh); i++) {
            if (live[i].hasOnlyAir()) publish(column, i, EMPTY);
            else upper = i;
        }
        return upper;
    }

    private void captureNext(ColumnWork work, LevelChunk chunk) {
        LevelChunkSection[] live = chunk.getSections();
        while (work.nextSection >= 0) {
            int index = work.nextSection--;
            if (index >= live.length || live[index].hasOnlyAir()
                || !resurvey && section(work.column.x, minSectionY + index, work.column.z) != null) continue;
            LevelChunkSection data = live[index];
            publish(work.column, index, capture(work, index, data));
            return;
        }
    }

    private MapSection capture(ColumnWork work, int index, LevelChunkSection source) {
        if (source.hasOnlyAir()) return EMPTY;
        int sectionBottom = (minSectionY + index) * SECTION_SIZE;
        boolean completelyCovered = sectionBottom + 15 < work.exposure.lowest;
        if (completelyCovered && !source.maybeHas(block -> !block.isSolidRender())) return BURIED;

        int[] ids = new int[SECTION_VOLUME];
        int solid = 0;
        for (int y = 0; y < SECTION_SIZE; y++) {
            for (int z = 0; z < SECTION_SIZE; z++) {
                for (int x = 0; x < SECTION_SIZE; x++) {
                    BlockState block = source.getBlockState(x, y, z);
                    if (block.isAir()) continue;
                    int worldY = sectionBottom + y;
                    int id = COVERED_TERRAIN;
                    if (!block.isSolidRender() || !completelyCovered && worldY >= work.exposure.threshold[z * SECTION_SIZE + x]) {
                        colorPosition.set(work.column.x * SECTION_SIZE + x, worldY, work.column.z * SECTION_SIZE + z);
                        id = materialId(block);
                    }
                    ids[index(x, y, z)] = id;
                    solid++;
                }
            }
        }
        return MapSection.pack(ids, solid);
    }

    private Exposure exposure(Column column, LevelChunk center) {
        LevelChunk west = loaded(column.x - 1, column.z), east = loaded(column.x + 1, column.z);
        LevelChunk north = loaded(column.x, column.z - 1), south = loaded(column.x, column.z + 1);
        int[] own = new int[SECTION_SIZE * SECTION_SIZE];
        boolean primed = center.hasPrimedHeightmap(SURFACE);
        for (int z = 0; z < SECTION_SIZE; z++) {
            for (int x = 0; x < SECTION_SIZE; x++) {
                own[z * SECTION_SIZE + x] = primed ? center.getHeight(SURFACE, x, z) : columnTop(center, x, z);
            }
        }

        int[] threshold = new int[own.length];
        int lowest = maxY;
        for (int z = 0; z < SECTION_SIZE; z++) {
            for (int x = 0; x < SECTION_SIZE; x++) {
                int i = z * SECTION_SIZE + x;
                int left = x > 0 ? own[i - 1] : edgeHeight(west, 15, z);
                int right = x < 15 ? own[i + 1] : edgeHeight(east, 0, z);
                int back = z > 0 ? own[i - SECTION_SIZE] : edgeHeight(north, x, 15);
                int front = z < 15 ? own[i + SECTION_SIZE] : edgeHeight(south, x, 0);
                threshold[i] = Math.min(own[i], 1 + Math.min(Math.min(left, right), Math.min(back, front)));
                lowest = Math.min(lowest, threshold[i]);
            }
        }
        return new Exposure(threshold, lowest);
    }

    private int columnTop(LevelChunk chunk, int x, int z) {
        LevelChunkSection[] live = chunk.getSections();
        for (int index = live.length - 1; index >= 0; index--) {
            LevelChunkSection section = live[index];
            if (section.hasOnlyAir()) continue;
            for (int y = 15; y >= 0; y--) {
                if (!section.getBlockState(x, y, z).isAir()) return (minSectionY + index) * SECTION_SIZE + y;
            }
        }
        return minY - 1;
    }

    private int edgeHeight(LevelChunk chunk, int x, int z) {
        return chunk != null && chunk.hasPrimedHeightmap(SURFACE) ? chunk.getHeight(SURFACE, x, z) : maxY;
    }

    private int materialId(BlockState block) {
        Integer id = materialIds.get(block);
        if (id != null) return id;
        String name = block.getBlock().getName().getString();
        int color = block.getMapColor(level, colorPosition).calculateARGBColor(MapColor.Brightness.NORMAL);
        if ((color >>> 24) == 0) color = 0xff858991;
        MapMaterial[] expanded = Arrays.copyOf(materials, materials.length + 1);
        int index = expanded.length - 1;
        boolean routeFloor = !MinecraftTerrain.inherentHazard(block)
            && block.isCollisionShapeFullBlock(level, colorPosition);
        expanded[index] = new MapMaterial(name, tile(color, name.hashCode()), outline(block), routeFloor);
        materials = expanded;
        materialIds.put(block, index);
        return index;
    }

    private List<MapMaterial.Box> outline(BlockState block) {
        if (block.isSolidRender()) return List.of();
        return MapMaterial.outline(block.getShape(level, colorPosition));
    }

    /** Creates an original, low-frequency mosaic from the block's map color. */
    private static int[] tile(int color, int seed) {
        int[] pixels = new int[64];
        for (int v = 0; v < 8; v++) {
            for (int u = 0; u < 8; u++) {
                int grain = (u + (v >> 1) * 5) * 37 + v * 19 + seed;
                grain ^= grain >>> 7;
                int shade = Math.floorMod(grain, 15) - 7;
                int red = Math.clamp(((color >>> 16) & 255) + shade, 0, 255);
                int green = Math.clamp(((color >>> 8) & 255) + shade, 0, 255);
                int blue = Math.clamp((color & 255) + shade, 0, 255);
                pixels[v * 8 + u] = 0xff000000 | (red << 16) | (green << 8) | blue;
            }
        }
        return pixels;
    }

    private void publish(Column column, int yIndex, MapSection data) {
        int slot = slot(column.x, minSectionY + yIndex, column.z);
        MapSection previous = sections.get(slot);
        if (previous != null && !resurvey || previous == data) return;
        sections.set(slot, data);
        if (previous == null) publishedSections++;
        changedSections++;
        if (cache != null) unsaved.add(new SurveyCache.Section(column.x, minSectionY + yIndex, column.z, data));
    }

    private MapSection section(int x, int y, int z) {
        int slot = slot(x, y, z);
        return slot < 0 ? null : sections.get(slot);
    }

    private int slot(int x, int y, int z) {
        int dx = x - minSectionX, dy = y - minSectionY, dz = z - minSectionZ;
        if (dx < 0 || dx >= sectionColumns || dz < 0 || dz >= sectionColumns || dy < 0 || dy >= sectionsHigh) return -1;
        return (dz * sectionColumns + dx) * sectionsHigh + dy;
    }

    private static int index(int x, int y, int z) { return (y * SECTION_SIZE + z) * SECTION_SIZE + x; }

    private static List<Column> surveyOrder(int centerX, int centerZ, int radius) {
        List<Column> result = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1));
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                result.add(new Column(centerX + dx, centerZ + dz));
            }
        }
        result.sort(Comparator.comparingInt(c -> {
            int dx = c.x - centerX, dz = c.z - centerZ;
            return dx * dx + dz * dz;
        }));
        return List.copyOf(result);
    }

    private record Column(int x, int z) {}
    private record Exposure(int[] threshold, int lowest) {}

    private static final class ColumnWork {
        private final Column column;
        private final Exposure exposure;
        private int nextSection;

        private ColumnWork(Column column, int nextSection, Exposure exposure) {
            this.column = column;
            this.nextSection = nextSection;
            this.exposure = exposure;
        }
    }

}
