package org.crafterscr.craftersmobmanagerspawn.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.crafterscr.craftersmobmanagerspawn.CraftersMobManagerSpawn;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class MMSpawnStorage {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    private static final Type ZONE_MAP_TYPE = new TypeToken<Map<String, SpawnZone>>() {
    }.getType();

    public static Path getFolder(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("crafters_mob_manager_spawn");
    }

    public static Path getFile(MinecraftServer server) {
        return getFolder(server).resolve("zones.json");
    }

    public static Map<String, SpawnZone> load(MinecraftServer server) {
        Path file = getFile(server);

        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }

        try (Reader reader = Files.newBufferedReader(file)) {
            Map<String, SpawnZone> zones = GSON.fromJson(reader, ZONE_MAP_TYPE);

            if (zones == null) {
                return new LinkedHashMap<>();
            }

            for (SpawnZone zone : zones.values()) {
                zone.resetRuntime();
            }

            CraftersMobManagerSpawn.LOGGER.info("Zonas de spawn cargadas: {}", zones.size());
            return zones;

        } catch (Exception e) {
            CraftersMobManagerSpawn.LOGGER.error("No se pudieron cargar las zonas de spawn.", e);
            return new LinkedHashMap<>();
        }
    }

    public static void save(MinecraftServer server, Map<String, SpawnZone> zones) {
        Path folder = getFolder(server);
        Path file = getFile(server);

        try {
            Files.createDirectories(folder);

            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(zones, ZONE_MAP_TYPE, writer);
            }

        } catch (IOException e) {
            CraftersMobManagerSpawn.LOGGER.error("No se pudieron guardar las zonas de spawn.", e);
        }
    }
}