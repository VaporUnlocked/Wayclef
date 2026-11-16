package dev.soprano.task;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.soprano.SopranoClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.world.item.crafting.Recipe;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Reads local server data for recipe hints when no integrated recipe manager is available. */
final class InstalledRecipes {
    private InstalledRecipes() { }

    static void forEach(RegistryAccess registries, Consumer<Recipe<?>> recipe) {
        var vanilla = ServerPacksSource.createVanillaPackSource().fullResources();
        var modRoots = FabricLoader.getInstance().getAllMods().stream()
            .filter(mod -> !mod.getMetadata().getId().equals("minecraft"))
            .flatMap(mod -> mod.getRootPaths().stream()).distinct().toList();
        forEach(vanilla, modRoots, registries, recipe);
    }

    static void forEach(PackResources vanilla, Iterable<Path> modRoots, RegistryAccess registries, Consumer<Recipe<?>> recipe) {
        var ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        Map<Identifier, IoSupplier<InputStream>> resources = new LinkedHashMap<>();
        PackResources.ResourceOutput add = (id, content) -> {
            if (id.getPath().endsWith(".json")) resources.put(id, content);
        };
        for (String namespace : vanilla.getNamespaces(PackType.SERVER_DATA)) {
            vanilla.listResources(PackType.SERVER_DATA, namespace, "recipe", add);
        }
        for (Path root : modRoots) {
            Path data = root.resolve("data");
            for (String namespace : PathPackResources.getNamespaces(data)) {
                PathPackResources.listResources(data, namespace, "recipe", add);
            }
        }
        resources.forEach((id, content) -> {
            try (var reader = new InputStreamReader(content.get(), StandardCharsets.UTF_8)) {
                Recipe.DIRECT_CODEC.parse(ops, JsonParser.parseReader(reader)).result().ifPresent(recipe);
            } catch (IOException | RuntimeException failure) {
                SopranoClient.LOG.debug("Skipping unreadable installed recipe {}", id, failure);
            }
        });
    }
}
