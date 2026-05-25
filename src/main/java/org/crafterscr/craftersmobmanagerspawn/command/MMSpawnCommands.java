package org.crafterscr.craftersmobmanagerspawn.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
import org.crafterscr.craftersmobmanagerspawn.data.SpawnPointData;
import org.crafterscr.craftersmobmanagerspawn.data.SpawnZone;
import org.crafterscr.craftersmobmanagerspawn.logic.MMSpawnManager;
import org.crafterscr.craftersmobmanagerspawn.util.HeightMode;

/**
 * Registra y ejecuta todos los comandos del mod.
 *
 * Todos los comandos cuelgan de /mmspawn y están limitados a OP/admin
 * usando source.hasPermission(2).
 */
public class MMSpawnCommands {

    /**
     * Evento de NeoForge que permite registrar comandos del servidor.
     */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    /**
     * Construye el árbol completo de comandos.
     * Aquí se definen subcomandos, argumentos y autocompletados.
     */
    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("mmspawn")
                        .requires(source -> source.hasPermission(2))

                        .then(Commands.literal("create")
                                .then(Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .executes(context -> create(
                                                context.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(context, "id")
                                        ))))

                        .then(Commands.literal("delete")
                                .then(Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                        .executes(context -> delete(
                                                context.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(context, "id")
                                        ))))

                        .then(Commands.literal("list")
                                .executes(context -> list(context.getSource())))

                        .then(Commands.literal("info")
                                .then(Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                        .executes(context -> info(
                                                context.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(context, "id")
                                        ))))

                        .then(Commands.literal("center")
                                .then(Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                        .executes(context -> center(
                                                context.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(context, "id")
                                        ))))

                        .then(Commands.literal("radius")
                                .then(Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                        .then(Commands.argument("value", IntegerArgumentType.integer(1))
                                                .executes(context -> setRadius(
                                                        context.getSource(),
                                                        com.mojang.brigadier.arguments.StringArgumentType.getString(context, "id"),
                                                        IntegerArgumentType.getInteger(context, "value")
                                                )))))

                        .then(Commands.literal("ymin")
                                .then(Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                        .then(Commands.argument("value", IntegerArgumentType.integer(-64, 320))
                                                .executes(context -> setYMin(
                                                        context.getSource(),
                                                        com.mojang.brigadier.arguments.StringArgumentType.getString(context, "id"),
                                                        IntegerArgumentType.getInteger(context, "value")
                                                )))))

                        .then(Commands.literal("ymax")
                                .then(Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                        .then(Commands.argument("value", IntegerArgumentType.integer(-64, 320))
                                                .executes(context -> setYMax(
                                                        context.getSource(),
                                                        com.mojang.brigadier.arguments.StringArgumentType.getString(context, "id"),
                                                        IntegerArgumentType.getInteger(context, "value")
                                                )))))

                        .then(Commands.literal("heightmode")
                                .then(Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                        .then(Commands.argument("mode", com.mojang.brigadier.arguments.StringArgumentType.word())
                                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(new String[]{"ground", "floor", "exact", "air"}, builder))
                                                .executes(context -> setHeightMode(
                                                        context.getSource(),
                                                        com.mojang.brigadier.arguments.StringArgumentType.getString(context, "id"),
                                                        com.mojang.brigadier.arguments.StringArgumentType.getString(context, "mode")
                                                )))))

                        .then(Commands.literal("mob")
                                .then(Commands.literal("add")
                                        .then(Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                                .then(Commands.argument("entity", ResourceLocationArgument.id())
                                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getEntityIds(), builder))
                                                        .then(Commands.argument("weight", IntegerArgumentType.integer(1))
                                                                .executes(context -> mobAdd(
                                                                        context.getSource(),
                                                                        com.mojang.brigadier.arguments.StringArgumentType.getString(context, "id"),
                                                                        ResourceLocationArgument.getId(context, "entity"),
                                                                        IntegerArgumentType.getInteger(context, "weight")
                                                                ))))))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                                .then(Commands.argument("entity", ResourceLocationArgument.id())
                                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getEntityIds(), builder))
                                                        .executes(context -> mobRemove(
                                                                context.getSource(),
                                                                com.mojang.brigadier.arguments.StringArgumentType.getString(context, "id"),
                                                                ResourceLocationArgument.getId(context, "entity")
                                                        )))))
                                .then(Commands.literal("clear")
                                        .then(Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                                .executes(context -> mobClear(
                                                        context.getSource(),
                                                        com.mojang.brigadier.arguments.StringArgumentType.getString(context, "id")
                                                )))))

                        .then(Commands.literal("max")
                                .then(Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                        .then(Commands.argument("value", IntegerArgumentType.integer(0))
                                                .executes(context -> setMax(
                                                        context.getSource(),
                                                        com.mojang.brigadier.arguments.StringArgumentType.getString(context, "id"),
                                                        IntegerArgumentType.getInteger(context, "value")
                                                )))))

                        // emptyDelay deja una sola espera antes de reponer mobs.
// La zona debe estar vacía este tiempo antes de reponer mobs.
                        .then(Commands.literal("emptyDelay")
                                .then(Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                        .then(Commands.argument("seconds", IntegerArgumentType.integer(0))
                                                .executes(context -> setEmptyDelay(
                                                        context.getSource(),
                                                        com.mojang.brigadier.arguments.StringArgumentType.getString(context, "id"),
                                                        IntegerArgumentType.getInteger(context, "seconds")
                                                )))))

                        // spawnInterval controla el respawn progresivo: 1 mob cada X segundos.
                        .then(Commands.literal("spawnInterval")
                                .then(Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1))
                                                .executes(context -> setSpawnInterval(
                                                        context.getSource(),
                                                        com.mojang.brigadier.arguments.StringArgumentType.getString(context, "id"),
                                                        IntegerArgumentType.getInteger(context, "seconds")
                                                )))))

                        .then(Commands.literal("start")
                                .then(Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                        .executes(context -> setActive(
                                                context.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(context, "id"),
                                                true
                                        ))))

                        .then(Commands.literal("stop")
                                .then(Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                        .executes(context -> setActive(
                                                context.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(context, "id"),
                                                false
                                        ))))

                        // show ahora puede recibir segundos: /mmspawn show <id> [segundos].
                        .then(Commands.literal("show")
                                .then(Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                        .executes(context -> show(
                                                context.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(context, "id"),
                                                10
                                        ))
                                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 120))
                                                .executes(context -> show(
                                                        context.getSource(),
                                                        com.mojang.brigadier.arguments.StringArgumentType.getString(context, "id"),
                                                        IntegerArgumentType.getInteger(context, "seconds")
                                                )))))

                        .then(Commands.literal("tp")
                                .then(Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                        .executes(context -> teleport(
                                                context.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(context, "id")
                                        ))))

                        .then(Commands.literal("point")
                                .then(Commands.literal("add")
                                        .then(Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                                .executes(context -> pointAdd(
                                                        context.getSource(),
                                                        com.mojang.brigadier.arguments.StringArgumentType.getString(context, "id")
                                                ))))
                                .then(Commands.literal("clear")
                                        .then(Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                                .executes(context -> pointClear(
                                                        context.getSource(),
                                                        com.mojang.brigadier.arguments.StringArgumentType.getString(context, "id")
                                                )))))

                        .then(Commands.literal("clear")
                                .then(Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                        .executes(context -> clearActive(
                                                context.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(context, "id")
                                        ))))

                        .then(Commands.literal("force")
                                .then(Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(MMSpawnManager.getZoneIds(), builder))
                                        .executes(context -> force(
                                                context.getSource(),
                                                com.mojang.brigadier.arguments.StringArgumentType.getString(context, "id")
                                        ))))
        );
    }

    /** Crea una zona nueva. Si lo ejecuta un jugador, también guarda su posición como centro. */
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

    /** Elimina completamente una zona y sus mobs asociados. */
    private static int delete(CommandSourceStack source, String id) {
        boolean deleted = MMSpawnManager.deleteZone(source.getServer(), id);

        if (!deleted) {
            fail(source, "No existe la zona: " + id);
            return 0;
        }

        success(source, "Zona eliminada completamente: " + id);
        return 1;
    }

    /** Muestra todos los IDs de zonas creadas. */
    private static int list(CommandSourceStack source) {
        if (MMSpawnManager.getZoneIds().isEmpty()) {
            success(source, "No hay zonas creadas.");
            return 1;
        }

        success(source, "Zonas: " + String.join(", ", MMSpawnManager.getZoneIds()));
        return 1;
    }

    /** Muestra información rápida de una zona. */
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
        success(source, "Modo: " + zone.getHeightMode());
        success(source, "Máximo vivo: " + zone.getMaxAlive());
        success(source, "Mobs vivos registrados: " + zone.getActiveMobUuids().size());
        success(source, "Mobs configurados: " + zone.getMobs().size());
        success(source, "Puntos manuales: " + zone.getManualPoints().size());
        success(source, "Empty delay: " + zone.getEmptyDelaySeconds() + " segundos");
        success(source, "Spawn interval: " + zone.getSpawnIntervalSeconds() + " segundos");

        return 1;
    }

    /** Guarda como centro de la zona la posición actual del jugador. */
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

    /** Cambia el radio horizontal de la zona. */
    private static int setRadius(CommandSourceStack source, String id, int value) {
        SpawnZone zone = getZoneOrFail(source, id);

        if (zone == null) {
            return 0;
        }

        zone.setRadius(value);
        MMSpawnManager.save();

        success(source, "Radio de " + id + " actualizado a " + value + ".");
        return 1;
    }

    /** Cambia la altura mínima permitida para la zona. */
    private static int setYMin(CommandSourceStack source, String id, int value) {
        SpawnZone zone = getZoneOrFail(source, id);

        if (zone == null) {
            return 0;
        }

        zone.setYMin(value);
        MMSpawnManager.save();

        success(source, "Y mínima de " + id + " actualizada a " + value + ".");
        return 1;
    }

    /** Cambia la altura máxima permitida para la zona. */
    private static int setYMax(CommandSourceStack source, String id, int value) {
        SpawnZone zone = getZoneOrFail(source, id);

        if (zone == null) {
            return 0;
        }

        zone.setYMax(value);
        MMSpawnManager.save();

        success(source, "Y máxima de " + id + " actualizada a " + value + ".");
        return 1;
    }

    /** Cambia el modo de altura: ground, floor, exact o air. */
    private static int setHeightMode(CommandSourceStack source, String id, String modeText) {
        SpawnZone zone = getZoneOrFail(source, id);

        if (zone == null) {
            return 0;
        }

        HeightMode mode = HeightMode.fromString(modeText);
        zone.setHeightMode(mode);
        MMSpawnManager.save();

        success(source, "Modo de altura de " + id + " actualizado a " + mode.name().toLowerCase() + ".");
        return 1;
    }

    /** Agrega un mob posible a la zona con su peso de aparición. */
    private static int mobAdd(CommandSourceStack source, String id, ResourceLocation entityId, int weight) {
        SpawnZone zone = getZoneOrFail(source, id);

        if (zone == null) {
            return 0;
        }

        zone.addMob(entityId.toString(), weight);
        MMSpawnManager.save();

        success(source, "Mob agregado a " + id + ": " + entityId + " con peso " + weight + ".");
        return 1;
    }

    /** Quita un mob específico de la lista de mobs posibles de la zona. */
    private static int mobRemove(CommandSourceStack source, String id, ResourceLocation entityId) {
        SpawnZone zone = getZoneOrFail(source, id);

        if (zone == null) {
            return 0;
        }

        zone.removeMob(entityId.toString());
        MMSpawnManager.save();

        success(source, "Mob eliminado de " + id + ": " + entityId + ".");
        return 1;
    }

    /** Limpia todos los mobs configurados en la zona. No elimina mobs vivos; para eso está /mmspawn clear. */
    private static int mobClear(CommandSourceStack source, String id) {
        SpawnZone zone = getZoneOrFail(source, id);

        if (zone == null) {
            return 0;
        }

        zone.clearMobs();
        MMSpawnManager.save();

        success(source, "Lista de mobs limpiada para " + id + ".");
        return 1;
    }

    /** Define cuántos mobs vivos como máximo puede mantener la zona. */
    private static int setMax(CommandSourceStack source, String id, int value) {
        SpawnZone zone = getZoneOrFail(source, id);

        if (zone == null) {
            return 0;
        }

        zone.setMaxAlive(value);
        MMSpawnManager.save();

        success(source, "Máximo de mobs vivos de " + id + " actualizado a " + value + ".");
        return 1;
    }

    /** Define cuántos segundos debe estar vacía la zona antes de reponer mobs. */
    private static int setEmptyDelay(CommandSourceStack source, String id, int seconds) {
        SpawnZone zone = getZoneOrFail(source, id);

        if (zone == null) {
            return 0;
        }

        zone.setEmptyDelaySeconds(seconds);
        MMSpawnManager.save();

        success(source, "Empty delay de " + id + " actualizado a " + seconds + " segundos.");
        return 1;
    }

    /** Define cada cuántos segundos aparece un mob durante el respawn progresivo. */
    private static int setSpawnInterval(CommandSourceStack source, String id, int seconds) {
        SpawnZone zone = getZoneOrFail(source, id);

        if (zone == null) {
            return 0;
        }

        zone.setSpawnIntervalSeconds(seconds);
        MMSpawnManager.save();

        success(source, "Spawn interval de " + id + " actualizado a " + seconds + " segundos.");
        return 1;
    }

    /** Activa o detiene una zona. */
    private static int setActive(CommandSourceStack source, String id, boolean active) {
        SpawnZone zone = getZoneOrFail(source, id);

        if (zone == null) {
            return 0;
        }

        zone.setActive(active);
        MMSpawnManager.save();

        success(source, active ? "Zona activada: " + id : "Zona detenida: " + id);
        return 1;
    }

    /** Muestra centro, radio y puntos manuales con partículas por varios segundos. */
    private static int show(CommandSourceStack source, String id, int seconds) {
        SpawnZone zone = getZoneOrFail(source, id);

        if (zone == null) {
            return 0;
        }

        ServerLevel level = MMSpawnManager.getLevel(source.getServer(), zone);

        if (level == null) {
            fail(source, "No se pudo encontrar la dimensión de la zona.");
            return 0;
        }

        MMSpawnManager.showZone(level, zone, seconds);
        success(source, "Mostrando zona con partículas durante " + seconds + " segundos: " + id);
        return 1;
    }

    /** Teletransporta al admin al centro de una zona. */
    private static int teleport(CommandSourceStack source, String id) {
        SpawnZone zone = getZoneOrFail(source, id);

        if (zone == null) {
            return 0;
        }

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

    /** Agrega la posición actual del jugador como punto manual de aparición. */
    private static int pointAdd(CommandSourceStack source, String id) {
        SpawnZone zone = getZoneOrFail(source, id);

        if (zone == null) {
            return 0;
        }

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

    /** Elimina todos los puntos manuales de una zona. */
    private static int pointClear(CommandSourceStack source, String id) {
        SpawnZone zone = getZoneOrFail(source, id);

        if (zone == null) {
            return 0;
        }

        zone.clearManualPoints();
        MMSpawnManager.save();

        success(source, "Puntos manuales limpiados para " + id + ".");
        return 1;
    }

    /** Elimina los mobs vivos asociados a una zona, pero no borra la zona. */
    private static int clearActive(CommandSourceStack source, String id) {
        SpawnZone zone = getZoneOrFail(source, id);

        if (zone == null) {
            return 0;
        }

        MMSpawnManager.clearActiveMobs(source.getServer(), zone);
        success(source, "Mobs activos limpiados de " + id + ".");
        return 1;
    }

    /** Fuerza el respawn hasta llenar la zona al máximo configurado. */
    private static int force(CommandSourceStack source, String id) {
        SpawnZone zone = getZoneOrFail(source, id);

        if (zone == null) {
            return 0;
        }

        ServerLevel level = MMSpawnManager.getLevel(source.getServer(), zone);

        if (level == null) {
            fail(source, "No se pudo encontrar la dimensión de la zona.");
            return 0;
        }

        int spawned = MMSpawnManager.forceRespawn(level, zone);
        success(source, "Respawn forzado en " + id + ". Mobs generados: " + spawned);
        return 1;
    }

    /** Busca una zona; si no existe, envía un error al admin. */
    private static SpawnZone getZoneOrFail(CommandSourceStack source, String id) {
        SpawnZone zone = MMSpawnManager.getZone(id);

        if (zone == null) {
            fail(source, "No existe la zona: " + id);
            return null;
        }

        return zone;
    }

    /** Mensaje verde de éxito para comandos. */
    private static void success(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal("§a[MMSpawn] §f" + message), false);
    }

    /** Mensaje rojo de error para comandos. */
    private static void fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal("§c[MMSpawn] §f" + message));
    }
}