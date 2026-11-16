package dev.soprano.task;

import dev.soprano.game.ToolDurability;
import dev.soprano.inventory.InventoryJobs;
import dev.soprano.path.Node;
import dev.soprano.task.JobQueue.PreflightIssue;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Checks the queue against the loaded world and present inventory without opening menus or taking actions. */
final class JobPreflight {
    private final ClientLevel level;
    private final LocalPlayer player;
    private final MultiPlayerGameMode mode;
    private final Node home;
    private final Map<String, Integer> inventory;
    private final List<PreflightIssue> issues = new ArrayList<>();
    private final int toolReserve = ToolDurability.configuredReserve();
    private int step;

    private JobPreflight(ClientLevel level, LocalPlayer player, MultiPlayerGameMode mode, Node home) {
        this.level = level;
        this.player = player;
        this.mode = mode;
        this.home = home;
        inventory = InventoryJobs.playerCounts(player);
    }

    static List<PreflightIssue> inspect(List<JobSpec> jobs, ClientLevel level, LocalPlayer player, MultiPlayerGameMode mode, Node home) {
        JobPreflight check = new JobPreflight(level, player, mode, home);
        for (JobSpec job : jobs) {
            check.step++;
            try {
                check.inspect(job);
            } catch (IllegalArgumentException invalid) {
                check.issue(true, invalid.getMessage());
            } catch (IllegalStateException unavailable) {
                check.issue(check.first(), unavailable.getMessage());
            }
        }
        return List.copyOf(check.issues);
    }

    private void inspect(JobSpec job) {
        switch (job.kind()) {
            case RETURN, HUNT, SETUP, TAKE, FILLBUCKET, CAPTURE, FISH, TRADE, BARTER, BREED -> { }
            case CAMPFIRE -> {
                if (!CampfireBatch.available(level, job.target())) issue(first(), "Select a loaded, lit, empty campfire.");
                checkAmount(job.ids().getFirst(), job.limit(), "campfire input", first());
            }
            case GATHER -> InventoryJobs.gatherItem(job.ids().getFirst());
            case CRAFT, FURNACE, STONECUT, SMITH, BREW, ENCHANT, UNLOAD, RESTOCK -> checkStation(job);
            default -> checkWorld(job);
        }
    }

    private void checkWorld(JobSpec job) {
        var region = WorldJob.area(job, level);
        if (region != null) {
            int unloaded = 0;
            for (int x = region.minX() >> 4; x <= region.maxX() >> 4; x++) {
                for (int z = region.minZ() >> 4; z <= region.maxZ() >> 4; z++) {
                    if (!level.hasChunk(x, z)) unloaded++;
                }
            }
            if (unloaded != 0) issue(false, unloaded + " chunks of the job selection are outside loaded terrain.");
        }
        if (job.kind() == JobSpec.Kind.QUARRY && home != null
            && region.contains(home.x(), (int) Math.floor(home.y()) - 1, home.z())) {
            issue(true, "The queue's starting footing must be outside the quarry selection.");
        }
        if (job.kind() == JobSpec.Kind.TUNNEL) {
            BlockPos end = job.target();
            if (!Node.validColumn(end.getX()) || !Node.validColumn(end.getZ()) || level.isOutsideBuildHeight(end.getY())) {
                issue(true, "The tunnel target is outside the world build limits.");
                return;
            }
            if (!first()) {
                issue(false, "Tunnel grade, tools and torch count depend on the position reached by earlier steps.");
                return;
            }
        }

        WorldJob world = new WorldJob(job, level, player);
        if (player.isCreative() && world.collectAfterBreak()) {
            issue(true, "Switch to survival to harvest drops from blocks.");
        }
        if (world.collecting()) {
            if (world.nearestDrop(world.collectBox(), Set.of()) == null) {
                issue(false, "No matching drop currently fits the requested count in the loaded selection.");
            }
            return;
        }

        Map<String, Integer> planting = new TreeMap<>();
        int breakTargets = 0, unavailableTools = 0, inspected = 0;
        String toolExample = null;
        String initialPlant = null;
        for (WorldJob.Target target : world.plannedTargets()) {
            if (job.kind() != JobSpec.Kind.TUNNEL && inspected == job.limit()) break;
            inspected++;
            if (target.plant() != null) {
                String plant = InventoryJobs.itemId(target.plant());
                planting.merge(plant, 1, Integer::sum);
                if (inspected == 1) initialPlant = plant;
            }
            BlockState state = target.expected();
            if (state == null) continue;
            breakTargets++;
            if (JobTools.bestSlot(player, level, target.position(), state, toolReserve) < 0) {
                unavailableTools++;
                if (toolExample == null) toolExample = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            }
        }
        if (unavailableTools != 0) {
            issue(false, "Put a compatible tool above the durability reserve in your hotbar for " + toolExample
                + " (" + unavailableTools + " of " + breakTargets + " inspected targets need a tool).");
        }
        if (job.kind() == JobSpec.Kind.MINE && inspected == 0) {
            issue(first(), "No matching blocks are currently eligible in loaded terrain.");
        } else if (job.kind() == JobSpec.Kind.MINE && !job.targetsAll() && inspected < job.limit()) {
            issue(false, "Only " + inspected + " eligible blocks are currently loaded for the requested " + job.limit() + ".");
        }
        for (var supply : planting.entrySet()) {
            String item = supply.getKey();
            boolean requiredAtStart = first() && item.equals(initialPlant) && inventory.getOrDefault(item, 0) == 0;
            checkAmount(item, supply.getValue(), "planting supply", requiredAtStart);
        }
    }

    private void checkStation(JobSpec job) {
        StationJob station = new StationJob(job, level, player, mode);
        if (station.storageJob()) {
            for (var request : new TreeMap<>(job.amounts()).entrySet()) {
                String item = request.getKey();
                if (station.storageLabels().stream().noneMatch(label -> label.acceptedItems().contains(item))) {
                    issue(first(), "No matching loaded storage label for " + item + " within the job's container checks.");
                }
                if (job.kind() == JobSpec.Kind.UNLOAD) checkAmount(item, request.getValue(), "unload supply", first());
            }
            return;
        }

        BlockPos target = job.target();
        if (target != null && (!Node.validColumn(target.getX()) || !Node.validColumn(target.getZ())
            || level.isOutsideBuildHeight(target.getY()))) {
            issue(true, "The selected station is outside the world build limits.");
            return;
        }
        if (target != null && (!WorldJob.loaded(level, target) || !station.validTarget(target))) {
            issue(first(), "Select and load a compatible station at " + target.toShortString() + ".");
            return;
        }
        if (job.kind() == JobSpec.Kind.CRAFT) {
            if (station.inventoryCrafting() && player.isCreative()) {
                issue(true, "Select a crafting table while in creative mode.");
                return;
            }
            if (station.inventoryCrafting() && player.inventoryMenu.getInputGridSlots().stream().anyMatch(Slot::hasItem)) {
                issue(first(), "Empty the inventory crafting grid before starting the job.");
            }
            if (!station.verifyCraftSupplies()) {
                issue(false, "The recipe book cannot confirm the full craft. Wayclef checks the available game recipes at the station.");
            }
            return;
        }

        if (job.kind() == JobSpec.Kind.SMITH || job.kind() == JobSpec.Kind.BREW || job.kind() == JobSpec.Kind.ENCHANT) return;
        if (job.kind() == JobSpec.Kind.STONECUT) {
            checkAmount(job.ids().getFirst(), job.limit(), "stonecutting input", first());
            return;
        }
        String input = job.ids().get(0), fuel = job.ids().get(1), output = job.ids().get(2);
        checkAmount(input, job.limit(), "furnace input", first());
        var fuelItem = InventoryJobs.item(fuel);
        ItemStack fuelSample = player.getInventory().getNonEquipmentItems().stream()
            .filter(stack -> !stack.isEmpty() && stack.is(fuelItem)).findFirst().orElseGet(fuelItem::getDefaultInstance);
        if (!fuelSample.has(DataComponents.COOKING_FUEL)) {
            issue(false, "No usable " + fuel + " found in inventory. The furnace's fuel is checked when opened.");
        } else {
            checkAmount(fuel, fuel.equals(input) ? job.limit() + 1 : 1, "furnace fuel (unless already loaded)", false);
        }
        station.verifyFurnaceRecipe(input, output);
    }

    private void checkAmount(String item, int required, String purpose, boolean blocking) {
        int present = inventory.getOrDefault(item, 0);
        if (present < required) issue(blocking, "Need " + required + " " + item + " for " + purpose + ". Inventory has " + present + ".");
    }

    private boolean first() { return step == 1; }

    private void issue(boolean blocking, String message) {
        issues.add(new PreflightIssue(step, message, blocking));
    }
}
