package dev.soprano.ui;

import dev.soprano.SopranoClient;
import dev.soprano.build.BlueprintBlockStates;
import dev.soprano.build.BlueprintImport;
import dev.soprano.build.BuildLayers;
import dev.soprano.build.BuildPlan;
import dev.soprano.game.BlueprintConstruction;
import dev.soprano.map.MapVolume;
import dev.soprano.map.NamedRegions;
import dev.soprano.path.SafeReturnCheckpoints.Scope;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/** Shared selection shortcuts and asynchronous build requests issued from chat. */
final class AutomationControls {
    static final int NEARBY_RADIUS = 24;
    private final SopranoModule module;
    private final BlueprintImport importer = new BlueprintImport();
    private ClientLevel selectionLevel;
    private Scope selectionScope;
    private BlockPos first, second;
    private ClientLevel importLevel;
    private LocalPlayer importPlayer;
    private BuildPlan imported;

    AutomationControls(SopranoModule module) { this.module = module; }

    BlockPos first() { updateSelectionScope(); return first; }
    BlockPos second() { updateSelectionScope(); return second; }

    void select(BlockPos a, BlockPos b) {
        updateSelectionScope();
        first = a == null ? null : a.immutable();
        second = b == null ? null : b.immutable();
    }

    String setCorner(boolean start, BlockPos position) {
        player();
        NamedRegions.checkCorner(position.getX(), position.getY(), position.getZ());
        if (Minecraft.getInstance().level.isOutsideBuildHeight(position)) throw new IllegalArgumentException("Select a block within the world build height.");
        if (start) select(position, second());
        else select(first(), position);
        return "Corner " + (start ? "A" : "B") + " set to " + position.toShortString() + ".";
    }

    private void updateSelectionScope() {
        ClientLevel level = Minecraft.getInstance().level;
        Scope scope = module.storageScope();
        if (level != selectionLevel || !Objects.equals(scope, selectionScope)) {
            selectionLevel = level;
            selectionScope = scope;
            first = second = null;
        }
    }

    MapVolume.Box selection() {
        updateSelectionScope();
        return first == null || second == null ? null : new MapVolume.Box(first.getX(), first.getY(), first.getZ(),
            second.getX(), second.getY(), second.getZ());
    }

    void fill(String state, boolean hollow) {
        player();
        if (selection() == null) throw new IllegalStateException("Set both region corners first.");
        var block = BlueprintBlockStates.resolve(state);
        if (block == null || block.isAir()) throw new IllegalArgumentException("Choose a placeable block state.");
        BuildPlan plan = new BuildPlan();
        plan.box(cell(first), cell(second), state, hollow);
        startPlan(plan);
    }

    void buildCurrent() {
        player();
        startPlan(PlanningViewport.worldBlueprint(Minecraft.getInstance().level));
    }

    String buildFile(String input, BlockPos origin) {
        SopranoModule.ensureActivityIdle();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = player();
        String entered = input.strip();
        if (entered.length() >= 2 && entered.startsWith("\"") && entered.endsWith("\"")) {
            entered = entered.substring(1, entered.length() - 1);
        }
        if (entered.isBlank()) throw new IllegalArgumentException("Enter a schematic path.");
        Path path = Path.of(entered);
        Path directory = mc.gameDirectory.toPath();
        Path source = directory.resolve(path).normalize();
        if (!Files.isRegularFile(source) && !path.isAbsolute() && path.getNameCount() == 1) {
            source = directory.resolve("schematics").resolve(path).normalize();
        }
        if (!Files.isRegularFile(source)) throw new IllegalArgumentException("Schematic file could not be found.");
        BuildPlan plan = new BuildPlan();
        importer.start(source, cell(origin == null ? player.blockPosition() : origin), mc.level, plan);
        imported = plan;
        importLevel = mc.level;
        importPlayer = player;
        return "Reading " + source.getFileName() + ".";
    }

    void tick() {
        updateSelectionScope();
        if (!importer.pending()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != importLevel || mc.player != importPlayer || !importPlayer.isAlive()) { cancelImport(); return; }
        if (mc.gui.screen() instanceof ChatScreen) return;
        try {
            var result = importer.poll(mc.level, imported);
            if (result == null) return;
            if (result.unavailable() != 0) throw new IllegalStateException(result.unavailable() + " schematic blocks are unavailable. Review the file in the Build planner.");
            startPlan(imported);
            SopranoClient.feedback(BlueprintConstruction.get().statusText());
        } catch (IllegalArgumentException | IllegalStateException failure) {
            SopranoClient.feedback(failure.getMessage());
        } finally {
            if (!importer.pending()) cancelImport();
        }
    }

    void cancelImport() {
        importer.cancel();
        imported = null;
        importLevel = null;
        importPlayer = null;
    }

    private void startPlan(BuildPlan plan) {
        module.startConstruction(plan, BuildLayers.Direction.BOTTOM_UP, BuildPlan.MAX_BLOCKS * 2, 0);
    }

    void mine(String arguments) {
        String[] fields = arguments.strip().split("\\s+");
        String count = "all";
        int firstBlock = 0;
        if (fields[0].matches("[+-]?\\d+")) {
            count = fields[0];
            firstBlock = 1;
        }
        if (fields.length <= firstBlock || fields[firstBlock].isEmpty()) throw new IllegalArgumentException("Enter at least one block ID to mine.");
        String blocks = String.join(",", Arrays.copyOfRange(fields, firstBlock, fields.length));
        module.startSingleJob("mine " + blocks + " " + count + " " + NEARBY_RADIUS, null, null);
    }

    void farm(int radius) { module.startSingleJob("crops 32768 " + radius, null, null); }

    void breed(String input) {
        String[] fields = input.strip().split("\\s+");
        if (fields.length > 2 || fields[0].isEmpty()) throw new IllegalArgumentException("Use breed <mob> [count].");
        module.startSingleJob("breed " + fields[0] + " " + (fields.length == 2 ? fields[1] : "1"), null, null);
    }

    void pickup(String input) {
        String[] fields = input.strip().split("\\s+");
        if (fields.length > 2 || fields[0].isEmpty()) throw new IllegalArgumentException("Use pickup <item[,item]> [count].");
        module.startSingleJob("collect " + fields[0] + " " + (fields.length == 2 ? fields[1] : "all") + " " + NEARBY_RADIUS, null, null);
    }

    void tunnel(int length) {
        LocalPlayer player = player();
        BlockPos end = player.blockPosition().relative(player.getDirection(), length);
        module.startSingleJob("tunnel 1 2 0 0 " + length * 2, null, end);
    }

    void runJob(String spec) {
        String kind = spec.strip().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        boolean needsTarget = kind.equals("furnace") || kind.equals("tunnel") || kind.equals("brew") || kind.equals("enchant");
        BlockPos target = needsTarget ? crosshair() : kind.equals("craft") ? pickedBlock() : null;
        module.startSingleJob(spec, selection(), target);
    }

    static LocalPlayer player() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) throw new IllegalStateException("Join a world first.");
        return mc.player;
    }

    static BlockPos crosshair() {
        BlockPos position = pickedBlock();
        if (position == null) throw new IllegalArgumentException("Aim at a loaded block first.");
        return position;
    }

    private static BlockPos pickedBlock() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
            && mc.level.hasChunk(hit.getBlockPos().getX() >> 4, hit.getBlockPos().getZ() >> 4)) return hit.getBlockPos();
        return null;
    }

    private static BuildPlan.Cell cell(BlockPos pos) { return new BuildPlan.Cell(pos.getX(), pos.getY(), pos.getZ()); }
}
