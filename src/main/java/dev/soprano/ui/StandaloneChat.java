package dev.soprano.ui;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.soprano.SopranoClient;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;

/** Runs explicitly addressed standalone Wayclef commands locally. */
public final class StandaloneChat {
    private static final String PREFIX = "." + SopranoCommandTree.NAME;
    private static final String SHORT_PREFIX = "." + SopranoCommandTree.SHORT_NAME;
    private static CommandDispatcher<ClientSuggestionProvider> dispatcher;

    private StandaloneChat() { }

    public static boolean handle(String message, ClientSuggestionProvider source) {
        if (!SopranoClient.ready() || SopranoClient.isMeteor()) return false;
        String command = message.strip();
        int offset = hasPrefix(command, PREFIX) ? PREFIX.length() : hasPrefix(command, SHORT_PREFIX) ? SHORT_PREFIX.length() : -1;
        if (offset < 0) return false;
        if (dispatcher == null) {
            dispatcher = new CommandDispatcher<>();
            var root = LiteralArgumentBuilder.<ClientSuggestionProvider>literal(SopranoCommandTree.NAME);
            new SopranoCommandTree(SopranoClient.module(), SopranoClient::feedback, SopranoClient::feedback,
                syntax -> PREFIX + " " + syntax).build(root);
            dispatcher.register(root);
        }
        try {
            dispatcher.execute(SopranoCommandTree.NAME + command.substring(offset), source);
        } catch (CommandSyntaxException problem) {
            SopranoClient.feedback(problem.getRawMessage().getString());
        }
        return true;
    }

    private static boolean hasPrefix(String command, String prefix) {
        return command.equals(prefix) || command.startsWith(prefix + " ");
    }
}
