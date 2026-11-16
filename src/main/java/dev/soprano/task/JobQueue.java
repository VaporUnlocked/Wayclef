package dev.soprano.task;

import dev.soprano.SopranoClient;
import dev.soprano.host.ClientEvents;
import dev.soprano.game.BlueprintConstruction;
import dev.soprano.game.FollowController;
import dev.soprano.game.MinecraftTerrain;
import dev.soprano.game.MiningApproach;
import dev.soprano.game.Navigation;
import dev.soprano.game.ReplaceActivity;
import dev.soprano.game.RouteBlockProtection;
import dev.soprano.game.ToolDurability;
import dev.soprano.inventory.InventoryJobs;
import dev.soprano.map.MapVolume;
import dev.soprano.mixin.FishingHookAccessor;
import dev.soprano.path.Goal;
import dev.soprano.path.IncrementalSearch;
import dev.soprano.path.Node;
import dev.soprano.path.RouteExclusions;
import dev.soprano.ui.PlanningScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.Connection;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.SmithingTableBlock;
import net.minecraft.world.level.block.StonecutterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Runs a bounded job queue through the live movement and interaction controllers. */
public final class JobQueue implements ClientEvents.Listener {
    private enum Phase { IDLE, ARMED, NEXT, CHOOSE, WALK, AIM, EXIT, BREAK, PAUSED_TOOL, CONFIRM, DROPS, COOK_WAIT, OPEN_MENU, MENU,
        ACCESS_PLAN, ACCESS_STEP, ACCESS_WALK, GATHER, HUNT, HUNT_DROPS, BUCKET_CONFIRM, FISH, CAMPFIRE, TRADE, BARTER, BREED }
    private enum Action { BREAK, PLANT, OPEN, PICKUP, RETURN, RETURN_TO_FURNACE, TUNNEL_END, SCOOP, FISH, CAMPFIRE }
    private enum FishPhase { CAST, WAIT, COLLECT }

    private record Cooking(JobSpec step, StationJob station, Node stand, int output, long readyAt, boolean seenLit) {
        BlockPos at() { return step.target(); }
        String item() { return step.ids().get(2); }
    }

    public record PreflightIssue(int step, String message, boolean blocking) { }

    public record Preflight(int steps, List<PreflightIssue> issues) {
        public Preflight { issues = List.copyOf(issues); }
    }

    private static final JobQueue INSTANCE = new JobQueue();
    public static final int MAX_STEPS = 16;
    private static final int MAX_APPROACHES = 5;
    private static final int ACK_TIMEOUT = 120;
    private static final int BREAK_TIMEOUT = 1_200;
    private static final int MAX_ACCESS_BREAKS = 96;
    private static final int MAX_ACCESS_REPLANS = 2;
    private static final int MAX_GATHER_STEPS_WITHOUT_OUTPUT = 512;
    private static final int MAX_UNKNOWN_STORAGE_CHECKS = 3;
    private static final Navigation.Options WALK = new Navigation.Options(
        new IncrementalSearch.Config(true, true, 1, 128, 16_000, false, IncrementalSearch.SearchMode.QUICK), 256, true, true);
    private final Object owner = new Object();
    private final List<JobSpec> draft = new ArrayList<>();
    private ClientLevel draftLevel;
    private Preflight lastPreflight;
    private List<JobSpec> work = List.of();
    private Phase phase = Phase.IDLE;
    private Action action;
    private ClientLevel level;
    private LocalPlayer player;
    private MultiPlayerGameMode mode;
    private Node home;
    private WorldJob world;
    private StationJob station;
    private CampfireBatch campfire;
    private WorldJob.Target target;
    private BlockPos position;
    private ItemEntity drop;
    private Set<Integer> previousDrops = Set.of();
    private Map<String, Integer> pickupBaseline = Map.of();
    private List<Node> approaches = List.of();
    private AbstractContainerMenu menu;
    private QuarryExit quarryExit;
    private WorldJob.Target preparedQuarryTarget;
    private WorldJob.Target accessOre;
    private MiningApproach accessSearch;
    private List<MiningApproach.Edge> accessPath = List.of();
    private Node accessFrom;
    private RouteBlockProtection accessProtection;
    private int accessEdge, accessBreak, accessUsed, accessReplans;
    private GatherPlan gather;
    private GatherTrading gatherTrading;
    private GatherMobApproach interactionApproach;
    private MerchantTrade merchantTrade;
    private JobSpec merchantStep;
    private boolean merchantClicked;
    private Piglin barterTarget;
    private BreedJob breeding;
    private int barterGoldBefore = -1, barterFinishedAt = -1;
    private boolean barterSawAdmiring;
    private JobSpec gatherStep;
    private int gatherActions, gatheredCount;
    private String gatherLastResult;
    private final Map<JobSpec, String> gatherSourceKeys = new HashMap<>();
    private final Map<JobSpec, List<WorldJob.Target>> gatherBlockPlans = new HashMap<>();
    private final Map<JobSpec, List<Item>> gatherMiningTools = new HashMap<>();
    private final Map<JobSpec, Item> gatherMiningOutputs = new HashMap<>();
    private final Map<JobSpec, GatherFishing.Spot> gatherFishPlans = new HashMap<>();
    private final Set<String> gatherRejectedSources = new HashSet<>();
    private final Set<String> gatherVisitedStorage = new HashSet<>();
    private final List<Cooking> gatherFurnaces = new ArrayList<>();
    private Cooking gatherReturning;
    private JobSpec gatherLookaheadStep;
    private boolean checkedCookingLookahead;
    private Map<String, Integer> gatherBefore = Map.of();
    private Map<String, Integer> gatherHotbarBefore = Map.of();
    private GatherHunt hunt;
    private Mob prey;
    private BlockPos huntDestination;
    private boolean huntWalking;
    private List<Node> huntApproaches = List.of();
    private int nextHuntApproach, huntProgressTick, huntPathTick;
    private double huntBestDistance;
    private GatherFluid gatherFluid;
    private GatherFishing gatherFishing;
    private GatherFishing.Spot fishingSpot;
    private FishingHook fishingHook;
    private FishPhase fishPhase;
    private InteractionHand fishingHand;
    private int fishingBefore;
    private boolean fishingReachedWater;
    private Item bucketResult, bucketInput;
    private int bucketBefore;
    private ItemStack swappedHeld = ItemStack.EMPTY;
    private Item swappedItem;
    private int selectedBefore = -1, swappedSlot = -1, activeSlot = -1;
    private int index, nextApproach, ticks, ack = -1, sequence = -1, dropDeadline;
    private boolean recording, mining, aimed, aiming, plannerHeld, sawDrop;
    private float yaw, pitch;
    private String status = "No job queue is running.";
    private String prePlannerStatus = "";

    private JobQueue() { }

    public static JobQueue get() { return INSTANCE; }
    public void initialize() { ClientEvents.subscribe(this); }
    public boolean active() { return phase != Phase.IDLE; }
    public boolean ownsNavigation(Object token) { return token == owner; }
    public String statusText() { return status; }
    public List<String> steps() {
        List<JobSpec> shown = active() ? work : draftLevel == Minecraft.getInstance().level ? draft : List.of();
        return shown.stream().map(JobSpec::command).toList();
    }
    public int currentStepIndex() { return active() && index >= 0 && index < work.size() ? index : -1; }

    public void enqueue(String spec, MapVolume.Box region, BlockPos target) {
        if (active()) throw new IllegalStateException("Stop the running job queue before editing it.");
        Minecraft mc = Minecraft.getInstance();
        ClientLevel current = mc.level;
        if (current == null || mc.player == null) throw new IllegalStateException("Join a world before queuing jobs.");
        JobSpec step = JobSpec.parse(spec, region, target, mc.player.blockPosition());
        if (draftLevel != current) draft.clear();
        if (draft.size() == MAX_STEPS) throw new IllegalStateException("The queue already has " + MAX_STEPS + " steps.");
        draftLevel = current;
        draft.add(step);
        lastPreflight = null;
        status = "Queued " + draft.size() + " step" + (draft.size() == 1 ? "." : "s.");
    }

    public TaskHistory.Task taskSnapshot(String dimension) {
        if (draft.isEmpty() || draftLevel != Minecraft.getInstance().level) {
            throw new IllegalStateException("Queue at least one step in this world.");
        }
        return taskSnapshot(draft, dimension);
    }

    private static TaskHistory.Task taskSnapshot(List<JobSpec> steps, String dimension) {
        var settings = new HashMap<String, String>();
        for (int i = 0; i < steps.size(); i++) settings.put("step" + (i + 1), steps.get(i).encode());
        String title = steps.size() + " step" + (steps.size() == 1 ? "" : "s") + ", starting with " + steps.getFirst().command();
        return new TaskHistory.Task("job-queue", dimension, title, settings);
    }

    public void restore(TaskHistory.Task saved) {
        if (active()) throw new IllegalStateException("Stop the running queue before reopening a saved one.");
        ClientLevel current = Minecraft.getInstance().level;
        if (!saved.kind().equals("job-queue") || current == null
            || !current.dimension().identifier().toString().equals(saved.dimension())) {
            throw new IllegalArgumentException("Open the saved queue in its original dimension.");
        }
        if (saved.settings().isEmpty() || saved.settings().size() > MAX_STEPS) throw new IllegalArgumentException("Invalid saved queue length.");
        var restored = new ArrayList<JobSpec>();
        for (int i = 1; i <= saved.settings().size(); i++) restored.add(JobSpec.decode(saved.settings().get("step" + i)));
        draftLevel = current;
        draft.clear();
        draft.addAll(restored);
        lastPreflight = null;
        status = "Reopened " + restored.size() + " saved steps. Start when ready.";
    }

    public void clear() {
        if (active()) throw new IllegalStateException("Stop the running job queue before clearing it.");
        draft.clear();
        lastPreflight = null;
        status = "Job queue cleared.";
    }

    public Preflight lastPreflight() {
        return draftLevel == Minecraft.getInstance().level ? lastPreflight : null;
    }

    public Preflight preflight() {
        Minecraft mc = Minecraft.getInstance();
        requireDraft(mc);
        return inspectDraft(mc, new MinecraftTerrain(mc.level, mc.player).startAtPlayer());
    }

    private Preflight inspectDraft(Minecraft mc, Node start) {
        return lastPreflight = new Preflight(draft.size(), JobPreflight.inspect(draft, mc.level, mc.player, mc.gameMode, start));
    }

    private void requireDraft(Minecraft mc) {
        if (active()) throw new IllegalStateException("A job queue is already running.");
        if (draft.isEmpty() || draftLevel != mc.level) throw new IllegalStateException("Queue at least one step in this world.");
        if (mc.player == null || mc.gameMode == null || mc.player.isSpectator() || !mc.player.mayBuild()) {
            throw new IllegalStateException("Join a world where you can build first.");
        }
    }

    public void start() {
        Minecraft mc = Minecraft.getInstance();
        requireDraft(mc);
        start(mc, draft, true);
    }

    public TaskHistory.Task startSingle(String command, MapVolume.Box region, BlockPos target) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) throw new IllegalStateException("Join a world before starting a job.");
        List<JobSpec> steps = List.of(JobSpec.parse(command, region, target, mc.player.blockPosition()));
        start(mc, steps, false);
        return taskSnapshot(steps, mc.level.dimension().identifier().toString());
    }

    public TaskHistory.Task startBatch(List<String> commands) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) throw new IllegalStateException("Join a world before starting jobs.");
        if (commands.isEmpty() || commands.size() > MAX_STEPS) {
            throw new IllegalArgumentException("Choose between 1 and " + MAX_STEPS + " jobs.");
        }
        BlockPos origin = mc.player.blockPosition();
        List<JobSpec> steps = commands.stream().map(command -> JobSpec.parse(command, null, null, origin)).toList();
        start(mc, steps, false);
        return taskSnapshot(steps, mc.level.dimension().identifier().toString());
    }

    private void start(Minecraft mc, List<JobSpec> steps, boolean fromDraft) {
        if (active()) throw new IllegalStateException("A job queue is already running.");
        if (mc.player == null || mc.gameMode == null || mc.player.isSpectator() || !mc.player.mayBuild()) {
            throw new IllegalStateException("Join a world where you can build first.");
        }
        if (mc.gui.screen() != null && !(mc.gui.screen() instanceof PlanningScreen) && !(mc.gui.screen() instanceof ChatScreen)) {
            throw new IllegalStateException("Close the current screen before starting the queue.");
        }
        if (Navigation.get().movementReservedByOther(owner) || ReplaceActivity.get().active()
            || FollowController.get().active() || BlueprintConstruction.get().active()) {
            throw new IllegalStateException("Stop the active route or job first.");
        }
        Node start = new MinecraftTerrain(mc.level, mc.player).startAtPlayer();
        if (start == null || !mc.player.onGround()) throw new IllegalStateException("Start from confirmed safe footing.");
        List<PreflightIssue> issues = fromDraft ? inspectDraft(mc, start).issues()
            : JobPreflight.inspect(steps, mc.level, mc.player, mc.gameMode, start);
        for (PreflightIssue issue : issues) {
            if (issue.blocking()) throw new IllegalStateException("Queue step " + issue.step() + ": " + issue.message());
        }
        level = mc.level;
        player = mc.player;
        mode = mc.gameMode;
        home = start;
        work = List.copyOf(steps);
        index = -1;
        ack = -1;
        enter(Phase.ARMED, "Starting " + work.getFirst().command() + ".");
    }

    public void stop() {
        if (active()) finish("Job queue stopped.");
    }

    public boolean ownsBreaking() {
        Minecraft mc = Minecraft.getInstance();
        return mining && phase == Phase.BREAK && sameWorld(mc) && mc.gui.screen() == null
            && mc.isWindowActive() && !mc.options.keyAttack.isDown() && !mc.options.keyUse.isDown();
    }

    public void acknowledge(ClientLevel appliedLevel, int acknowledged) {
        if (active() && level == appliedLevel) ack = Math.max(ack, acknowledged);
    }

    @Override
    public void packetSending(Connection source, Packet<?> outbound) {
        if (!recording || !active()) return;
        if (outbound instanceof ServerboundPlayerActionPacket packet && packet.getPos().equals(position)
            && (packet.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
            || packet.getAction() == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK)) {
            sequence = Math.max(sequence, packet.getSequence());
        } else if (outbound instanceof ServerboundUseItemOnPacket packet && action == Action.PLANT) {
            sequence = Math.max(sequence, packet.sequence());
        } else if (outbound instanceof ServerboundUseItemPacket packet && action == Action.SCOOP) {
            sequence = Math.max(sequence, packet.sequence());
        }
    }

    @Override
    public void mouseClick(ClientEvents.Action action) {
        if (!active() || phase == Phase.ARMED || phase == Phase.WALK || phase == Phase.NEXT || phase == Phase.PAUSED_TOOL) return;
        if (Minecraft.getInstance().gui.screen() instanceof PlanningScreen) return;
        if (action == ClientEvents.Action.PRESS) {
            // Process the player's click after releasing all job-owned input.
            finish("You took control of the job.", false);
        }
    }

    @Override
    public void screenChanged(Screen screen) {
        if (active() && (screen instanceof PlanningScreen || screen instanceof ChatScreen)
            && phase != Phase.ARMED && !menuPhase() && phase != Phase.PAUSED_TOOL
            && sameWorld(Minecraft.getInstance())) holdForPlanner();
    }

    @Override
    public void disconnected() {
        if (active()) finish("Left the world.", false);
        draftLevel = null;
        draft.clear();
        lastPreflight = null;
    }

    @Override
    public void tickPost() {
        if (!active()) return;
        Minecraft mc = Minecraft.getInstance();
        if (!sameWorld(mc)) { finish("World or player changed.", false); return; }
        if (Navigation.get().movementReservedByOther(owner)) { finish("Another tool took control of movement."); return; }
        if (phase == Phase.PAUSED_TOOL && (mc.gui.screen() != null || !mc.isWindowActive()
            || SopranoClient.manualInteractionHeld(mc.options) || player.isUsingItem())) return;
        if (phase == Phase.ARMED && (mc.gui.screen() instanceof PlanningScreen || mc.gui.screen() instanceof ChatScreen)) return;
        if ((mc.gui.screen() instanceof PlanningScreen || mc.gui.screen() instanceof ChatScreen) && !menuPhase()) {
            holdForPlanner();
            return;
        }
        if (mc.gui.screen() != null && !menuPhase()) {
            finish("A game screen interrupted the job.", false);
            return;
        }
        if (!mc.isWindowActive()) { finish("The job stopped because Minecraft lost focus.", false); return; }
        if (SopranoClient.manualInteractionHeld(mc.options) || movedView() || player.isUsingItem()) {
            finish("The job stopped because the player was controlled manually.", false);
            return;
        }
        if (player.isPassenger() || player.isFallFlying() || player.getAbilities().flying || player.isInWater() || player.isInLava()) {
            finish("The job needs normal ground controls.");
            return;
        }
        ticks++;
        try {
            if (plannerHeld) {
                plannerHeld = false;
                status = prePlannerStatus;
                if (phase == Phase.WALK) { nextStand(); return; }
            }
            switch (phase) {
                case ARMED -> enter(Phase.NEXT, "Starting the queue.");
                case NEXT -> nextJob(mc);
                case CHOOSE -> choose();
                case WALK -> walk();
                case AIM -> aimAction();
                case EXIT -> verifyExit();
                case BREAK -> mine();
                case PAUSED_TOOL -> resumeAfterTool();
                case CONFIRM -> confirm();
                case DROPS -> drops();
                case COOK_WAIT -> {
                    if (readyToReturn(gatherReturning)) beginAim();
                }
                case OPEN_MENU -> openMenu(mc);
                case MENU -> menu(mc);
                case CAMPFIRE -> advanceCampfire();
                case TRADE -> advanceMerchant(mc);
                case BARTER -> advanceBarter();
                case BREED -> {
                    boolean finished = breeding.tick();
                    if (finished) complete(breeding.status());
                    else describe(breeding.status());
                }
                case ACCESS_PLAN -> advanceAccessPlan();
                case ACCESS_STEP -> advanceAccessStep();
                case ACCESS_WALK -> advanceAccessWalk();
                case GATHER -> advanceGather(mc);
                case FISH -> advanceFish();
                case HUNT -> advanceHunt();
                case HUNT_DROPS -> {
                    if (hunt.shearing() && prey != null && GatherSources.eligibleShearing(prey)) {
                        if (ticks >= ACK_TIMEOUT) throw new IllegalStateException("The server did not confirm shearing.");
                    } else if (ticks >= 16) complete("Checking the mob's drops.");
                }
                case BUCKET_CONFIRM -> confirmBucket();
                case IDLE -> { }
            }
        } catch (IllegalArgumentException | IllegalStateException failure) {
            if (gatherStep != null && (menu == null || menu.getCarried().isEmpty())) settleGather(failure.getMessage(), true);
            else finish(failure.getMessage());
        }
    }

    private void holdForPlanner() {
        if (plannerHeld) return;
        if (phase == Phase.WALK) {
            Navigation.get().cancelOwned(owner);
            nextApproach = Math.max(0, nextApproach - 1);
        } else if (phase == Phase.ACCESS_WALK) {
            Navigation.get().cancelOwned(owner);
            planAccess();
        } else if (phase == Phase.HUNT) {
            stopHuntWalk();
            huntDestination = null;
        } else if (interactionApproach != null) {
            interactionApproach.suspend();
        } else if (breeding != null) {
            breeding.suspend();
        } else if (phase == Phase.BREAK) {
            if (level.getBlockState(position) != target.expected()) beginConfirm();
            else {
                if (mining && mode.isDestroying()) mode.stopDestroyBlock();
                mining = false;
                beginAim();
            }
        } else if (phase == Phase.EXIT) {
            quarryExit = null;
            beginAim();
        }
        aimed = aiming = false;
        prePlannerStatus = status;
        plannerHeld = true;
        describe("Paused while the planner or chat is open.");
    }

    private boolean sameWorld(Minecraft mc) {
        return level == mc.level && player == mc.player && mode == mc.gameMode && player != null && player.isAlive()
            && !player.isRemoved() && player.mayBuild() && !player.isSpectator();
    }

    private boolean movedView() {
        return aiming && (Math.abs(Mth.wrapDegrees(player.getYRot() - yaw)) > .4 || Math.abs(player.getXRot() - pitch) > .4);
    }

    private void nextJob(Minecraft mc) {
        if (++index == work.size()) { finish("Job queue finished. " + status); return; }
        JobSpec spec = work.get(index);
        if (spec.kind() == JobSpec.Kind.GATHER) {
            if (player.isCreative()) throw new IllegalStateException("Use survival to gather and craft resources.");
            startGather(spec);
        } else startStep(mc, spec);
    }

    private void startStep(Minecraft mc, JobSpec spec) {
        world = null;
        campfire = null;
        resetCommerce();
        breeding = null;
        station = gatherReturning == null ? null : gatherReturning.station();
        checkedCookingLookahead = gatherReturning != null;
        hunt = null;
        prey = null;
        huntWalking = false;
        huntApproaches = List.of();
        huntDestination = null;
        bucketResult = bucketInput = null;
        target = null;
        drop = null;
        previousDrops = Set.of();
        pickupBaseline = Map.of();
        if (spec.kind() == JobSpec.Kind.CRAFT || spec.kind() == JobSpec.Kind.FURNACE || spec.kind() == JobSpec.Kind.STONECUT
            || spec.kind() == JobSpec.Kind.SMITH || spec.kind() == JobSpec.Kind.BREW || spec.kind() == JobSpec.Kind.ENCHANT
            || spec.kind() == JobSpec.Kind.RESTOCK || spec.kind() == JobSpec.Kind.UNLOAD || spec.kind() == JobSpec.Kind.TAKE) {
            if (spec.kind() == JobSpec.Kind.TAKE && gatherStep == null) throw new IllegalArgumentException("Use gather to retrieve supplies from storage.");
            if (station == null) station = new StationJob(spec, level, player, mode, gatherStep != null);
            if (station.inventoryCrafting()) {
                if (player.isCreative()) throw new IllegalStateException("Select a crafting table while in creative mode.");
                if (player.containerMenu != player.inventoryMenu) throw new IllegalStateException("Close the current container.");
                mc.gui.setScreen(new InventoryScreen(player));
                menu = player.inventoryMenu;
                station.opened();
                enter(Phase.MENU, "Preparing inventory crafting.");
                return;
            }
        } else if (spec.kind() == JobSpec.Kind.CAMPFIRE) {
            if (player.isCreative()) throw new IllegalStateException("Use survival to cook on a campfire.");
            campfire = new CampfireBatch(spec, level, player);
            approach(Action.CAMPFIRE, spec.target());
            return;
        } else if (spec.kind() == JobSpec.Kind.FISH) {
            if (gatherStep == null) throw new IllegalArgumentException("Use gather with an item that can be caught while fishing.");
            startFishing(spec);
            return;
        } else if (spec.kind() == JobSpec.Kind.TRADE) {
            startMerchant(spec);
            return;
        } else if (spec.kind() == JobSpec.Kind.BARTER) {
            if (gatherStep == null) throw new IllegalArgumentException("Use gather with an item obtained by bartering.");
            startBarter(spec);
            return;
        } else if (spec.kind() == JobSpec.Kind.BREED) {
            if (player.isCreative()) throw new IllegalStateException("Use survival to breed animals.");
            breeding = new BreedJob(level, player, mode, spec, owner, WALK, this::equipBreedingFood);
            enter(Phase.BREED, "Looking for a pair of adults.");
            return;
        } else if (spec.kind() == JobSpec.Kind.HUNT || spec.kind() == JobSpec.Kind.CAPTURE) {
            if (gatherStep == null) throw new IllegalArgumentException("Use gather with the item you want to obtain from a mob.");
            if (spec.kind() == JobSpec.Kind.CAPTURE) {
                bucketResult = InventoryJobs.item(spec.ids().getFirst());
                bucketInput = GatherHunt.captureIngredient(bucketResult);
                if (bucketInput == null) throw new IllegalArgumentException("This item has no supported mob interaction.");
                requireBucketSupply();
            }
            hunt = new GatherHunt(spec, level, player, mode);
            enter(Phase.HUNT, "Looking for an eligible mob.");
            return;
        } else if (spec.kind() == JobSpec.Kind.SETUP) {
            if (gatherStep == null) throw new IllegalArgumentException("Use gather to prepare a crafting station.");
            setupStation(InventoryJobs.item(spec.ids().getFirst()));
            return;
        } else if (spec.kind() == JobSpec.Kind.FILLBUCKET) {
            if (gatherStep == null) throw new IllegalArgumentException("Use gather to fill a bucket.");
            bucketResult = InventoryJobs.item(spec.ids().getFirst());
            bucketInput = Items.BUCKET;
            requireBucketSupply();
            approach(Action.SCOOP, spec.target());
            return;
        } else {
            world = new WorldJob(spec, level, player, gatherBlockPlans.get(spec));
            if (player.isCreative() && world.collectAfterBreak()) {
                throw new IllegalStateException("Switch to survival to harvest drops from blocks.");
            }
            if (spec.kind() == JobSpec.Kind.RETURN) {
                walkTo(Action.RETURN, home);
                return;
            }
            if (spec.kind() == JobSpec.Kind.QUARRY && spec.region().contains(home.x(), (int) Math.floor(home.y()) - 1, home.z())) {
                throw new IllegalStateException("Start the queue with footing outside the quarry selection.");
            }
        }
        enter(Phase.CHOOSE, "Finding the next target.");
    }

    private void setupStation(Item item) {
        if (!(item instanceof BlockItem supply) || !(supply.getBlock() instanceof CraftingTableBlock
            || supply.getBlock() instanceof AbstractFurnaceBlock || supply.getBlock() instanceof StonecutterBlock
            || supply.getBlock() instanceof SmithingTableBlock)) {
            throw new IllegalArgumentException("Unsupported gathering station.");
        }
        if (InventoryJobs.count(player, InventoryJobs.itemId(item)) < 1) throw new IllegalStateException("The station item is missing.");
        var rules = Navigation.activeExclusions(false);
        var footprint = new RouteExclusions.Footprint(1, 1);
        var state = supply.getBlock().defaultBlockState();
        BlockPos origin = player.blockPosition();
        action = Action.PLANT;
        for (int radius = 1; radius <= 3; radius++) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                position = origin.relative(direction, radius);
                BlockPos floor = position.below();
                if (!WorldJob.loaded(level, position) || !WorldJob.loaded(level, floor)
                    || !level.getWorldBorder().isWithinBounds(position) || !level.getBlockState(position).isAir()
                    || rules.blockingAt(new RouteExclusions.Point(position.getX() + .5, position.getY(), position.getZ() + .5), footprint).isPresent()
                    || !level.getBlockState(floor).isCollisionShapeFullBlock(level, floor)
                    || MinecraftTerrain.inherentHazard(level.getBlockState(floor))
                    || player.getBoundingBox().inflate(.1).intersects(new AABB(position))
                    || !state.canSurvive(level, position) || !level.isUnobstructed(state, position, CollisionContext.of(player))) continue;
                target = new WorldJob.Target(position, null, item, Direction.UP);
                if (currentHit() != null) { beginAim(); return; }
            }
        }
        throw new IllegalStateException("There's no clear spot in reach for the crafting station.");
    }

    private void startGather(JobSpec spec) {
        String item = InventoryJobs.itemId(InventoryJobs.gatherItem(spec.ids().getFirst()));
        gatherActions = 0;
        gatheredCount = InventoryJobs.count(player, item);
        gatherLastResult = null;
        gatherRejectedSources.clear();
        gatherVisitedStorage.clear();
        gatherFluid = new GatherFluid(level, player);
        gatherFishing = new GatherFishing(level, player);
        gatherTrading = new GatherTrading(level, player);
        Item wanted = InventoryJobs.item(item);
        gather = new GatherPlan(level, player, spec.target(), item, spec.limit(),
            (id, deficit) -> gatherSource(spec.target(), id, deficit),
            step -> gatherMiningTools.getOrDefault(step, List.of()), this::scoutStorage, result -> bucketSource(spec.target(), result),
            this::cookedMobSource, this::fishingSource,
            (result, deficit) -> gatherTrading.opportunities(result, deficit, result == wanted || result == Items.EMERALD,
                gatherRejectedSources::contains), offer -> gatherSourceKeys.put(offer.action(), offer.key()));
        enter(Phase.GATHER, "Gathering " + spec.limit() + " " + item + ".");
    }

    private JobSpec gatherSource(BlockPos origin, String item, int amount) {
        JobSpec stored = GatherStorage.findKnown(level, player, item, amount, gatherRejectedSources::contains,
            this::gatherFurnaceOccupied);
        if (stored != null) return rememberStorageStep(item, stored);
        Item output = InventoryJobs.item(item);
        var sources = GatherSources.find(level, player, origin, output, amount,
            SopranoClient.module().jobLookahead(), gatherRejectedSources::contains);
        if (sources.isEmpty()) return null;
        var selected = sources.getFirst();
        if (selected.step().kind() == JobSpec.Kind.MINE) gatherMiningOutputs.put(selected.step(), output);
        return rememberSourceStep(selected);
    }

    private JobSpec cookedMobSource(Item raw, Item cooked, int amount) {
        return rememberSourceStep(GatherSources.findCooked(level, player, raw, cooked, amount, gatherRejectedSources::contains));
    }

    private JobSpec fishingSource(Item item) {
        GatherFishing.Spot spot = gatherFishing.find(item, gatherRejectedSources::contains);
        if (spot == null) return null;
        JobSpec step = JobSpec.parse("fish " + InventoryJobs.itemId(item), null, spot.water(), null);
        gatherSourceKeys.put(step, spot.key());
        gatherFishPlans.put(step, spot);
        return step;
    }

    private JobSpec rememberSourceStep(GatherSources.Source source) {
        if (source == null) return null;
        gatherSourceKeys.put(source.step(), source.key());
        if (!source.prepared().isEmpty()) gatherBlockPlans.put(source.step(), source.prepared());
        if (!source.tools().isEmpty()) gatherMiningTools.put(source.step(), source.tools());
        return source.step();
    }

    private JobSpec scoutStorage(String item, int amount) {
        if (gatherVisitedStorage.size() >= MAX_UNKNOWN_STORAGE_CHECKS) return null;
        JobSpec stored = GatherStorage.findUnknown(level, player, item, amount,
            gatherRejectedSources::contains, gatherVisitedStorage::contains, this::gatherFurnaceOccupied);
        return rememberStorageStep(item, stored);
    }

    private JobSpec rememberStorageStep(String item, JobSpec stored) {
        if (stored != null) gatherSourceKeys.put(stored, GatherStorage.key(level, item, stored.target()));
        return stored;
    }

    private JobSpec bucketSource(BlockPos origin, Item result) {
        String id = InventoryJobs.itemId(result);
        if (GatherHunt.captureIngredient(result) != null) {
            JobSpec step = JobSpec.parse("capture " + id + " 24", null, null, origin);
            return new GatherHunt(step, level, player, mode).nearest() == null ? null : step;
        }
        if (!GatherFluid.supports(result)) return null;
        BlockPos source = gatherFluid.nearest(result, origin, at -> gatherRejectedSources.contains(fluidKey(id, at)));
        if (source == null) return null;
        JobSpec step = JobSpec.parse("fillbucket " + id, null, source, null);
        gatherSourceKeys.put(step, fluidKey(id, source));
        return step;
    }

    private static String fluidKey(String item, BlockPos at) { return "fluid:" + at.asLong() + ":" + item; }

    private boolean gatherFurnaceOccupied(BlockPos at) {
        return gatherFurnaces.stream().anyMatch(batch -> batch.at().equals(at));
    }

    private JobSpec planWhileCooking() {
        gatherSourceKeys.clear();
        gatherBlockPlans.clear();
        gatherMiningTools.clear();
        gatherMiningOutputs.clear();
        gatherFishPlans.clear();
        var expected = new HashMap<String, Integer>();
        var occupied = new HashSet<BlockPos>();
        for (Cooking batch : gatherFurnaces) {
            expected.merge(batch.item(), batch.output(), Integer::sum);
            occupied.add(batch.at());
        }
        return gather.nextWhileSmelting(expected, occupied);
    }

    private boolean readyToReturn(Cooking batch) {
        if (level.getGameTime() >= batch.readyAt()) return true;
        if (!WorldJob.loaded(level, batch.at())) return false;
        // A furnace that went out may have finished, stalled, or lost its fuel.
        var state = level.getBlockState(batch.at());
        return !(state.getBlock() instanceof AbstractFurnaceBlock)
            || batch.seenLit() && !state.getValueOrElse(BlockStateProperties.LIT, true);
    }

    private void considerLeavingFurnace() {
        if (checkedCookingLookahead) return;
        checkedCookingLookahead = true;
        int lookahead = SopranoClient.module().jobLookahead();
        if (gatherFurnaces.size() >= lookahead) return;
        Node stand = new MinecraftTerrain(level, player).startAtPlayer();
        if (stand == null) return;
        Cooking batch = new Cooking(gatherStep, station, stand, station.remainingCookOutput(),
            level.getGameTime() + station.remainingCookTicks(), level.getBlockState(position).getValueOrElse(BlockStateProperties.LIT, false));
        boolean otherBatchReady = gatherFurnaces.stream().anyMatch(this::readyToReturn);
        gatherFurnaces.add(batch);
        try {
            if (!otherBatchReady) {
                gatherLookaheadStep = planWhileCooking();
                if (gatherLookaheadStep == null) {
                    gatherFurnaces.removeLast();
                    return;
                }
            }
            station.detachFurnace();
        } catch (IllegalArgumentException | IllegalStateException failure) {
            gatherFurnaces.removeLast();
            gatherLookaheadStep = null;
            throw failure;
        }
        closeMenu();
        gatherStep = null;
        gatherBefore = Map.of();
        gatherHotbarBefore = Map.of();
        enter(Phase.GATHER, "The furnace is cooking. Looking for the next ingredients.");
    }

    private void beginGatherStep(Minecraft mc, JobSpec step) {
        gatherBefore = InventoryJobs.playerCounts(player);
        gatherHotbarBefore = GatherInventory.hotbarCounts(player);
        gatherStep = step;
        startStep(mc, step);
    }

    private void advanceGather(Minecraft mc) {
        JobSpec order = work.get(index);
        String item = InventoryJobs.itemId(InventoryJobs.gatherItem(order.ids().getFirst()));
        int currentCount = InventoryJobs.count(player, item);
        if (currentCount > gatheredCount) {
            gatheredCount = currentCount;
            gatherActions = 0;
        }
        JobSpec step;
        if (!gatherFurnaces.isEmpty()) {
            Cooking next = gatherFurnaces.stream().min(Comparator.comparingLong(Cooking::readyAt)).orElseThrow();
            Cooking ready = gatherFurnaces.stream().filter(this::readyToReturn).min(Comparator.comparingLong(Cooking::readyAt)).orElse(null);
            step = gatherLookaheadStep;
            gatherLookaheadStep = null;
            if (ready == null && step == null) step = planWhileCooking();
            if (ready != null || step == null) {
                gatherReturning = ready == null ? next : ready;
                beginGatherStep(mc, gatherReturning.step());
                return;
            }
        } else {
            gatherSourceKeys.clear();
            gatherBlockPlans.clear();
            gatherMiningTools.clear();
            gatherMiningOutputs.clear();
            gatherFishPlans.clear();
            try {
                step = gather.next();
            } catch (IllegalStateException blocked) {
                String message = "Gathered " + currentCount + " / " + order.limit() + " " + item + ". " + blocked.getMessage();
                throw new IllegalStateException(gatherLastResult == null ? message : message + " " + gatherLastResult);
            }
        }
        if (step == null) {
            gather = null;
            complete("Inventory contains " + currentCount + " " + item + ".");
            return;
        }
        if (++gatherActions > MAX_GATHER_STEPS_WITHOUT_OUTPUT) {
            throw new IllegalStateException("Gather stopped after repeated actions without producing "
                + item + ". Inventory has " + currentCount + " / " + order.limit() + ".");
        }
        beginGatherStep(mc, step);
    }

    private void settleGather(String reason, boolean failed) {
        if (mining && mode.isDestroying()) mode.stopDestroyBlock();
        mining = false;
        stopFishing();
        resetCommerce();
        restoreHeld();
        closeMenu();
        Navigation.get().cancelOwned(owner);
        clearAccess();
        aiming = aimed = false;
        var after = InventoryJobs.playerCounts(player);
        boolean progressed = !gatherBefore.equals(after);
        if (progressed) GatherInventory.promoteGains(player, mode, gatherBefore, gatherHotbarBefore, after);
        if (gatherReturning != null) {
            if (failed) reason = "Furnace at " + gatherReturning.at().toShortString() + ". " + reason;
            gatherFurnaces.remove(gatherReturning);
            gatherReturning = null;
        }
        if (failed || !progressed && gatherStep.kind() != JobSpec.Kind.HUNT) {
            String key = gatherSourceKeys.get(gatherStep);
            if (key != null) gatherRejectedSources.add(key);
            else gather.reject(gatherStep);
            gatherLastResult = reason;
        }
        gatherStep = null;
        gatherBefore = Map.of();
        gatherHotbarBefore = Map.of();
        enter(Phase.GATHER, reason);
    }

    private boolean menuPhase() {
        return phase == Phase.MENU || phase == Phase.OPEN_MENU || phase == Phase.TRADE && merchantClicked;
    }

    private void startMerchant(JobSpec spec) {
        if (player.isCreative()) throw new IllegalStateException("Use survival to trade with merchants.");
        if (!(level.getEntity(spec.entityId()) instanceof AbstractVillager merchant) || merchant.isBaby() || !merchant.isAlive())
            throw new IllegalStateException("The selected merchant is no longer available.");
        if (gatherTrading == null) gatherTrading = new GatherTrading(level, player);
        gatherTrading.opening(spec.entityId());
        merchantStep = spec;
        interactionApproach = new GatherMobApproach(level, player, merchant, owner, WALK);
        enter(Phase.TRADE, "Walking to the merchant.");
    }

    private void advanceMerchant(Minecraft mc) {
        if (merchantTrade != null) {
            if (player.containerMenu != menu || mc.gui.screen() == null) throw new IllegalStateException("The merchant menu closed.");
            boolean finished = merchantTrade.tick();
            String detail = merchantTrade.status();
            if (finished) {
                closeMenu();
                resetCommerce();
                complete(detail);
            } else describe(detail);
            return;
        }
        if (merchantClicked) {
            if (mc.gui.screen() != null) {
                if (!(player.containerMenu instanceof MerchantMenu open)) throw new IllegalStateException("The merchant did not open a trade menu.");
                menu = open;
                merchantTrade = new MerchantTrade(player, mode, open, gatherTrading, merchantStep);
            } else if (ticks > ACK_TIMEOUT) throw new IllegalStateException("The merchant did not open a trade menu.");
            return;
        }
        if (ticks > 600) throw new IllegalStateException("Could not reach the merchant.");
        var hit = interactionApproach.tick();
        if (hit == null) return;
        merchantClicked = true;
        mode.interact(player, hit.getEntity(), hit, InteractionHand.MAIN_HAND);
        enter(Phase.TRADE, "Opening the merchant's offers.");
    }

    private void resetCommerce() {
        if (interactionApproach != null) interactionApproach.cancel();
        interactionApproach = null;
        merchantTrade = null;
        merchantStep = null;
        merchantClicked = false;
        barterTarget = null;
        barterGoldBefore = barterFinishedAt = -1;
        barterSawAdmiring = false;
    }

    private void startBarter(JobSpec spec) {
        if (!(level.getEntity(spec.entityId()) instanceof Piglin piglin) || !GatherTrading.canOffer(piglin))
            throw new IllegalStateException("The selected piglin is no longer available.");
        barterTarget = piglin;
        interactionApproach = new GatherMobApproach(level, player, piglin, owner, WALK);
        enter(Phase.BARTER, "Walking to an adult piglin.");
    }

    private void advanceBarter() {
        if (!barterTarget.isAlive() || barterTarget.isRemoved() || !PiglinAi.isWearingSafeArmor(player)
            || barterTarget.isAggressive() || !WorldJob.loaded(level, barterTarget.blockPosition()))
            throw new IllegalStateException("The selected piglin is no longer safe to approach.");
        String gold = InventoryJobs.itemId(Items.GOLD_INGOT);
        if (barterGoldBefore < 0) {
            if (ticks > 600 || !GatherTrading.canOffer(barterTarget)) throw new IllegalStateException("Could not reach a piglin ready to barter.");
            var hit = interactionApproach.tick();
            if (hit == null) return;
            int count = InventoryJobs.count(player, gold);
            if (count < 1) throw new IllegalStateException("Bartering requires a gold ingot.");
            if (!player.getMainHandItem().is(Items.GOLD_INGOT)) {
                if (!selectItem(Items.GOLD_INGOT)) throw new IllegalStateException("Could not equip a gold ingot.");
                if (swappedSlot >= 0) swappedItem = Items.GOLD_INGOT;
                return;
            }
            barterGoldBefore = count;
            mode.interact(player, barterTarget, hit, InteractionHand.MAIN_HAND);
            enter(Phase.BARTER, "Offering one gold ingot.");
            return;
        }
        if (ticks > 320) throw new IllegalStateException("The piglin did not finish the barter.");
        if (InventoryJobs.count(player, gold) >= barterGoldBefore) {
            if (ticks > ACK_TIMEOUT) throw new IllegalStateException("The piglin did not accept the gold ingot.");
            return;
        }
        if (GatherTrading.admiring(barterTarget)) {
            barterSawAdmiring = true;
            describe("Waiting for the piglin to toss its barter.");
            return;
        }
        if (barterSawAdmiring || ticks >= 120) {
            if (barterFinishedAt < 0) barterFinishedAt = ticks;
            else if (ticks - barterFinishedAt >= 4) complete("Checking the piglin's barter drops.");
        }
    }

    private void startFishing(JobSpec spec) {
        fishingSpot = gatherFishPlans.get(spec);
        if (fishingSpot == null || player.fishing != null) throw new IllegalStateException("The fishing spot or your rod is busy.");
        fishingBefore = InventoryJobs.count(player, spec.ids().getFirst());
        if (player.onGround() && gatherFishing.aim(fishingSpot.water(), player.getEyePosition()) != null) beginCast();
        else walkTo(Action.FISH, fishingSpot.stand());
    }

    private void beginCast() {
        aimed = false;
        fishingReachedWater = false;
        fishPhase = FishPhase.CAST;
        enter(Phase.FISH, "Aiming the rod at " + fishingSpot.water().toShortString() + ".");
    }

    private void advanceFish() {
        if (fishPhase == FishPhase.CAST) {
            if (player.fishing != null) throw new IllegalStateException("There is already a fishing line in use.");
            if (!player.onGround()) {
                if (ticks > 40) throw new IllegalStateException("Lost safe fishing footing.");
                return;
            }
            var inventory = player.getInventory();
            Item wanted = InventoryJobs.item(gatherStep.ids().getFirst());
            if (inventory.getFreeSlot() < 0 && inventory.getSlotWithRemainingSpace(wanted.getDefaultInstance()) < 0) {
                throw new IllegalStateException("There is no inventory space for " + gatherStep.ids().getFirst() + ".");
            }
            var aim = gatherFishing.aim(fishingSpot.water(), player.getEyePosition());
            if (aim == null || ticks > 40) throw new IllegalStateException("The fishing spot lost a clear cast.");
            if (!face(player.getEyePosition().add(aim.look()))) return;
            fishingHand = fishingRodHand();
            useFishingRod();
            aiming = false;
            fishPhase = FishPhase.WAIT;
            enter(Phase.FISH, "Waiting for a bite.");
            return;
        }
        if (fishPhase == FishPhase.COLLECT) {
            String item = gatherStep.ids().getFirst();
            if (player.fishing == null) {
                fishingHook = null;
                if (InventoryJobs.count(player, item) > fishingBefore) {
                    complete("Caught " + item + ".");
                    return;
                }
                if (ticks >= 45) {
                    var nearby = new AABB(player.blockPosition()).minmax(new AABB(fishingSpot.water())).inflate(3);
                    if (!level.getEntitiesOfClass(ItemEntity.class, nearby,
                        entity -> entity.isAlive() && InventoryJobs.itemId(entity.getItem()).equals(item)).isEmpty()) {
                        complete("Checking " + item + " from fishing.");
                    } else beginCast();
                    return;
                }
            }
            if (ticks >= ACK_TIMEOUT) throw new IllegalStateException("The server did not confirm reeling in the line.");
            return;
        }
        if (player.fishing == null) {
            if (fishingHook != null || ticks >= ACK_TIMEOUT) throw new IllegalStateException("Lost the fishing line.");
            return;
        }
        fishingHook = player.fishing;
        var hooked = fishingHook.getHookedIn();
        if (hooked != null && !(hooked instanceof ItemEntity)) throw new IllegalStateException("The fishing line caught an entity.");
        fishingReachedWater |= fishingHook.isInWater() || level.getFluidState(fishingHook.blockPosition()).is(FluidTags.WATER);
        if ((!fishingReachedWater && ticks >= ACK_TIMEOUT) || ticks >= 2_400) {
            throw new IllegalStateException(fishingReachedWater ? "No fishing bite arrived." : "The bobber did not reach the water.");
        }
        if (hooked instanceof ItemEntity || ((FishingHookAccessor) fishingHook).soprano$biting()) {
            useFishingRod();
            fishPhase = FishPhase.COLLECT;
            enter(Phase.FISH, "Reeling in and waiting for the catch.");
        }
    }

    private InteractionHand fishingRodHand() {
        if (GatherFishing.usableRod(player.getMainHandItem())) return InteractionHand.MAIN_HAND;
        if (GatherFishing.usableRod(player.getOffhandItem())) return InteractionHand.OFF_HAND;
        int slot = GatherFishing.rodSlot(player);
        if (slot < 0) throw new IllegalStateException("No fishing rod above the durability reserve.");
        restoreHeld();
        slot = GatherFishing.rodSlot(player);
        if (slot < 0) throw new IllegalStateException("No fishing rod above the durability reserve.");
        equipHuntItem(slot);
        return InteractionHand.MAIN_HAND;
    }

    private void useFishingRod() {
        if (!player.getItemInHand(fishingHand).is(Items.FISHING_ROD) || !mode.useItem(player, fishingHand).consumesAction()) {
            throw new IllegalStateException("Could not use the fishing rod.");
        }
        player.swing(fishingHand, SwingAnimation.DEFAULT, false);
    }

    private void stopFishing() {
        if (fishingHook != null && fishPhase == FishPhase.WAIT && fishingHand != null
            && sameWorld(Minecraft.getInstance()) && player.fishing == fishingHook && fishingHook.getHookedIn() == null
            && player.getItemInHand(fishingHand).is(Items.FISHING_ROD)) mode.useItem(player, fishingHand);
        fishingSpot = null;
        fishingHook = null;
        fishingHand = null;
        fishPhase = null;
    }

    private void advanceHunt() {
        if (player.getHealth() <= 8) { finish("Gather stopped hunting because health fell to 4 hearts."); return; }
        if (prey != null && prey.isDeadOrDying()) {
            stopHuntWalk();
            aiming = false;
            enter(Phase.HUNT_DROPS, "Waiting for mob drops.");
            return;
        }
        if (!hunt.eligible(prey)) {
            stopHuntWalk();
            prey = hunt.nearest();
            if (prey == null) throw new IllegalStateException("No eligible mob is reachable in the loaded area.");
            huntDestination = null;
            huntBestDistance = Double.POSITIVE_INFINITY;
            huntProgressTick = ticks;
            if (hunt.shearing()) equipShears();
            else if (bucketResult == null) equipHuntWeapon();
        }
        double distance = Math.sqrt(prey.distanceToSqr(player));
        if (distance < huntBestDistance - .25) {
            huntBestDistance = distance;
            huntProgressTick = ticks;
        }
        if (ticks - huntProgressTick > 600) throw new IllegalStateException("Stopped chasing a mob after losing progress.");

        Vec3 point = player.onGround() ? hunt.aimPoint(prey, player.getEyePosition()) : null;
        if (point != null) {
            if (huntWalking) stopHuntWalk();
            face(point);
            if (hunt.shearing()) {
                if (!GatherHunt.usableShears(player.getMainHandItem())) equipShears();
                else if (hunt.capture(prey)) {
                    aiming = false;
                    enter(Phase.HUNT_DROPS, "Waiting for shearing drops.");
                    return;
                }
            } else if (bucketResult == null) {
                if (!hunt.heldWeaponUsable(prey)) equipHuntWeapon();
                else if (hunt.strike(prey)) huntProgressTick = ticks;
            } else if (holdBucket()) {
                bucketBefore = InventoryJobs.count(player, InventoryJobs.itemId(bucketResult));
                if (hunt.capture(prey)) {
                    aiming = false;
                    enter(Phase.BUCKET_CONFIRM, "Waiting for the filled bucket.");
                    return;
                }
            }
            String action = hunt.shearing() ? "Shearing " : bucketResult == null ? "Hunting " : "Approaching to use the bucket on ";
            describe(action + prey.getType().getDescription().getString() + ".");
            return;
        }
        aiming = aimed = false;
        BlockPos nextPosition = prey.blockPosition();
        boolean moved = huntDestination == null || nextPosition.distManhattan(huntDestination) >= 3;
        if (huntWalking) {
            String state = Navigation.get().ownedState(owner);
            if (state.equals("PAUSED") || state.equals("STOPPED") || state.equals("IDLE")) {
                finish("Movement stopped while hunting.");
                return;
            }
            if (!state.equals("FAILED") && !state.equals("ARRIVED") && (!moved || ticks - huntPathTick < 16)) return;
            stopHuntWalk();
        }
        if (!player.onGround()) return;
        if (moved) {
            huntDestination = nextPosition;
            huntApproaches = hunt.approaches(prey);
            nextHuntApproach = 0;
        }
        if (nextHuntApproach >= huntApproaches.size()) {
            hunt.ignore(prey);
            prey = null;
            return;
        }
        Node stand = huntApproaches.get(nextHuntApproach++);
        if (!Navigation.get().requestOwned(owner, new Goal(stand.x(), stand.y16(), stand.z(), 0), WALK)) {
            throw new IllegalStateException("Another tool owns navigation.");
        }
        huntWalking = true;
        huntPathTick = ticks;
        describe("Walking to " + prey.getType().getDescription().getString() + ".");
    }

    private void equipHuntWeapon() {
        int slot = hunt.weaponSlot(prey);
        if (slot < 0) throw new IllegalStateException("No usable weapon for the selected mob.");
        Inventory inventory = player.getInventory();
        if (slot == inventory.getSelectedSlot()) return;
        boolean wasSwapped = swappedSlot >= 0;
        restoreHeld();
        if (wasSwapped) slot = hunt.weaponSlot(prey);
        if (slot < 0) throw new IllegalStateException("No usable weapon for the selected mob.");
        equipHuntItem(slot);
    }

    private void equipShears() {
        if (GatherHunt.usableShears(player.getMainHandItem())) return;
        restoreHeld();
        int slot = GatherHunt.shearingToolSlot(player);
        if (slot < 0) throw new IllegalStateException("No usable shears.");
        equipHuntItem(slot);
    }

    private void equipHuntItem(int slot) {
        Inventory inventory = player.getInventory();
        if (slot == inventory.getSelectedSlot()) return;
        ItemStack wanted = inventory.getItem(slot);
        if (!selectInventorySlot(slot)) throw new IllegalStateException("Couldn't equip " + wanted.getHoverName().getString() + ".");
        if (swappedSlot >= 0) swappedItem = wanted.getItem();
    }

    private void stopHuntWalk() {
        if (huntWalking) Navigation.get().cancelOwned(owner);
        huntWalking = false;
    }

    private void choose() {
        aiming = false;
        if (station != null) {
            BlockPos next = station.target();
            if (next == null) throw new IllegalStateException(station.incompleteStorage());
            if (gatherReturning != null && !WorldJob.loaded(level, next)) {
                walkTo(Action.RETURN_TO_FURNACE, gatherReturning.stand());
                return;
            }
            if (!WorldJob.loaded(level, next) || !station.validTarget(next)) { unavailable("The station or its label changed."); return; }
            approach(Action.OPEN, next);
            return;
        }
        if (world.collecting()) {
            world.processed = world.collected();
            if (world.finished()) { complete(world.result()); return; }
            drop = world.nearestDrop(world.collectBox(), Set.of());
            if (drop == null) {
                if (world.targetsAll()) complete(world.result());
                else throw new IllegalStateException("No matching drop fits the remaining count. " + world.result());
                return;
            }
            approachDrop();
            return;
        }
        if (world.finished()) { complete(world.result()); return; }
        target = world.kind() == JobSpec.Kind.QUARRY ? takeQuarryTarget() : world.take();
        if (target == null) {
            if (world.kind() == JobSpec.Kind.TUNNEL) { walkTo(Action.TUNNEL_END, world.tunnelEnd()); return; }
            if (world.kind() == JobSpec.Kind.MINE && !world.targetsAll() && world.processed < world.limit()) {
                throw new IllegalStateException("No more reachable matching blocks. " + world.result());
            }
            complete(world.result());
            return;
        }
        if (target.plant() != null && InventoryJobs.count(player, InventoryJobs.itemId(target.plant())) == 0) {
            throw new IllegalStateException("Missing " + InventoryJobs.itemId(target.plant()) + " for planting at " + target.position().toShortString() + ".");
        }
        approach(target.expected() == null ? Action.PLANT : Action.BREAK, target.position());
    }

    private WorldJob.Target takeQuarryTarget() {
        int limit = SopranoClient.module().jobLookahead();
        if (limit == 0) preparedQuarryTarget = null;
        else if (!reachableQuarryTarget(preparedQuarryTarget, player.getEyePosition())) prepareQuarryTarget(limit);
        WorldJob.Target next = world.takeQuarryTarget(preparedQuarryTarget);
        preparedQuarryTarget = null;
        return next;
    }

    private void prepareQuarryTarget(int limit) {
        preparedQuarryTarget = null;
        if (limit <= 0 || world.finished() || !player.onGround()) return;
        Vec3 eye = player.getEyePosition();
        preparedQuarryTarget = world.firstQuarryCandidate(limit, candidate -> reachableQuarryTarget(candidate, eye));
    }

    private boolean reachableQuarryTarget(WorldJob.Target candidate, Vec3 eye) {
        return candidate != null && breakHit(candidate.position(), eye) != null && safeBreakAt(candidate);
    }

    private void chooseNextBreak(String message) {
        enter(Phase.CHOOSE, message);
        choose();
        if (phase == Phase.AIM && action == Action.BREAK) aimAction();
    }

    private void walkTo(Action next, Node stand) {
        action = next;
        approaches = List.of(stand);
        nextApproach = 0;
        nextStand();
    }

    private void approach(Action next, BlockPos at) {
        action = next;
        position = at;
        aiming = false;
        if (currentHit() != null) { beginAim(); return; }
        if (next == Action.BREAK) restoreHeld();
        approaches = stands(at, false);
        nextApproach = 0;
        nextStand();
    }

    private void approachDrop() {
        action = Action.PICKUP;
        position = drop.blockPosition();
        aiming = false;
        if (campfire == null ? drop.distanceToSqr(player) < 1.1 : campfirePickupAt(player.getX(), player.getY(), player.getZ())) {
            enter(Phase.DROPS, "Waiting for the item pickup.");
            return;
        }
        restoreHeld();
        approaches = stands(position, true);
        nextApproach = 0;
        nextStand();
    }

    private List<Node> stands(BlockPos at, boolean pickup) {
        MinecraftTerrain terrain = new MinecraftTerrain(level, player);
        List<Node> candidates = new ArrayList<>();
        int radius = pickup ? 1 : 4;
        int y16 = at.getY() * 16;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = at.getX() + dx, z = at.getZ() + dz;
                if (!Node.validColumn(x) || !Node.validColumn(z)) continue;
                for (Node node : terrain.floors(x, z, y16 - 48, y16 + (pickup ? 8 : 32))) {
                    if (!terrain.canFinish(node)) continue;
                    if (pickup) {
                        Vec3 point = drop.position();
                        if (campfire != null ? campfirePickupAt(node.centerX(), node.y(), node.centerZ())
                            : Math.hypot(node.centerX() - point.x, node.centerZ() - point.z) < .82
                            && point.y >= node.y() - .15 && point.y < node.y() + 2) candidates.add(node);
                    } else if (!(action == Action.BREAK && supportAt(node, at))
                        && hit(new Vec3(node.centerX(), node.y() + player.getEyeHeight(), node.centerZ())) != null) {
                        candidates.add(node);
                    }
                }
            }
        }
        return candidates.stream().distinct().sorted(Comparator.comparingDouble(node ->
            player.position().distanceToSqr(node.centerX(), node.y(), node.centerZ()))).toList();
    }

    private boolean campfirePickupAt(double x, double y, double z) {
        // Vanilla checks a box inflated by (1, 0.5, 1). Leave a margin for route arrival tolerance.
        return player.getBoundingBox().move(x - player.getX(), y - player.getY(), z - player.getZ())
            .inflate(.8, .4, .8).intersects(drop.getBoundingBox());
    }

    private void nextStand() {
        Navigation.get().cancelOwned(owner);
        if (nextApproach >= Math.min(approaches.size(), MAX_APPROACHES)) {
            if (action == Action.BREAK && world != null && world.kind() == JobSpec.Kind.MINE
                && (world.canExcavateAccess() || gatherStep != null) && accessOre == null
                && WorldJob.loaded(level, target.position()) && level.getBlockState(target.position()) == target.expected()) {
                accessOre = target;
                accessProtection = SopranoClient.module().options().permissions().protectedBlocks();
                accessUsed = accessReplans = 0;
                planAccess();
                return;
            }
            unavailable(switch (action) {
                case RETURN -> "No verified walking route back to the start.";
                case RETURN_TO_FURNACE -> "No verified walking route back to the furnace.";
                case TUNNEL_END -> "The tunnel was excavated, but there is no verified walking route to its end.";
                default -> "No reachable work position.";
            });
            return;
        }
        Node node = approaches.get(nextApproach++);
        if (!Navigation.get().requestOwned(owner, new Goal(node.x(), node.y16(), node.z(), 0), WALK)) {
            throw new IllegalStateException("Another tool owns navigation.");
        }
        enter(Phase.WALK, switch (action) {
            case RETURN -> "Returning to the start.";
            case RETURN_TO_FURNACE -> "Returning to the furnace.";
            case TUNNEL_END -> "Checking passage to the tunnel destination.";
            case FISH -> "Walking to the fishing spot.";
            default -> "Walking to the next target.";
        });
    }

    private void planAccess() {
        accessSearch = null;
        aiming = aimed = false;
        enter(Phase.ACCESS_PLAN, "Finding a route to buried " + accessOre.position().toShortString() + ".");
    }

    private void advanceAccessPlan() {
        if (!oreStillPresent()) { failAccess("The mining target changed."); return; }
        if (!player.onGround()) {
            if (ticks > 40) failAccess("Could not regain footing before planning excavation.");
            return;
        }
        if (accessSearch == null) {
            accessSearch = new MiningApproach(level, player, accessOre.position(), accessProtection, MAX_ACCESS_BREAKS - accessUsed);
        }
        switch (accessSearch.advance()) {
            case SEARCHING -> { }
            case FAILED -> failAccess("No safe excavation route to " + accessOre.position().toShortString() + ".");
            case FOUND -> {
                accessPath = accessSearch.path();
                accessFrom = accessSearch.start();
                accessSearch = null;
                accessEdge = accessBreak = 0;
                enter(Phase.ACCESS_STEP, "Opening access to " + accessOre.position().toShortString() + ".");
            }
        }
    }

    private boolean oreStillPresent() {
        return WorldJob.loaded(level, accessOre.position()) && level.getBlockState(accessOre.position()) == accessOre.expected();
    }

    private void advanceAccessStep() {
        if (!oreStillPresent()) { failAccess("The mining target changed."); return; }
        if (!player.onGround()) {
            if (ticks > 40) failAccess("Could not regain footing in the excavation.");
            return;
        }
        target = accessOre;
        position = target.position();
        if (currentHit() != null) {
            clearAccess();
            beginAim();
            return;
        }
        if (accessEdge >= accessPath.size()) { failAccess("The ore is still outside interaction reach."); return; }
        MiningApproach.Edge edge = accessPath.get(accessEdge);
        if (!MiningApproach.validEdge(level, player, accessFrom, edge, accessProtection)) {
            replanAccess("The next excavation step is blocked or unsafe.");
            return;
        }
        while (accessBreak < edge.breaks().size() && level.getBlockState(edge.breaks().get(accessBreak)).isAir()) accessBreak++;
        if (accessBreak < edge.breaks().size()) {
            if (accessUsed >= MAX_ACCESS_BREAKS) { failAccess("Access excavation limit reached."); return; }
            position = edge.breaks().get(accessBreak);
            target = new WorldJob.Target(position, level.getBlockState(position), null, null);
            beginAim();
            return;
        }
        Node destination = edge.destination();
        if (!Navigation.get().requestOwned(owner, new Goal(destination.x(), destination.y16(), destination.z(), 0), WALK)) {
            throw new IllegalStateException("Another tool owns navigation.");
        }
        enter(Phase.ACCESS_WALK, "Walking into the confirmed opening.");
    }

    private void advanceAccessWalk() {
        if (!oreStillPresent()) { failAccess("The mining target changed."); return; }
        switch (Navigation.get().ownedState(owner)) {
            case "ARRIVED" -> {
                Navigation.get().cancelOwned(owner);
                accessFrom = accessPath.get(accessEdge++).destination();
                accessBreak = 0;
                enter(Phase.ACCESS_STEP, "Checking the next excavation step.");
            }
            case "FAILED" -> replanAccess("Could not walk into the confirmed opening.");
            case "PAUSED", "STOPPED", "IDLE" -> failAccess("Movement stopped in the excavation.");
            default -> describe(Navigation.get().ownedStatus(owner));
        }
    }

    private void replanAccess(String reason) {
        Navigation.get().cancelOwned(owner);
        if (++accessReplans > MAX_ACCESS_REPLANS) failAccess(reason);
        else planAccess();
    }

    private void failAccess(String reason) {
        Navigation.get().cancelOwned(owner);
        restoreHeld();
        clearAccess();
        world.skipped++;
        world.lastBlocked = reason;
        enter(Phase.CHOOSE, reason);
    }

    private void clearAccess() {
        accessOre = null;
        accessSearch = null;
        accessPath = List.of();
        accessFrom = null;
        accessProtection = null;
    }

    private void walk() {
        switch (Navigation.get().ownedState(owner)) {
            case "ARRIVED" -> {
                Navigation.get().cancelOwned(owner);
                if (action == Action.RETURN) complete("Returned to the queue start.");
                else if (action == Action.RETURN_TO_FURNACE) {
                    BlockPos furnace = gatherReturning.at();
                    if (!WorldJob.loaded(level, furnace) || !station.validTarget(furnace)) unavailable("The furnace is no longer available.");
                    else approach(Action.OPEN, furnace);
                }
                else if (action == Action.TUNNEL_END) complete("Tunnel destination reached. " + world.result());
                else if (action == Action.FISH) beginCast();
                else if (action == Action.PICKUP) enter(Phase.DROPS, "Waiting for the item pickup.");
                else if (currentHit() == null) nextStand();
                else beginAim();
            }
            case "FAILED" -> nextStand();
            case "PAUSED", "STOPPED", "IDLE" -> throw new IllegalStateException("Movement stopped before the next target.");
            default -> describe(Navigation.get().ownedStatus(owner));
        }
    }

    private void beginAim() {
        aimed = false;
        enter(Phase.AIM, "Aiming at " + position.toShortString() + ".");
    }

    private BlockHitResult currentHit() {
        Vec3 eye = player.getEyePosition();
        if (action == Action.BREAK) return breakHit(position, eye);
        if (!player.onGround() || !WorldJob.loaded(level, position) || !level.mayInteract(player, position)
            || !level.getWorldBorder().isWithinBounds(position)) return null;
        return hit(eye);
    }

    private BlockHitResult breakHit(BlockPos at, Vec3 eye) {
        if (!player.onGround() || !WorldJob.loaded(level, at) || !level.mayInteract(player, at)
            || !level.getWorldBorder().isWithinBounds(at) || supportsPlayer(level, player, at)
            || player.getBoundingBox().inflate(.07).intersects(new AABB(at))) return null;
        return shapeHit(at, eye);
    }

    static boolean supportsPlayer(ClientLevel level, LocalPlayer player, BlockPos at) {
        AABB body = player.getBoundingBox();
        double above = body.minY - at.getY();
        if (above <= 0 || above > 1.55 || body.maxX <= at.getX() || body.minX >= at.getX() + 1
            || body.maxZ <= at.getZ() || body.minZ >= at.getZ() + 1) return false;
        var shape = level.getBlockState(at).getCollisionShape(level, at, CollisionContext.of(player));
        return !shape.isEmpty() && Math.abs(above - shape.max(Direction.Axis.Y)) <= .05;
    }

    private BlockHitResult hit(Vec3 eye) {
        if (action == Action.SCOOP) return gatherFluid.hit(bucketResult, position, eye);
        if (action == Action.PLANT) {
            Direction side = target.placementFace();
            BlockPos support = position.relative(side.getOpposite());
            if (!WorldJob.loaded(level, support)) return null;
            var shape = level.getBlockState(support).getShape(level, support, CollisionContext.of(player));
            if (shape.isEmpty()) return null;
            AABB bounds = shape.bounds().move(support);
            Vec3 center = bounds.getCenter();
            Vec3 point = switch (side) {
                case UP -> new Vec3(center.x, bounds.maxY - .01, center.z);
                case DOWN -> new Vec3(center.x, bounds.minY + .01, center.z);
                case EAST -> new Vec3(bounds.maxX - .01, center.y, center.z);
                case WEST -> new Vec3(bounds.minX + .01, center.y, center.z);
                case SOUTH -> new Vec3(center.x, center.y, bounds.maxZ - .01);
                case NORTH -> new Vec3(center.x, center.y, bounds.minZ + .01);
            };
            BlockHitResult result = trace(eye, point);
            return result != null && result.getBlockPos().equals(support) && result.getDirection() == side ? result : null;
        }
        return shapeHit(position, eye);
    }

    private BlockHitResult shapeHit(BlockPos at, Vec3 eye) {
        var shape = level.getBlockState(at).getShape(level, at, CollisionContext.of(player));
        if (shape.isEmpty()) return null;
        AABB bounds = shape.bounds().move(at);
        BlockHitResult result = trace(eye, bounds.getCenter());
        return result != null && result.getBlockPos().equals(at) ? result : null;
    }

    private BlockHitResult trace(Vec3 eye, Vec3 point) {
        if (eye.distanceTo(point) >= player.blockInteractionRange() - .08) return null;
        BlockHitResult hit = level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    private BlockHitResult aimedHit(BlockHitResult hit) {
        if (action == Action.BREAK) {
            // This look direction was already in place for the current vanilla movement update.
            BlockHitResult ready = lookedHit(hit);
            if (ready != null) {
                yaw = player.getYRot();
                pitch = player.getXRot();
                aiming = true;
                return ready;
            }
        }
        if (!face(hit.getLocation())) return null;
        return lookedHit(hit);
    }

    private BlockHitResult lookedHit(BlockHitResult hit) {
        HitResult looked = player.pick(player.blockInteractionRange(), 1, false);
        return looked instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK
            && block.getBlockPos().equals(hit.getBlockPos())
            && (action != Action.PLANT || block.getDirection() == hit.getDirection()) ? block : null;
    }

    private boolean face(Vec3 point) {
        Vec3 vector = point.subtract(player.getEyePosition());
        float desiredYaw = (float) Math.toDegrees(Math.atan2(vector.z, vector.x)) - 90;
        float desiredPitch = (float) -Math.toDegrees(Math.atan2(vector.y, Math.hypot(vector.x, vector.z)));
        float dy = Mth.wrapDegrees(desiredYaw - player.getYRot());
        float dp = desiredPitch - player.getXRot();
        yaw = player.getYRot() + Math.clamp(dy, -40, 40);
        pitch = player.getXRot() + Math.clamp(dp, -35, 35);
        player.setYRot(yaw);
        player.setXRot(pitch);
        aiming = true;
        boolean aligned = Math.abs(dy) < .15 && Math.abs(dp) < .15;
        boolean ready = aligned && aimed;
        aimed = aligned;
        return ready;
    }

    private void aimAction() {
        if (action == Action.OPEN && gatherReturning != null && !readyToReturn(gatherReturning)) {
            aiming = aimed = false;
            enter(Phase.COOK_WAIT, "Waiting for " + gatherReturning.item() + " at " + position.toShortString() + ".");
            return;
        }
        BlockHitResult hit = currentHit();
        if (hit == null || ticks > 40) {
            if (accessOre != null) replanAccess("The access block lost clear reach.");
            else unavailable("The target lost clear reach.");
            return;
        }
        if (action == Action.SCOOP) {
            if (face(hit.getLocation()) && gatherFluid.aimed(bucketResult, position) && holdBucket()) fillBucket();
            return;
        }
        hit = aimedHit(hit);
        if (hit == null) return;
        switch (action) {
            case BREAK -> {
                if (!safeBreak()) { unavailable("The block changed or cannot be safely removed."); return; }
                if (accessOre != null && !MiningApproach.validEdge(level, player, accessFrom, accessPath.get(accessEdge), accessProtection)) {
                    replanAccess("The excavation changed before the next break.");
                    return;
                }
                int heldSlot = player.getInventory().getSelectedSlot();
                ItemStack held = player.getMainHandItem();
                if (!selectTool()) return;
                // The normal game-mode tick sends the selected slot before we send the break.
                if (heldSlot != player.getInventory().getSelectedSlot() || !ItemStack.matches(held, player.getMainHandItem())) return;
                if (world.kind() == JobSpec.Kind.QUARRY) {
                    quarryExit = new QuarryExit(level, player, position, home);
                    enter(Phase.EXIT, "Verifying an exit before digging.");
                    verifyExit();
                } else startBreaking(hit);
            }
            case PLANT -> place(hit);
            case CAMPFIRE -> loadCampfire(hit);
            case OPEN -> {
                if (!station.validTarget(position) || player.containerMenu != player.inventoryMenu) {
                    unavailable("The selected station changed before opening."); return;
                }
                if (gatherStep != null && gatherStep.kind() == JobSpec.Kind.TAKE && !GatherStorage.known(level, position)) {
                    gatherVisitedStorage.add(GatherStorage.containerKey(level, position));
                }
                mode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
                aiming = false;
                enter(Phase.OPEN_MENU, "Waiting for the station menu.");
            }
            default -> throw new IllegalStateException("Unexpected job action.");
        }
    }

    private void loadCampfire(BlockHitResult hit) {
        Item input = campfire.ingredient();
        if (!player.getMainHandItem().is(input)) {
            restoreHeld();
            if (!selectItem(input)) throw new IllegalStateException("Could not equip the campfire ingredient.");
            swappedItem = input;
            return;
        }
        campfire.submitInput();
        if (!mode.useItemOn(player, InteractionHand.MAIN_HAND, hit).consumesAction()) {
            throw new IllegalStateException("The campfire did not accept the ingredient.");
        }
        enter(Phase.CAMPFIRE, "Waiting for the server to confirm the campfire ingredient.");
    }

    private void advanceCampfire() {
        if (!campfire.inputConfirmed()) {
            if (ticks > ACK_TIMEOUT) throw new IllegalStateException("The server did not confirm the campfire ingredient.");
            return;
        }
        if (!campfire.stagingComplete()) {
            beginAim();
            return;
        }
        restoreHeld();
        if (!campfire.cooked()) {
            if (campfire.timedOut()) throw new IllegalStateException("The campfire stopped cooking.");
            describe("Cooking on the campfire.");
            return;
        }
        var pickup = campfire.pickup();
        if (pickup.collected() >= pickup.limit()) { complete("Collected the campfire batch."); return; }
        if (pickup.nearestDrop(pickup.collectBox(), Set.of()) != null) {
            world = pickup;
            enter(Phase.CHOOSE, "Collecting food from the campfire.");
            return;
        }
        if (campfire.timedOut()) throw new IllegalStateException("The campfire did not drop the requested result.");
        describe("Waiting for the campfire drops.");
    }

    private void requireBucketSupply() {
        if (InventoryJobs.count(player, InventoryJobs.itemId(bucketInput)) <= 0) throw new IllegalStateException("The required bucket is missing.");
    }

    private boolean holdBucket() {
        if (player.getMainHandItem().is(bucketInput)) return true;
        if (!selectItem(bucketInput)) throw new IllegalStateException("Could not equip the required bucket.");
        return false;
    }

    private void fillBucket() {
        bucketBefore = InventoryJobs.count(player, InventoryJobs.itemId(bucketResult));
        sequence = -1;
        recording = true;
        try {
            if (!mode.useItem(player, InteractionHand.MAIN_HAND).consumesAction()) throw new IllegalStateException("The game rejected filling the bucket.");
        } finally {
            recording = false;
        }
        aiming = false;
        enter(Phase.BUCKET_CONFIRM, "Waiting for the filled bucket.");
    }

    private void confirmBucket() {
        if (ticks > ACK_TIMEOUT) throw new IllegalStateException("The server did not return " + InventoryJobs.itemId(bucketResult) + ".");
        if (hunt == null && (sequence < 0 || ack < sequence) || hunt != null && ticks < 6) return;
        if (InventoryJobs.count(player, InventoryJobs.itemId(bucketResult)) <= bucketBefore) return;
        restoreHeld();
        complete("Obtained " + InventoryJobs.itemId(bucketResult) + ".");
    }

    private void verifyExit() {
        BlockHitResult hit = currentHit();
        if (hit == null || !safeBreak()) { unavailable("The excavation site changed before the break."); return; }
        IncrementalSearch.Result result = quarryExit.advance();
        if (result.status() == IncrementalSearch.Status.SEARCHING) return;
        if (result.status() != IncrementalSearch.Status.FOUND || !quarryExit.confirm(result)) {
            unavailable("No verified way out after removing this block.");
            return;
        }
        quarryExit = null;
        hit = lookedHit(hit);
        if (hit == null) { beginAim(); return; }
        startBreaking(hit);
    }

    private boolean safeBreak() {
        return target != null && safeBreakAt(target)
            && (accessOre == null || oreStillPresent() && !accessProtection.contains(target.expected()));
    }

    private boolean safeBreakAt(WorldJob.Target candidate) {
        BlockPos at = candidate.position();
        if (!WorldJob.loaded(level, at)) return false;
        BlockState current = level.getBlockState(at);
        if (current != candidate.expected() || !WorldJob.breakable(level, at, current) || supportsPlayer(level, player, at)) return false;
        for (Direction side : Direction.values()) {
            BlockPos adjacent = at.relative(side);
            if (!WorldJob.loaded(level, adjacent) || !level.getBlockState(adjacent).getFluidState().isEmpty()
                || side == Direction.UP && level.getBlockState(adjacent).getBlock() instanceof FallingBlock) return false;
        }
        return true;
    }

    private boolean selectTool() {
        int reserve = ToolDurability.configuredReserve();
        int slot = gatherStep == null ? JobTools.bestSlot(player, level, position, target.expected(), reserve)
            : gatherToolSlot(reserve);
        if (slot < 0) {
            if (gatherStep != null) {
                unavailable("No compatible tool above the durability reserve for " + position.toShortString() + ".");
                return false;
            }
            restoreHeld();
            aimed = aiming = false;
            quarryExit = null;
            enter(Phase.PAUSED_TOOL, "Put a compatible tool above the durability reserve in your hotbar.");
            return false;
        }
        Inventory inventory = player.getInventory();
        if (!Inventory.isHotbarSlot(slot) || swappedSlot >= 0 && slot != activeSlot) {
            restoreHeld();
            slot = gatherToolSlot(reserve);
            if (slot < 0 || !selectInventorySlot(slot)) {
                unavailable("Could not equip a compatible tool for " + position.toShortString() + ".");
                return false;
            }
            if (swappedSlot >= 0) swappedItem = player.getMainHandItem().getItem();
            return true;
        }
        if (slot != inventory.getSelectedSlot()) {
            if (selectedBefore < 0) selectedBefore = inventory.getSelectedSlot();
            activeSlot = slot;
            inventory.setSelectedSlot(slot);
        }
        return true;
    }

    private int gatherToolSlot(int reserve) {
        Item outcome = accessOre == null ? gatherMiningOutputs.get(gatherStep) : null;
        return outcome == null ? JobTools.bestInventorySlot(player, level, position, target.expected(), reserve)
            : GatherSources.bestToolForDrop(level, player, position, target.expected(), reserve, outcome);
    }

    private void startBreaking(BlockHitResult hit) {
        if (!JobTools.canBreak(player.getMainHandItem(), player, level, position, target.expected()) || pauseForTool()) {
            if (phase != Phase.PAUSED_TOOL) beginAim();
            return;
        }
        if (mode.isDestroying()) throw new IllegalStateException("Another action is mining a block.");
        if (accessOre == null && world.collectAfterBreak()) {
            previousDrops = level.getEntitiesOfClass(ItemEntity.class, dropArea()).stream().map(ItemEntity::getId).collect(Collectors.toSet());
            pickupBaseline = InventoryJobs.playerCounts(player);
        }
        sequence = -1;
        recording = true;
        try {
            if (!mode.startDestroyBlock(position, hit.getDirection())) {
                unavailable("The game rejected the block break.");
                return;
            }
            player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        } finally {
            recording = false;
        }
        mining = mode.isDestroying();
        if (!mining || level.getBlockState(position) != target.expected()) beginConfirm();
        else enter(Phase.BREAK, "Breaking " + position.toShortString() + ".");
        if (world.kind() == JobSpec.Kind.QUARRY) prepareQuarryTarget(SopranoClient.module().jobLookahead());
    }

    private void mine() {
        if (level.getBlockState(position) != target.expected()) { beginConfirm(); return; }
        if (accessOre != null && (!oreStillPresent()
            || !MiningApproach.validEdge(level, player, accessFrom, accessPath.get(accessEdge), accessProtection))) {
            if (mining && mode.isDestroying()) mode.stopDestroyBlock();
            mining = false;
            restoreHeld();
            replanAccess("The excavation changed while breaking a block.");
            return;
        }
        if (pauseForTool()) return;
        BlockHitResult hit = currentHit();
        String at = position.toShortString();
        if (hit == null || !safeBreak()) throw new IllegalStateException("Lost safe reach to the block at " + at + ".");
        if (!JobTools.canBreak(player.getMainHandItem(), player, level, position, target.expected())) {
            throw new IllegalStateException("The held tool changed while breaking " + at + ".");
        }
        if (!mining || !mode.isDestroying()) throw new IllegalStateException("Minecraft interrupted the break at " + at + ".");
        if (ticks > BREAK_TIMEOUT) throw new IllegalStateException("Timed out breaking the block at " + at + ".");
        recording = true;
        try {
            if (mode.continueDestroyBlock(position, hit.getDirection())) {
                player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            }
        } finally {
            recording = false;
        }
        if (level.getBlockState(position) != target.expected()) beginConfirm();
    }

    private boolean pauseForTool() {
        String reason = ToolDurability.pauseReason(player, level, position, target.expected(), ToolDurability.configuredReserve());
        if (reason == null) return false;
        if (gatherStep != null) throw new IllegalStateException(reason);
        if (mining && mode.isDestroying()) mode.stopDestroyBlock();
        mining = aimed = aiming = false;
        restoreHeld();
        quarryExit = null;
        enter(Phase.PAUSED_TOOL, "Paused. " + reason);
        return true;
    }

    private void resumeAfterTool() {
        if (!WorldJob.loaded(level, position) || level.getBlockState(position) != target.expected()) {
            unavailable("The target changed while waiting for a tool.");
            return;
        }
        if (!player.onGround() || !safeBreak()) return;
        if (JobTools.bestSlot(player, level, position, target.expected(), ToolDurability.configuredReserve()) < 0) return;
        if (currentHit() == null && accessOre != null) replanAccess("The access block moved out of reach.");
        else if (currentHit() == null) nextStand();
        else beginAim();
    }

    private void place(BlockHitResult hit) {
        BlockState current = level.getBlockState(position);
        if (matchesPlant(current)) {
            restoreHeld();
            afterPlant();
            return;
        }
        if (!current.canBeReplaced() || !selectItem(target.plant())) {
            throw new IllegalStateException("Unable to plant at " + position.toShortString() + ".");
        }
        sequence = -1;
        recording = true;
        try {
            if (!mode.useItemOn(player, InteractionHand.MAIN_HAND, hit).consumesAction()) {
                throw new IllegalStateException("The game rejected planting at " + position.toShortString() + ".");
            }
            player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        } finally {
            recording = false;
        }
        beginConfirm();
    }

    private boolean matchesPlant(BlockState state) {
        return target.plant() instanceof BlockItem item && state.getBlock() == item.getBlock();
    }

    private boolean selectItem(Item wanted) {
        Inventory inventory = player.getInventory();
        if (inventory.getSelectedItem().is(wanted)) return true;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            if (!inventory.getItem(slot).is(wanted)) continue;
            return selectInventorySlot(slot) && inventory.getSelectedItem().is(wanted);
        }
        return false;
    }

    private boolean equipBreedingFood(ItemStack wanted) {
        if (ItemStack.isSameItemSameComponents(wanted, player.getMainHandItem())) return true;
        restoreHeld();
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            if (!ItemStack.isSameItemSameComponents(wanted, inventory.getItem(slot))) continue;
            if (!selectInventorySlot(slot)) throw new IllegalStateException("Could not equip the breeding food.");
            if (swappedSlot >= 0) swappedItem = wanted.getItem();
            return false;
        }
        throw new IllegalStateException("The breeding food is no longer carried.");
    }

    private boolean selectInventorySlot(int slot) {
        Inventory inventory = player.getInventory();
        int current = inventory.getSelectedSlot();
        if (Inventory.isHotbarSlot(slot)) {
            selectedBefore = current;
            activeSlot = slot;
            inventory.setSelectedSlot(slot);
            return true;
        }
        if (player.containerMenu != player.inventoryMenu || !player.inventoryMenu.getCarried().isEmpty()) return false;
        var menuSlot = player.inventoryMenu.findSlot(inventory, slot);
        if (menuSlot.isEmpty()) return false;
        selectedBefore = activeSlot = current;
        swappedHeld = inventory.getItem(current).copy();
        swappedSlot = slot;
        mode.handleContainerInput(player.inventoryMenu.containerId, menuSlot.getAsInt(), current, ContainerInput.SWAP, player);
        return true;
    }

    private void restoreHeld() {
        if (player != null && sameWorld(Minecraft.getInstance()) && activeSlot >= 0) {
            Inventory inventory = player.getInventory();
            if (inventory.getSelectedSlot() == activeSlot) {
                if (swappedSlot >= 0 && player.containerMenu == player.inventoryMenu && player.inventoryMenu.getCarried().isEmpty()
                    && ItemStack.matches(inventory.getItem(swappedSlot), swappedHeld)
                    && (inventory.getItem(activeSlot).isEmpty() || target != null && inventory.getItem(activeSlot).is(target.plant())
                    || swappedItem != null && inventory.getItem(activeSlot).is(swappedItem)
                    || bucketResult != null && (inventory.getItem(activeSlot).is(bucketInput) || inventory.getItem(activeSlot).is(bucketResult)))) {
                    player.inventoryMenu.findSlot(inventory, swappedSlot).ifPresent(menuSlot ->
                        mode.handleContainerInput(player.inventoryMenu.containerId, menuSlot, activeSlot, ContainerInput.SWAP, player));
                }
                inventory.setSelectedSlot(selectedBefore);
            }
        }
        activeSlot = selectedBefore = swappedSlot = -1;
        swappedHeld = ItemStack.EMPTY;
        swappedItem = null;
    }

    private void beginConfirm() {
        if (mining && mode.isDestroying()) mode.stopDestroyBlock();
        mining = false;
        enter(Phase.CONFIRM, "Waiting for the server to confirm the block update.");
    }

    private void confirm() {
        if (ticks > ACK_TIMEOUT) throw new IllegalStateException("The server did not confirm the block update.");
        if (sequence < 0 || ack < sequence) return;
        if (!WorldJob.loaded(level, position)) throw new IllegalStateException("The changed block is no longer loaded.");
        if (action == Action.PLANT) {
            if (!matchesPlant(level.getBlockState(position))) throw new IllegalStateException("The server rejected the plant or torch.");
            restoreHeld();
            afterPlant();
            return;
        }
        if (!level.getBlockState(position).isAir()) throw new IllegalStateException("The server rejected the break or another block took its place.");
        if (accessOre != null || target.plant() != null
            || world.kind() != JobSpec.Kind.QUARRY && world.kind() != JobSpec.Kind.MINE) restoreHeld();
        if (accessOre != null) {
            accessUsed++;
            accessBreak++;
            world.accessBreaks++;
            enter(Phase.ACCESS_STEP, "Confirmed access break.");
            return;
        }
        world.processed++;
        if (target.plant() != null) approach(Action.PLANT, target.position());
        else if (world.collectAfterBreak()) {
            beginDropWait();
            drops();
        } else chooseNextBreak("Finding the next target.");
    }

    private void afterPlant() {
        if (gatherStep != null && gatherStep.kind() == JobSpec.Kind.SETUP) complete("Placed " + gatherStep.ids().getFirst() + ".");
        else if (target.expected() != null && world.collectAfterBreak()) beginDropWait();
        else enter(Phase.CHOOSE, "Finding the next target.");
    }

    private void beginDropWait() {
        aiming = false;
        drop = null;
        dropDeadline = 0;
        sawDrop = false;
        enter(Phase.DROPS, "Looking for drops from the last block.");
    }

    private AABB dropArea() { return new AABB(target.position()).inflate(2.5); }

    private void drops() {
        if (world.collecting()) world.processed = world.collected();
        if (drop != null && drop.isAlive() && ticks < 40 && (!world.collecting() || !world.finished())) return;
        world.ignoreDrop(drop);
        drop = null;
        if (world.collecting()) { enter(Phase.CHOOSE, "Checking the remaining drops."); return; }
        drop = world.nearestDrop(dropArea(), previousDrops);
        if (drop != null) {
            sawDrop = true;
            approachDrop();
        } else if (sawDrop || dropDeadline++ > 16) {
            world.countPickups(pickupBaseline);
            pickupBaseline = Map.of();
            chooseNextBreak("Finding the next block.");
        }
    }

    private void openMenu(Minecraft mc) {
        if (mc.gui.screen() != null && player.containerMenu != player.inventoryMenu) {
            menu = player.containerMenu;
            station.opened();
            enter(Phase.MENU, "Checking the open station.");
        } else if (ticks > ACK_TIMEOUT) unavailable("The station menu did not open.");
    }

    private void menu(Minecraft mc) {
        if (player.containerMenu != menu || mc.gui.screen() == null) throw new IllegalStateException("The station menu closed.");
        if (!station.inventoryCrafting() && (!WorldJob.loaded(level, position) || !station.validTarget(position))) {
            unavailable("The station or its sign changed."); return;
        }
        StationJob.Result result = station.tick(menu);
        describe(station.status());
        if (result == StationJob.Result.COOKING) {
            considerLeavingFurnace();
            return;
        }
        if (result == StationJob.Result.RUNNING) return;
        closeMenu();
        if (result == StationJob.Result.FINISHED) complete(station.status());
        else {
            station.advance();
            enter(Phase.CHOOSE, "Checking the next labeled container.");
        }
    }

    private void closeMenu() {
        if (menu != null && sameWorld(Minecraft.getInstance()) && player.containerMenu == menu && menu.getCarried().isEmpty()) {
            player.closeContainer();
        }
        menu = null;
    }

    private void unavailable(String reason) {
        aiming = false;
        if (station != null && station.storageJob()) {
            closeMenu();
            station.advance();
            if (station.target() == null) throw new IllegalStateException(reason + " " + station.incompleteStorage());
            enter(Phase.CHOOSE, reason + " Checking the next labeled container.");
        } else if (action == Action.PICKUP) {
            world.ignoreDrop(drop);
            drop = null;
            enter(world.collecting() ? Phase.CHOOSE : Phase.DROPS, reason);
        } else if (accessOre != null) {
            failAccess(reason);
        } else if (world != null && action == Action.BREAK && world.kind() != JobSpec.Kind.TUNNEL) {
            restoreHeld();
            world.skipped++;
            world.lastBlocked = reason;
            enter(Phase.CHOOSE, reason);
        } else throw new IllegalStateException(reason);
    }

    private void complete(String result) {
        aiming = false;
        if (gatherStep != null) { settleGather(result, false); return; }
        restoreHeld();
        preparedQuarryTarget = null;
        enter(Phase.NEXT, result);
    }

    private void enter(Phase phase, String reason) {
        this.phase = phase;
        ticks = 0;
        describe(reason);
    }

    private void describe(String reason) {
        if (index < 0 || index >= work.size()) { status = reason; return; }
        String kind = work.get(index).kind().name().toLowerCase(Locale.ROOT);
        status = "Step " + (index + 1) + "/" + work.size() + " " + kind + ". " + reason;
        if (!gatherFurnaces.isEmpty()) {
            status += gatherFurnaces.size() == 1 ? " One furnace batch in progress."
                : " " + gatherFurnaces.size() + " furnace batches in progress.";
        }
    }

    private void finish(String reason) { finish(reason, true); }

    private void finish(String reason, boolean close) {
        Minecraft mc = Minecraft.getInstance();
        stopFishing();
        resetCommerce();
        breeding = null;
        if (sameWorld(mc)) {
            if (mining && mode.isDestroying()) mode.stopDestroyBlock();
            if (close) closeMenu();
        }
        restoreHeld();
        Navigation.get().cancelOwned(owner);
        aiming = mining = recording = plannerHeld = false;
        phase = Phase.IDLE;
        status = reason;
        if (!gatherFurnaces.isEmpty()) {
            status += " Check the furnace at " + gatherFurnaces.getFirst().at().toShortString() + ".";
        }
        world = null;
        station = null;
        campfire = null;
        menu = null;
        quarryExit = null;
        preparedQuarryTarget = null;
        clearAccess();
        gather = null;
        gatherTrading = null;
        gatherStep = null;
        gatherSourceKeys.clear();
        gatherBlockPlans.clear();
        gatherMiningTools.clear();
        gatherMiningOutputs.clear();
        gatherFishPlans.clear();
        gatherRejectedSources.clear();
        gatherVisitedStorage.clear();
        gatherFurnaces.clear();
        gatherReturning = null;
        gatherLookaheadStep = null;
        gatherBefore = Map.of();
        gatherHotbarBefore = Map.of();
        hunt = null;
        prey = null;
        gatherFluid = null;
        gatherFishing = null;
        bucketResult = bucketInput = null;
        target = null;
        drop = null;
        player = null;
        level = null;
        mode = null;
        work = List.of();
    }

    static boolean supportAt(Node node, BlockPos block) {
        return node.x() == block.getX() && node.z() == block.getZ()
            && node.y() > block.getY() && node.y() <= block.getY() + 1.05;
    }

}
