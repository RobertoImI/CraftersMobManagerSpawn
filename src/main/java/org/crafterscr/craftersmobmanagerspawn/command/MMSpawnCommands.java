package org.crafterscr.craftersmobmanagerspawn.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.crafterscr.craftersmobmanagerspawn.compat.CobblemonCompat;
import org.crafterscr.craftersmobmanagerspawn.data.SpawnDropEntry;
import org.crafterscr.craftersmobmanagerspawn.data.SpawnMobEntry;
import org.crafterscr.craftersmobmanagerspawn.data.SpawnPointData;
import org.crafterscr.craftersmobmanagerspawn.data.SpawnZone;
import org.crafterscr.craftersmobmanagerspawn.logic.MMSpawnManager;
import org.crafterscr.craftersmobmanagerspawn.util.HeightMode;
import org.crafterscr.craftersmobmanagerspawn.util.RespawnMode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Registra y ejecuta todos los comandos del mod.
 *
 * Todos los comandos cuelgan de /mmspawn y están limitados a OP/admin
 * usando source.hasPermission(2).
 */
public class MMSpawnCommands {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    /**
     * Construye el árbol completo de comandos.
     *
     * Nota:
     * /mmspawn pokemon solo se registra si Cobblemon está cargado.
     */
    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("mmspawn")
                .requires(source -> source.hasPermission(2))

                .then(Commands.literal("create")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(context -> create(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "id")
                                ))))

                .then(Commands.literal("delete")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                .executes(context -> delete(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "id")
                                ))))

                .then(Commands.literal("list")
                        .executes(context -> list(context.getSource())))

                .then(Commands.literal("info")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                .executes(context -> info(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "id")
                                ))))

                .then(Commands.literal("center")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                .executes(context -> center(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "id")
                                ))))

                .then(Commands.literal("radius")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                .then(Commands.argument("value", IntegerArgumentType.integer(1))
                                        .executes(context -> setRadius(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "id"),
                                                IntegerArgumentType.getInteger(context, "value")
                                        )))))

                .then(Commands.literal("ymin")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                .then(Commands.argument("value", IntegerArgumentType.integer(-64, 320))
                                        .executes(context -> setYMin(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "id"),
                                                IntegerArgumentType.getInteger(context, "value")
                                        )))))

                .then(Commands.literal("ymax")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                .then(Commands.argument("value", IntegerArgumentType.integer(-64, 320))
                                        .executes(context -> setYMax(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "id"),
                                                IntegerArgumentType.getInteger(context, "value")
                                        )))))

                .then(Commands.literal("heightmode")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                .then(Commands.argument("mode", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(new String[]{"ground", "floor", "exact", "air"}, builder))
                                        .executes(context -> setHeightMode(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "id"),
                                                StringArgumentType.getString(context, "mode")
                                        )))))

                .then(Commands.literal("respawnMode")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                .then(Commands.argument("mode", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(new String[]{"empty", "cooldown"}, builder))
                                        .executes(context -> setRespawnMode(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "id"),
                                                StringArgumentType.getString(context, "mode")
                                        )))))

                .then(Commands.literal("respawnDelay")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(0))
                                        .executes(context -> setRespawnDelay(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "id"),
                                                IntegerArgumentType.getInteger(context, "seconds")
                                        )))))

                .then(Commands.literal("mob")
                        .then(Commands.literal("add")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                        .then(Commands.argument("entity", ResourceLocationArgument.id())
                                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getEntityIds(), builder))
                                                .then(Commands.argument("weight", IntegerArgumentType.integer(1))
                                                        .executes(context -> mobAdd(
                                                                context.getSource(),
                                                                StringArgumentType.getString(context, "id"),
                                                                ResourceLocationArgument.getId(context, "entity"),
                                                                IntegerArgumentType.getInteger(context, "weight")
                                                        ))))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                        .then(Commands.argument("entity", ResourceLocationArgument.id())
                                                .suggests(MMSpawnCommands::suggestConfiguredNormalMobsForZone)
                                                .executes(context -> mobRemove(
                                                        context.getSource(),
                                                        StringArgumentType.getString(context, "id"),
                                                        ResourceLocationArgument.getId(context, "entity")
                                                )))))
                        .then(Commands.literal("list")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                        .executes(context -> mobList(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "id")
                                        ))))
                        .then(Commands.literal("clear")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                        .executes(context -> mobClear(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "id")
                                        )))))
                        .then(createMobDropCommand())

                .then(Commands.literal("max")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                .then(Commands.argument("value", IntegerArgumentType.integer(0))
                                        .executes(context -> setMax(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "id"),
                                                IntegerArgumentType.getInteger(context, "value")
                                        )))))

                .then(Commands.literal("spawnInterval")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(1))
                                        .executes(context -> setSpawnInterval(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "id"),
                                                IntegerArgumentType.getInteger(context, "seconds")
                                        )))))

                .then(Commands.literal("start")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                .executes(context -> setActive(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "id"),
                                        true
                                ))))

                .then(Commands.literal("stop")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                .executes(context -> setActive(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "id"),
                                        false
                                ))))

                .then(Commands.literal("show")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                .executes(context -> show(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "id"),
                                        10
                                ))
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 120))
                                        .executes(context -> show(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "id"),
                                                IntegerArgumentType.getInteger(context, "seconds")
                                        )))))

                .then(Commands.literal("tp")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                .executes(context -> teleport(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "id")
                                ))))

                .then(Commands.literal("point")
                        .then(Commands.literal("add")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                        .executes(context -> pointAdd(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "id")
                                        ))))
                        .then(Commands.literal("clear")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                        .executes(context -> pointClear(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "id")
                                        )))))

                .then(Commands.literal("clear")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                .executes(context -> clearActive(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "id")
                                ))))

                .then(Commands.literal("force")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                .executes(context -> force(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "id")
                                ))));

        if (CobblemonCompat.isCobblemonLoaded()) {
            root.then(createPokemonCommand());
        }

        dispatcher.register(root);
    }

    /**
     * Comandos especiales de Cobblemon.
     * Solo existen si Cobblemon está cargado.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> createPokemonCommand() {
        return Commands.literal("pokemon")
                .then(Commands.literal("add")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                .then(Commands.argument("weight", IntegerArgumentType.integer(1))
                                        .then(Commands.argument("properties", CobblemonCompat.pokemonPropertiesArgument())
                                                .executes(context -> pokemonAdd(
                                                        context.getSource(),
                                                        StringArgumentType.getString(context, "id"),
                                                        IntegerArgumentType.getInteger(context, "weight"),
                                                        getRawArgument(context, "properties")
                                                ))))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                .then(Commands.argument("properties", StringArgumentType.greedyString())
                                        .suggests(MMSpawnCommands::suggestConfiguredPokemonForZone)
                                        .executes(context -> pokemonRemove(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "id"),
                                                getRawArgument(context, "properties")
                                        )))))
                .then(Commands.literal("list")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                .executes(context -> pokemonList(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "id")
                                ))))
                .then(createPokemonDropCommand());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createMobDropCommand() {
        return Commands.literal("drop")
                .then(Commands.literal("add")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                .then(Commands.argument("entity", ResourceLocationArgument.id())
                                        .suggests(MMSpawnCommands::suggestConfiguredNormalMobsForZone)
                                        .then(Commands.argument("item", ResourceLocationArgument.id())
                                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getItemIds(), builder))
                                                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                                        .executes(context -> mobDropAdd(
                                                                context.getSource(),
                                                                StringArgumentType.getString(context, "id"),
                                                                ResourceLocationArgument.getId(context, "entity"),
                                                                ResourceLocationArgument.getId(context, "item"),
                                                                IntegerArgumentType.getInteger(context, "count")
                                                        )))))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                .then(Commands.argument("entity", ResourceLocationArgument.id())
                                        .suggests(MMSpawnCommands::suggestConfiguredNormalMobsForZone)
                                        .then(Commands.argument("item", ResourceLocationArgument.id())
                                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getItemIds(), builder))
                                                .executes(context -> mobDropRemove(
                                                        context.getSource(),
                                                        StringArgumentType.getString(context, "id"),
                                                        ResourceLocationArgument.getId(context, "entity"),
                                                        ResourceLocationArgument.getId(context, "item")
                                                ))))))
                .then(Commands.literal("list")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                .then(Commands.argument("entity", ResourceLocationArgument.id())
                                        .suggests(MMSpawnCommands::suggestConfiguredNormalMobsForZone)
                                        .executes(context -> mobDropList(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "id"),
                                                ResourceLocationArgument.getId(context, "entity")
                                        )))))
                .then(Commands.literal("clear")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                .then(Commands.argument("entity", ResourceLocationArgument.id())
                                        .suggests(MMSpawnCommands::suggestConfiguredNormalMobsForZone)
                                        .executes(context -> mobDropClear(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "id"),
                                                ResourceLocationArgument.getId(context, "entity")
                                        )))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> createPokemonDropCommand() {
        return Commands.literal("drop")
                .then(Commands.literal("add")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                .then(Commands.argument("item", ResourceLocationArgument.id())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getItemIds(), builder))
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                                .then(Commands.argument("properties", StringArgumentType.greedyString())
                                                        .suggests(MMSpawnCommands::suggestConfiguredPokemonForZone)
                                                        .executes(context -> pokemonDropAdd(
                                                                context.getSource(),
                                                                StringArgumentType.getString(context, "id"),
                                                                ResourceLocationArgument.getId(context, "item"),
                                                                IntegerArgumentType.getInteger(context, "count"),
                                                                getRawArgument(context, "properties")
                                                        )))))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                .then(Commands.argument("item", ResourceLocationArgument.id())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getItemIds(), builder))
                                        .then(Commands.argument("properties", StringArgumentType.greedyString())
                                                .suggests(MMSpawnCommands::suggestConfiguredPokemonForZone)
                                                .executes(context -> pokemonDropRemove(
                                                        context.getSource(),
                                                        StringArgumentType.getString(context, "id"),
                                                        ResourceLocationArgument.getId(context, "item"),
                                                        getRawArgument(context, "properties")
                                                ))))))
                .then(Commands.literal("list")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                .then(Commands.argument("properties", StringArgumentType.greedyString())
                                        .suggests(MMSpawnCommands::suggestConfiguredPokemonForZone)
                                        .executes(context -> pokemonDropList(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "id"),
                                                getRawArgument(context, "properties")
                                        )))))
                .then(Commands.literal("clear")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                .then(Commands.argument("properties", StringArgumentType.greedyString())
                                        .suggests(MMSpawnCommands::suggestConfiguredPokemonForZone)
                                        .executes(context -> pokemonDropClear(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "id"),
                                                getRawArgument(context, "properties")
                                        )))));
    }

    private static int create(CommandSourceStack source, String id) {
        if (MMSpawnManager.exists(id)) {
            fail(source, "Ya existe una zona con el ID: " + id);
            return 0;
        }

        SpawnZone zone = MMSpawnManager.createZone(id);

        try {
            ServerPlayer player = source.getPlayerOrException();
            zone.setCenter(player.getX(), player.getY(), player.getZ());
            zone.setDimension(player.serverLevel().dimension().location().toString());
            MMSpawnManager.save();
        } catch (Exception ignored) {
        }

        success(source, "Zona creada: " + id);
        return 1;
    }

    private static int delete(CommandSourceStack source, String id) {
        boolean deleted = MMSpawnManager.deleteZone(source.getServer(), id);

        if (!deleted) {
            fail(source, "No existe la zona: " + id);
            return 0;
        }

        success(source, "Zona eliminada completamente: " + id);
        return 1;
    }

    private static int list(CommandSourceStack source) {
        if (MMSpawnManager.getZoneIds().isEmpty()) {
            success(source, "No hay zonas creadas.");
            return 1;
        }

        success(source, "Zonas: " + String.join(", ", MMSpawnManager.getZoneIds()));
        return 1;
    }

    private static int info(CommandSourceStack source, String id) {
        SpawnZone zone = getZoneOrFail(source, id);

        if (zone == null) {
            return 0;
        }

        success(source, "Zona: " + zone.getId());
        success(source, "Activa: " + zone.isActive());
        success(source, "Dimensión: " + zone.getDimension());
        success(source, "Centro: " + zone.getCenterX() + ", " + zone.getCenterY() + ", " + zone.getCenterZ());
        success(source, "Radio: " + zone.getRadius());
        success(source, "Altura: " + zone.getYMin() + " - " + zone.getYMax());
        success(source, "Modo de altura: " + zone.getHeightMode());
        success(source, "Modo de respawn: " + zone.getRespawnMode().name().toLowerCase());
        success(source, "Respawn delay: " + zone.getRespawnDelaySeconds() + " segundos");
        success(source, "Spawn interval: " + zone.getSpawnIntervalSeconds() + " segundos");
        success(source, "Máximo vivo: " + zone.getMaxAlive());
        success(source, "Mobs vivos registrados: " + zone.getActiveMobUuids().size());
        success(source, "Mobs configurados: " + zone.getMobs().size());
        success(source, "Puntos manuales: " + zone.getManualPoints().size());

        long remaining = MMSpawnManager.getRespawnRemainingSeconds(zone);
        if (remaining >= 0L) {
            success(source, "Cooldown restante: " + remaining + " segundos");
        }

        return 1;
    }

    private static int center(CommandSourceStack source, String id) {
        SpawnZone zone = getZoneOrFail(source, id);

        if (zone == null) {
            return 0;
        }

        try {
            ServerPlayer player = source.getPlayerOrException();
            zone.setCenter(player.getX(), player.getY(), player.getZ());
            zone.setDimension(player.serverLevel().dimension().location().toString());
            MMSpawnManager.save();
            success(source, "Centro actualizado para " + id + ".");
            return 1;
        } catch (Exception e) {
            fail(source, "Este comando debe ejecutarlo un jugador.");
            return 0;
        }
    }

    private static int setRadius(CommandSourceStack source, String id, int value) {
        SpawnZone zone = getZoneOrFail(source, id);
        if (zone == null) return 0;
        zone.setRadius(value);
        MMSpawnManager.save();
        success(source, "Radio de " + id + " actualizado a " + value + ".");
        return 1;
    }

    private static int setYMin(CommandSourceStack source, String id, int value) {
        SpawnZone zone = getZoneOrFail(source, id);
        if (zone == null) return 0;
        zone.setYMin(value);
        MMSpawnManager.save();
        success(source, "Y mínima de " + id + " actualizada a " + value + ".");
        return 1;
    }

    private static int setYMax(CommandSourceStack source, String id, int value) {
        SpawnZone zone = getZoneOrFail(source, id);
        if (zone == null) return 0;
        zone.setYMax(value);
        MMSpawnManager.save();
        success(source, "Y máxima de " + id + " actualizada a " + value + ".");
        return 1;
    }

    private static int setHeightMode(CommandSourceStack source, String id, String modeText) {
        SpawnZone zone = getZoneOrFail(source, id);
        if (zone == null) return 0;
        HeightMode mode = HeightMode.fromString(modeText);
        zone.setHeightMode(mode);
        MMSpawnManager.save();
        success(source, "Modo de altura de " + id + " actualizado a " + mode.name().toLowerCase() + ".");
        return 1;
    }

    private static int setRespawnMode(CommandSourceStack source, String id, String modeText) {
        SpawnZone zone = getZoneOrFail(source, id);
        if (zone == null) return 0;
        RespawnMode mode = RespawnMode.fromString(modeText);
        zone.setRespawnMode(mode);
        MMSpawnManager.save();
        success(source, "Modo de respawn de " + id + " actualizado a " + mode.name().toLowerCase() + ".");
        return 1;
    }

    private static int setRespawnDelay(CommandSourceStack source, String id, int seconds) {
        SpawnZone zone = getZoneOrFail(source, id);
        if (zone == null) return 0;
        zone.setRespawnDelaySeconds(seconds);
        MMSpawnManager.save();
        success(source, "Respawn delay de " + id + " actualizado a " + seconds + " segundos.");
        return 1;
    }

    private static int mobAdd(CommandSourceStack source, String id, ResourceLocation entityId, int weight) {
        SpawnZone zone = getZoneOrFail(source, id);
        if (zone == null) return 0;
        zone.addMob(entityId.toString(), weight);
        MMSpawnManager.save();
        success(source, "Mob agregado a " + id + ": " + entityId + " con peso " + weight + ".");
        return 1;
    }

    private static int mobRemove(CommandSourceStack source, String id, ResourceLocation entityId) {
        SpawnZone zone = getZoneOrFail(source, id);
        if (zone == null) return 0;
        zone.removeMob(entityId.toString());
        MMSpawnManager.save();
        success(source, "Mob eliminado de " + id + ": " + entityId + ".");
        return 1;
    }

    private static int mobList(CommandSourceStack source, String id) {
        SpawnZone zone = getZoneOrFail(source, id);
        if (zone == null) return 0;

        boolean found = false;
        success(source, "Mobs normales configurados en " + id + ":");

        for (SpawnMobEntry entry : zone.getMobs()) {
            if (CobblemonCompat.isCobblemonPokemonEntry(entry.getEntityId())) {
                continue;
            }

            found = true;
            success(source, "- " + entry.getEntityId() + " | peso: " + entry.getWeight());
        }

        if (!found) {
            success(source, "No hay mobs normales configurados.");
        }

        return 1;
    }

    private static int mobClear(CommandSourceStack source, String id) {
        SpawnZone zone = getZoneOrFail(source, id);
        if (zone == null) return 0;
        zone.clearMobs();
        MMSpawnManager.save();
        success(source, "Lista de mobs limpiada para " + id + ".");
        return 1;
    }

    private static int pokemonAdd(CommandSourceStack source, String id, int weight, String properties) {
        SpawnZone zone = getZoneOrFail(source, id);
        if (zone == null) return 0;

        String cleanProperties = CobblemonCompat.normalizeProperties(properties);

        if (cleanProperties.isBlank()) {
            fail(source, "Debes indicar las propiedades del Pokémon. Ejemplo: pikachu level=25 shiny");
            return 0;
        }

        String storedEntry = CobblemonCompat.toStoredPokemonEntry(cleanProperties);
        zone.addMob(storedEntry, weight);
        MMSpawnManager.save();
        success(source, "Pokémon agregado a " + id + ": " + cleanProperties + " con peso " + weight + ".");
        return 1;
    }

    private static int pokemonRemove(CommandSourceStack source, String id, String properties) {
        SpawnZone zone = getZoneOrFail(source, id);
        if (zone == null) return 0;

        String cleanProperties = CobblemonCompat.normalizeProperties(properties);

        if (cleanProperties.isBlank()) {
            fail(source, "Debes indicar las propiedades del Pokémon a eliminar.");
            return 0;
        }

        String storedEntry = CobblemonCompat.toStoredPokemonEntry(cleanProperties);
        zone.removeMob(storedEntry);
        MMSpawnManager.save();
        success(source, "Pokémon eliminado de " + id + ": " + cleanProperties + ".");
        return 1;
    }

    private static int pokemonList(CommandSourceStack source, String id) {
        SpawnZone zone = getZoneOrFail(source, id);
        if (zone == null) return 0;

        boolean found = false;
        success(source, "Pokémon configurados en " + id + ":");

        for (SpawnMobEntry entry : zone.getMobs()) {
            if (!CobblemonCompat.isCobblemonPokemonEntry(entry.getEntityId())) {
                continue;
            }

            found = true;
            success(source, "- " + CobblemonCompat.toPokemonProperties(entry.getEntityId()) + " | peso: " + entry.getWeight());
        }

        if (!found) {
            success(source, "No hay Pokémon configurados.");
        }

        return 1;
    }

    private static CompletableFuture<Suggestions> suggestConfiguredPokemonForZone(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        String zoneId;

        try {
            zoneId = StringArgumentType.getString(context, "id");
        } catch (IllegalArgumentException exception) {
            return builder.buildFuture();
        }

        SpawnZone zone = MMSpawnManager.getZone(zoneId);

        if (zone == null) {
            return builder.buildFuture();
        }

        List<String> suggestions = new ArrayList<>();

        for (SpawnMobEntry entry : zone.getMobs()) {
            if (CobblemonCompat.isCobblemonPokemonEntry(entry.getEntityId())) {
                suggestions.add(CobblemonCompat.toPokemonProperties(entry.getEntityId()));
            }
        }

        return SharedSuggestionProvider.suggest(suggestions, builder);
    }

    private static CompletableFuture<Suggestions> suggestConfiguredNormalMobsForZone(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        String zoneId;

        try {
            zoneId = StringArgumentType.getString(context, "id");
        } catch (IllegalArgumentException exception) {
            return builder.buildFuture();
        }

        SpawnZone zone = MMSpawnManager.getZone(zoneId);

        if (zone == null) {
            return builder.buildFuture();
        }

        List<String> suggestions = new ArrayList<>();

        for (SpawnMobEntry entry : zone.getMobs()) {
            if (!CobblemonCompat.isCobblemonPokemonEntry(entry.getEntityId())) {
                suggestions.add(entry.getEntityId());
            }
        }

        return SharedSuggestionProvider.suggest(suggestions, builder);
    }

    private static String getRawArgument(CommandContext<CommandSourceStack> context, String argumentName) {
        for (ParsedCommandNode<CommandSourceStack> node : context.getNodes()) {
            if (node.getNode().getName().equals(argumentName)) {
                return node.getRange().get(context.getInput()).trim();
            }
        }

        return "";
    }

    private static int mobDropAdd(CommandSourceStack source, String id, ResourceLocation entityId, ResourceLocation itemId, int count) {
        SpawnZone zone = getZoneOrFail(source, id);
        if (zone == null) return 0;

        SpawnMobEntry entry = findConfiguredEntry(zone, entityId.toString(), false);
        if (entry == null) {
            fail(source, "Ese mob no está configurado en la zona: " + entityId);
            return 0;
        }

        if (!MMSpawnManager.isValidItemId(itemId)) {
            fail(source, "Item inválido o no registrado: " + itemId);
            return 0;
        }

        entry.addDrop(itemId.toString(), count);
        MMSpawnManager.save();
        success(source, "Drop garantizado agregado: " + count + "x " + itemId + " para " + entityId + ".");
        return 1;
    }

    private static int mobDropRemove(CommandSourceStack source, String id, ResourceLocation entityId, ResourceLocation itemId) {
        SpawnZone zone = getZoneOrFail(source, id);
        if (zone == null) return 0;

        SpawnMobEntry entry = findConfiguredEntry(zone, entityId.toString(), false);
        if (entry == null) {
            fail(source, "Ese mob no está configurado en la zona: " + entityId);
            return 0;
        }

        if (!entry.removeDrop(itemId.toString())) {
            fail(source, "Ese drop no estaba configurado: " + itemId);
            return 0;
        }

        MMSpawnManager.save();
        success(source, "Drop eliminado de " + entityId + ": " + itemId + ".");
        return 1;
    }

    private static int mobDropList(CommandSourceStack source, String id, ResourceLocation entityId) {
        SpawnZone zone = getZoneOrFail(source, id);
        if (zone == null) return 0;

        SpawnMobEntry entry = findConfiguredEntry(zone, entityId.toString(), false);
        if (entry == null) {
            fail(source, "Ese mob no está configurado en la zona: " + entityId);
            return 0;
        }

        return showDrops(source, entityId.toString(), entry);
    }

    private static int mobDropClear(CommandSourceStack source, String id, ResourceLocation entityId) {
        SpawnZone zone = getZoneOrFail(source, id);
        if (zone == null) return 0;

        SpawnMobEntry entry = findConfiguredEntry(zone, entityId.toString(), false);
        if (entry == null) {
            fail(source, "Ese mob no está configurado en la zona: " + entityId);
            return 0;
        }

        entry.clearDrops();
        MMSpawnManager.save();
        success(source, "Drops limpiados para " + entityId + ".");
        return 1;
    }

    private static int pokemonDropAdd(CommandSourceStack source, String id, ResourceLocation itemId, int count, String properties) {
        SpawnZone zone = getZoneOrFail(source, id);
        if (zone == null) return 0;

        SpawnMobEntry entry = getPokemonEntryOrFail(source, zone, properties);
        if (entry == null) return 0;

        if (!MMSpawnManager.isValidItemId(itemId)) {
            fail(source, "Item inválido o no registrado: " + itemId);
            return 0;
        }

        entry.addDrop(itemId.toString(), count);
        MMSpawnManager.save();
        success(source, "Drop garantizado agregado: " + count + "x " + itemId + " para " + CobblemonCompat.toPokemonProperties(entry.getEntityId()) + ".");
        return 1;
    }

    private static int pokemonDropRemove(CommandSourceStack source, String id, ResourceLocation itemId, String properties) {
        SpawnZone zone = getZoneOrFail(source, id);
        if (zone == null) return 0;

        SpawnMobEntry entry = getPokemonEntryOrFail(source, zone, properties);
        if (entry == null) return 0;

        if (!entry.removeDrop(itemId.toString())) {
            fail(source, "Ese drop no estaba configurado: " + itemId);
            return 0;
        }

        MMSpawnManager.save();
        success(source, "Drop eliminado: " + itemId + ".");
        return 1;
    }

    private static int pokemonDropList(CommandSourceStack source, String id, String properties) {
        SpawnZone zone = getZoneOrFail(source, id);
        if (zone == null) return 0;

        SpawnMobEntry entry = getPokemonEntryOrFail(source, zone, properties);
        if (entry == null) return 0;

        return showDrops(source, CobblemonCompat.toPokemonProperties(entry.getEntityId()), entry);
    }

    private static int pokemonDropClear(CommandSourceStack source, String id, String properties) {
        SpawnZone zone = getZoneOrFail(source, id);
        if (zone == null) return 0;

        SpawnMobEntry entry = getPokemonEntryOrFail(source, zone, properties);
        if (entry == null) return 0;

        entry.clearDrops();
        MMSpawnManager.save();
        success(source, "Drops limpiados para " + CobblemonCompat.toPokemonProperties(entry.getEntityId()) + ".");
        return 1;
    }

    private static SpawnMobEntry getPokemonEntryOrFail(CommandSourceStack source, SpawnZone zone, String properties) {
        String clean = CobblemonCompat.normalizeProperties(properties);

        if (clean.isBlank()) {
            fail(source, "Debes indicar el Pokémon configurado.");
            return null;
        }

        String storedEntry = CobblemonCompat.toStoredPokemonEntry(clean);
        SpawnMobEntry entry = findConfiguredEntry(zone, storedEntry, true);

        if (entry == null) {
            fail(source, "Ese Pokémon no está configurado exactamente en la zona: " + clean);
        }

        return entry;
    }

    private static SpawnMobEntry findConfiguredEntry(SpawnZone zone, String entityId, boolean pokemon) {
        for (SpawnMobEntry entry : zone.getMobs()) {
            boolean isPokemon = CobblemonCompat.isCobblemonPokemonEntry(entry.getEntityId());

            if (isPokemon == pokemon && entry.getEntityId().equalsIgnoreCase(entityId)) {
                return entry;
            }
        }

        return null;
    }

    private static int showDrops(CommandSourceStack source, String displayName, SpawnMobEntry entry) {
        if (entry.getDrops().isEmpty()) {
            success(source, "No hay drops configurados para " + displayName + ".");
            return 1;
        }

        success(source, "Drops garantizados de " + displayName + ":");

        for (SpawnDropEntry drop : entry.getDrops()) {
            success(source, "- " + drop.getCount() + "x " + drop.getItemId());
        }

        return 1;
    }

    private static int setMax(CommandSourceStack source, String id, int value) {
        SpawnZone zone = getZoneOrFail(source, id);
        if (zone == null) return 0;
        zone.setMaxAlive(value);
        MMSpawnManager.save();
        success(source, "Máximo de mobs vivos de " + id + " actualizado a " + value + ".");
        return 1;
    }

    private static int setSpawnInterval(CommandSourceStack source, String id, int seconds) {
        SpawnZone zone = getZoneOrFail(source, id);
        if (zone == null) return 0;
        zone.setSpawnIntervalSeconds(seconds);
        MMSpawnManager.save();
        success(source, "Spawn interval de " + id + " actualizado a " + seconds + " segundos.");
        return 1;
    }

    private static int setActive(CommandSourceStack source, String id, boolean active) {
        SpawnZone zone = getZoneOrFail(source, id);
        if (zone == null) return 0;
        zone.setActive(active);
        MMSpawnManager.save();
        success(source, active ? "Zona activada: " + id : "Zona detenida: " + id);
        return 1;
    }

    private static int show(CommandSourceStack source, String id, int seconds) {
        SpawnZone zone = getZoneOrFail(source, id);
        if (zone == null) return 0;

        ServerLevel level = MMSpawnManager.getLevel(source.getServer(), zone);

        if (level == null) {
            fail(source, "No se pudo encontrar la dimensión de la zona.");
            return 0;
        }

        MMSpawnManager.showZone(level, zone, seconds);
        success(source, "Mostrando zona con partículas durante " + seconds + " segundos: " + id);
        return 1;
    }

    private static int teleport(CommandSourceStack source, String id) {
        SpawnZone zone = getZoneOrFail(source, id);
        if (zone == null) return 0;

        try {
            ServerPlayer player = source.getPlayerOrException();
            ServerLevel level = MMSpawnManager.getLevel(source.getServer(), zone);

            if (level == null) {
                fail(source, "No se pudo encontrar la dimensión de la zona.");
                return 0;
            }

            player.teleportTo(level, zone.getCenterX(), zone.getCenterY(), zone.getCenterZ(), player.getYRot(), player.getXRot());
            success(source, "Teletransportado al centro de " + id + ".");
            return 1;
        } catch (Exception e) {
            fail(source, "Este comando debe ejecutarlo un jugador.");
            return 0;
        }
    }

    private static int pointAdd(CommandSourceStack source, String id) {
        SpawnZone zone = getZoneOrFail(source, id);
        if (zone == null) return 0;

        try {
            ServerPlayer player = source.getPlayerOrException();
            BlockPos pos = player.blockPosition();
            zone.addManualPoint(new SpawnPointData(pos));
            MMSpawnManager.save();
            success(source, "Punto manual agregado a " + id + ": " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
            return 1;
        } catch (Exception e) {
            fail(source, "Este comando debe ejecutarlo un jugador.");
            return 0;
        }
    }

    private static int pointClear(CommandSourceStack source, String id) {
        SpawnZone zone = getZoneOrFail(source, id);
        if (zone == null) return 0;
        zone.clearManualPoints();
        MMSpawnManager.save();
        success(source, "Puntos manuales limpiados para " + id + ".");
        return 1;
    }

    private static int clearActive(CommandSourceStack source, String id) {
        SpawnZone zone = getZoneOrFail(source, id);
        if (zone == null) return 0;
        MMSpawnManager.clearActiveMobs(source.getServer(), zone);
        success(source, "Mobs activos limpiados de " + id + ".");
        return 1;
    }

    private static int force(CommandSourceStack source, String id) {
        SpawnZone zone = getZoneOrFail(source, id);
        if (zone == null) return 0;

        ServerLevel level = MMSpawnManager.getLevel(source.getServer(), zone);

        if (level == null) {
            fail(source, "No se pudo encontrar la dimensión de la zona.");
            return 0;
        }

        int spawned = MMSpawnManager.forceRespawn(level, zone);
        success(source, "Respawn forzado en " + id + ". Mobs generados: " + spawned);
        return 1;
    }

    private static SpawnZone getZoneOrFail(CommandSourceStack source, String id) {
        SpawnZone zone = MMSpawnManager.getZone(id);

        if (zone == null) {
            fail(source, "No existe la zona: " + id);
            return null;
        }

        return zone;
    }

    private static void success(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal("§a[MMSpawn] §f" + message), false);
    }

    private static void fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal("§c[MMSpawn] §f" + message));
    }
}
