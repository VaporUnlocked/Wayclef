package dev.soprano.ui;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.commands.Commands;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;

import java.util.ArrayList;
import java.util.List;

public final class SopranoCommand extends Command {
    private final SopranoModule module;

    public SopranoCommand(SopranoModule module) {
        super(SopranoCommandTree.NAME, "Navigate, build, mine and run local jobs.", availableAliases());
        this.module = module;
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> root) {
        new SopranoCommandTree(module, message -> info("%s", message), message -> error("%s", message), this::toString).build(root);
    }

    private static String[] availableAliases() {
        List<String> aliases = new ArrayList<>(2);
        if (aliasAvailable(SopranoCommandTree.SHORT_NAME)) aliases.add(SopranoCommandTree.SHORT_NAME);
        FabricLoader loader = FabricLoader.getInstance();
        if (!loader.isModLoaded("baritone") && !loader.isModLoaded("baritone-meteor") && aliasAvailable("b")) {
            aliases.add("b");
        }
        return aliases.toArray(String[]::new);
    }

    private static boolean aliasAvailable(String alias) {
        if (Commands.DISPATCHER != null && Commands.DISPATCHER.getRoot().getChild(alias) != null) return false;
        return Commands.COMMANDS.stream().noneMatch(command ->
            alias.equals(command.getName()) || command.getAliases().contains(alias));
    }
}
