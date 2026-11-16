package dev.soprano.ui;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.soprano.SopranoClient;
import dev.soprano.game.EscapeController;
import dev.soprano.game.StrongholdTracker;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.mojang.brigadier.Command.SINGLE_SUCCESS;
import static com.mojang.brigadier.builder.RequiredArgumentBuilder.argument;

/** Common client command syntax used by both hosts. */
final class SopranoCommandTree {
    static final String NAME = "wayclef";
    static final String SHORT_NAME = "way";

    private final SopranoModule module;
    private final Consumer<String> info;
    private final Consumer<String> error;
    private final Function<String, String> usage;

    SopranoCommandTree(SopranoModule module, Consumer<String> info, Consumer<String> error, Function<String, String> usage) {
        this.module = module;
        this.info = info;
        this.error = error;
        this.usage = usage;
    }

    void build(LiteralArgumentBuilder<ClientSuggestionProvider> root) {
        root.executes(context -> openSettings());
        root.then(LiteralArgumentBuilder.<ClientSuggestionProvider>literal("settings").executes(context -> openSettings()));
        root.then(LiteralArgumentBuilder.<ClientSuggestionProvider>literal("goto").then(destination(true)));
        root.then(LiteralArgumentBuilder.<ClientSuggestionProvider>literal("preview").then(destination(false)));
        root.then(LiteralArgumentBuilder.<ClientSuggestionProvider>literal("xz").then(coordinate("x").then(coordinate("z")
            .executes(context -> walkTo(value(context, "x"), null, value(context, "z"), true)))));
        root.then(LiteralArgumentBuilder.<ClientSuggestionProvider>literal("here").executes(context -> act(module::routeToCrosshair)));
        root.then(LiteralArgumentBuilder.<ClientSuggestionProvider>literal("surface").executes(context -> act(module::routeToSurface)));
        root.then(LiteralArgumentBuilder.<ClientSuggestionProvider>literal("follow").then(
            RequiredArgumentBuilder.<ClientSuggestionProvider, String>argument("player", StringArgumentType.word()).executes(context ->
                act(() -> module.followPlayer(StringArgumentType.getString(context, "player"))))));
        root.then(LiteralArgumentBuilder.<ClientSuggestionProvider>literal("detour").executes(context -> act(module::detourToCrosshair)));
        root.then(LiteralArgumentBuilder.<ClientSuggestionProvider>literal("pause").executes(context -> act(module::pause)));
        root.then(LiteralArgumentBuilder.<ClientSuggestionProvider>literal("resume").executes(context -> act(module::resume)));
        root.then(LiteralArgumentBuilder.<ClientSuggestionProvider>literal("stop").executes(context -> act(module::stop)));
        root.then(LiteralArgumentBuilder.<ClientSuggestionProvider>literal("cancel").executes(context -> act(module::stop)));
        root.then(LiteralArgumentBuilder.<ClientSuggestionProvider>literal("status").executes(context -> reportStatus()));
        root.then(LiteralArgumentBuilder.<ClientSuggestionProvider>literal("help").executes(context -> help()));
        automation(root);
    }

    private void automation(LiteralArgumentBuilder<ClientSuggestionProvider> root) {
        AutomationControls automation = module.automation();
        root.then(literal("mine").then(textArgument("blocks").executes(context -> act(() -> automation.mine(string(context, "blocks"))))));
        root.then(literal("gather").then(textArgument("order").executes(context -> act(() -> automation.runJob("gather " + string(context, "order"))))));
        root.then(literal("breed").then(textArgument("order").executes(context -> act(() -> automation.breed(string(context, "order"))))));
        root.then(literal("escape").executes(context -> act(() -> module.escape(EscapeController.Choice.AUTO)))
            .then(literal("pearl").executes(context -> act(() -> module.escape(EscapeController.Choice.PEARL))))
            .then(literal("chorus").executes(context -> act(() -> module.escape(EscapeController.Choice.CHORUS)))));
        root.then(literal("stronghold").executes(context -> respond(StrongholdTracker.get()::start))
            .then(literal("go").executes(context -> act(module::approachStronghold)))
            .then(literal("mark").executes(context -> respond(module::markStronghold))));
        root.then(literal("brew").then(textArgument("order").executes(context -> act(() -> automation.runJob("brew " + string(context, "order"))))));
        root.then(literal("enchant").then(textArgument("order").executes(context -> act(() -> automation.runJob("enchant " + string(context, "order"))))));
        root.then(literal("cushion").executes(context -> act(() -> module.sitOnCushion(null)))
            .then(RequiredArgumentBuilder.<ClientSuggestionProvider, String>argument("color", StringArgumentType.word())
                .executes(context -> act(() -> module.sitOnCushion(string(context, "color")))))
            .then(literal("to").then(coordinate("x").then(coordinate("y").then(coordinate("z")
                .executes(context -> act(() -> module.routeByCushion(position(context)))))))));
        root.then(literal("portal")
            .then(literal("eyes").executes(context -> act(() -> module.fillEndPortal(AutomationControls.crosshair())))
                .then(coordinate("x").then(coordinate("y").then(coordinate("z")
                    .executes(context -> act(() -> module.fillEndPortal(position(context))))))))
            .then(literal("gateway").executes(context -> act(() -> module.hopGateway(AutomationControls.crosshair())))
                .then(coordinate("x").then(coordinate("y").then(coordinate("z")
                    .executes(context -> act(() -> module.hopGateway(position(context))))))))
            .then(literal("mark").executes(context -> respond(() -> module.markPortal(AutomationControls.crosshair())))
                .then(coordinate("x").then(coordinate("y").then(coordinate("z")
                    .executes(context -> respond(() -> module.markPortal(position(context))))))))
            .then(literal("enter").executes(context -> act(() -> module.enterPortal(AutomationControls.crosshair())))
                .then(coordinate("x").then(coordinate("y").then(coordinate("z")
                    .executes(context -> act(() -> module.enterPortal(position(context))))))))
            .then(literal("build").then(coordinate("x").then(coordinate("y").then(coordinate("z")
                .then(literal("x").executes(context -> act(() -> module.buildPortal(position(context), Direction.Axis.X))))
                .then(literal("z").executes(context -> act(() -> module.buildPortal(position(context), Direction.Axis.Z))))))))
            .then(literal("light").executes(context -> act(() -> module.lightPortal(AutomationControls.crosshair().above())))
                .then(coordinate("x").then(coordinate("y").then(coordinate("z")
                    .executes(context -> act(() -> module.lightPortal(position(context)))))))));
        root.then(literal("farm").executes(context -> act(() -> automation.farm(AutomationControls.NEARBY_RADIUS)))
            .then(count("radius", 32).executes(context -> act(() -> automation.farm(value(context, "radius"))))));
        root.then(literal("pickup").then(textArgument("items").executes(context -> act(() -> automation.pickup(string(context, "items"))))));
        root.then(literal("tunnel").executes(context -> act(() -> automation.tunnel(16)))
            .then(count("length", 128).executes(context -> act(() -> automation.tunnel(value(context, "length"))))));
        root.then(literal("job").then(textArgument("step").executes(context -> act(() -> automation.runJob(string(context, "step"))))));
        root.then(literal("build").executes(context -> act(automation::buildCurrent))
            .then(textArgument("file").executes(context -> respond(() -> automation.buildFile(string(context, "file"), null))))
            .then(literal("at").then(coordinate("x").then(coordinate("y").then(coordinate("z")
                .then(textArgument("file").executes(context -> respond(() -> automation.buildFile(string(context, "file"), position(context))))))))));
        root.then(literal("sel").then(corner("pos1", true)).then(corner("pos2", false))
            .then(literal("clear").executes(context -> respond(() -> {
                automation.select(null, null);
                return "Cleared the region selection.";
            })))
            .then(literal("fill").then(textArgument("block").executes(context -> act(() -> automation.fill(string(context, "block"), false)))))
            .then(literal("shell").then(textArgument("block").executes(context -> act(() -> automation.fill(string(context, "block"), true)))))
            .then(literal("cleararea").executes(context -> act(() -> automation.runJob("quarry 32768"))))
            .then(literal("replace").executes(context -> act(() -> module.startReplace(automation.selection(), AutomationControls.crosshair())))
                .then(coordinate("x").then(coordinate("y").then(coordinate("z")
                    .executes(context -> act(() -> module.startReplace(automation.selection(), position(context)))))))));
    }

    private LiteralArgumentBuilder<ClientSuggestionProvider> corner(String name, boolean first) {
        return literal(name).executes(context -> respond(() -> module.automation().setCorner(first, AutomationControls.crosshair())))
            .then(coordinate("x").then(coordinate("y").then(coordinate("z")
                .executes(context -> respond(() -> module.automation().setCorner(first, position(context)))))));
    }

    private static LiteralArgumentBuilder<ClientSuggestionProvider> literal(String name) { return LiteralArgumentBuilder.literal(name); }

    private static RequiredArgumentBuilder<ClientSuggestionProvider, String> textArgument(String name) {
        return argument(name, StringArgumentType.greedyString());
    }

    private static RequiredArgumentBuilder<ClientSuggestionProvider, Integer> count(String name, int maximum) {
        return argument(name, IntegerArgumentType.integer(1, maximum));
    }

    private static BlockPos position(CommandContext<ClientSuggestionProvider> context) {
        return new BlockPos(value(context, "x"), value(context, "y"), value(context, "z"));
    }

    private static String string(CommandContext<ClientSuggestionProvider> context, String name) {
        return StringArgumentType.getString(context, name);
    }

    private RequiredArgumentBuilder<ClientSuggestionProvider, Integer> destination(boolean walk) {
        var yOrZ = coordinate("y-or-z");
        yOrZ.executes(context -> walkTo(value(context, "x"), null, value(context, "y-or-z"), walk));
        yOrZ.then(coordinate("z").executes(context ->
            walkTo(value(context, "x"), value(context, "y-or-z"), value(context, "z"), walk)));
        return coordinate("x").then(yOrZ);
    }

    private static RequiredArgumentBuilder<ClientSuggestionProvider, Integer> coordinate(String name) {
        return argument(name, IntegerArgumentType.integer(SopranoModule.MIN_XZ, SopranoModule.MAX_XZ));
    }

    private static int value(CommandContext<ClientSuggestionProvider> context, String name) {
        return IntegerArgumentType.getInteger(context, name);
    }

    private int walkTo(int x, Integer y, int z, boolean walk) {
        return act(() -> module.request(module.coordinateGoal(x, y, z), walk));
    }

    private int act(Runnable action) {
        return respond(() -> {
            action.run();
            return module.statusText();
        });
    }

    private int respond(Supplier<String> action) {
        try {
            info.accept(action.get());
            return SINGLE_SUCCESS;
        } catch (IllegalArgumentException | IllegalStateException exception) {
            error.accept(exception.getMessage());
            return 0;
        }
    }

    private int reportStatus() {
        info.accept(module.statusText());
        return SINGLE_SUCCESS;
    }

    private int openSettings() {
        SopranoClient.openPlanningScreen();
        return SINGLE_SUCCESS;
    }

    private int help() {
        info.accept(usage.apply("goto <x> <y> <z>") + " walks to a coordinate. Omit y to accept any reachable height.");
        info.accept(usage.apply("preview <x> <y> <z>") + " shows a route. " + usage.apply("here") + " walks to the targeted block.");
        info.accept(usage.apply("surface") + " walks to the closest reachable footing open to the sky.");
        info.accept(usage.apply("follow <player>") + " follows a visible player. Run it again to resume a paused follow.");
        info.accept(usage.apply("detour") + " suspends a trip and routes to the targeted block. Use resume to continue the trip.");
        info.accept(usage.apply("mine [count] <block...>") + " mines nearby blocks, including buried targets. Use farm [radius], pickup <item> [count], or tunnel [length] for other work.");
        info.accept(usage.apply("gather <item> <amount>") + " gathers nearby resources and crafts toward the requested inventory count.");
        info.accept(usage.apply("breed <mob> [count]") + " uses carried breeding food on eligible nearby animals.");
        info.accept(usage.apply("brew <potion> [amount] [form]") + " brews carried supplies at the targeted stand. Use "
            + usage.apply("brew item <item> [amount]") + " for other recipe outputs.");
        info.accept(usage.apply("enchant <item> [offer] [enchantment]") + " picks an affordable offer at the targeted table.");
        info.accept(usage.apply("cushion [color]") + " walks to and sits on a free cushion.");
        info.accept(usage.apply("cushion to <x> <y> <z>") + " follows a chain of cushions to a destination seat.");
        info.accept(usage.apply("escape [pearl|chorus]") + " uses a carried teleport item to get away.");
        info.accept(usage.apply("portal build <x> <y> <z> <x|z>") + " builds a Nether portal frame around the lower opening. Use "
            + usage.apply("portal light [x y z]") + " to light it.");
        info.accept(usage.apply("portal mark [x y z]") + " saves an active portal. " + usage.apply("portal enter [x y z]")
            + " walks through a Nether portal and checks the destination.");
        info.accept(usage.apply("stronghold") + " surveys your next two Eye of Ender throws.");
        info.accept(usage.apply("portal eyes [x y z]") + " fills an End portal frame. "
            + usage.apply("portal gateway [x y z]") + " throws an ender pearl through a loaded gateway.");
        info.accept(usage.apply("build [file]") + " builds the current blueprint or a schematic. Use build at <x> <y> <z> <file> to set its origin.");
        info.accept(usage.apply("sel pos1|pos2 [x y z]") + " marks corners. Use sel fill <block>, sel shell <block>, sel cleararea, or sel replace with a held block.");
        info.accept(usage.apply("job <step>") + " runs a step using Activities syntax. Use stop to end a job or status to check it.");
        return SINGLE_SUCCESS;
    }
}
