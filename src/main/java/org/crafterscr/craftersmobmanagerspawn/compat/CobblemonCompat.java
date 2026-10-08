package org.crafterscr.craftersmobmanagerspawn.compat;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.crafterscr.craftersmobmanagerspawn.logic.MMSpawnManager;

/**
 * Compatibilidad opcional con Cobblemon.
 *
 * Esta clase NO importa clases de Cobblemon directamente.
 * Todo se hace por strings, comandos o reflexión.
 *
 * Así el mod puede abrir aunque Cobblemon NO esté instalado.
 */
public class CobblemonCompat {

    private static final String COBBLEMON_MOD_ID = "cobblemon";
    private static final String COBBLEMON_POKEMON_ENTITY_ID = "cobblemon:pokemon";

    // Se registra una sola vez por proceso. Cobblemon mantiene el listener en su observable global.
    private static boolean battleFaintedListenerRegistered = false;
    private static boolean battleVictoryListenerRegistered = false;

    private CobblemonCompat() {
    }

    /**
     * Revisa si Cobblemon está cargado.
     */
    public static boolean isCobblemonLoaded() {
        return ModList.get().isLoaded(COBBLEMON_MOD_ID);
    }

    /**
     * Devuelve true si esta entrada debe tratarse como Pokémon de Cobblemon.
     *
     * Ejemplos válidos:
     * cobblemon:pikachu
     * cobblemon:pikachu shiny
     * cobblemon:pikachu level=25 shiny
     */
    public static boolean isCobblemonPokemonEntry(String entryId) {
        if (entryId == null) {
            return false;
        }

        String lower = entryId.toLowerCase(Locale.ROOT).trim();

        return lower.startsWith("cobblemon:")
                && !lower.equals(COBBLEMON_POKEMON_ENTITY_ID);
    }

    /**
     * Convierte las propiedades escritas por el admin en el formato interno del mod.
     *
     * El admin escribirá:
     * pikachu level=25 shiny
     *
     * El mod guardará:
     * cobblemon:pikachu level=25 shiny
     */
    public static String toStoredPokemonEntry(String properties) {
        String clean = normalizeProperties(properties);

        if (clean.toLowerCase(Locale.ROOT).startsWith("cobblemon:")) {
            return clean;
        }

        return "cobblemon:" + clean;
    }

    /**
     * Convierte la entrada guardada en JSON a propiedades reales de Cobblemon.
     *
     * cobblemon:pikachu level=25 shiny
     * pasa a:
     * pikachu level=25 shiny
     */
    public static String toPokemonProperties(String entryId) {
        String clean = normalizeProperties(entryId);

        if (clean.toLowerCase(Locale.ROOT).startsWith("cobblemon:")) {
            return clean.substring("cobblemon:".length()).trim();
        }

        return clean;
    }

    /**
     * Limpia espacios dobles.
     */
    public static String normalizeProperties(String text) {
        if (text == null) {
            return "";
        }

        return text.trim().replaceAll("\\s+", " ");
    }

    /**
     * Intenta usar el ArgumentType real de Cobblemon:
     * PokemonPropertiesArgumentType.properties()
     *
     * Esto permite que Brigadier use las sugerencias nativas de Cobblemon:
     * especies, shiny, level, ability, forms/aspects y demás propiedades disponibles.
     *
     * Si Cobblemon no está instalado, usa greedyString como fallback.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static ArgumentType<Object> pokemonPropertiesArgument() {
        if (!isCobblemonLoaded()) {
            return (ArgumentType<Object>) (ArgumentType) StringArgumentType.greedyString();
        }

        try {
            Class<?> argumentClass = Class.forName("com.cobblemon.mod.common.command.argument.PokemonPropertiesArgumentType");

            /*
             * Algunas clases Kotlin exponen métodos de companion como estáticos
             * si usan @JvmStatic. Probamos primero esa opción.
             */
            try {
                Method staticPropertiesMethod = argumentClass.getMethod("properties");
                Object argumentType = staticPropertiesMethod.invoke(null);
                return (ArgumentType<Object>) argumentType;
            } catch (NoSuchMethodException ignored) {
                /*
                 * Si no existe método estático, probamos con Companion.
                 */
            }

            Field companionField = argumentClass.getField("Companion");
            Object companion = companionField.get(null);

            Method companionPropertiesMethod = companion.getClass().getMethod("properties");
            Object argumentType = companionPropertiesMethod.invoke(companion);

            return (ArgumentType<Object>) argumentType;

        } catch (Exception exception) {
            exception.printStackTrace();

            /*
             * Fallback seguro.
             * El comando seguirá funcionando, pero sin las sugerencias avanzadas de Cobblemon.
             */
            return (ArgumentType<Object>) (ArgumentType) StringArgumentType.greedyString();
        }
    }

    /**
     * Ejecuta internamente:
     * /pokespawnat <x> <y> <z> <properties>
     *
     * No devuelve la entidad directamente porque a veces Cobblemon no deja verla
     * en el mismo tick. Para evitar doble spawn, MMSpawnManager la marcará como pending.
     */
    public static boolean runPokeSpawnAt(ServerLevel level, BlockPos pos, String entryId) {
        if (!isCobblemonLoaded()) {
            return false;
        }

        String pokemonProperties = toPokemonProperties(entryId);

        if (pokemonProperties.isBlank()) {
            return false;
        }

        Vec3 spawnVec = new Vec3(
                pos.getX() + 0.5D,
                pos.getY(),
                pos.getZ() + 0.5D
        );

        try {
            CommandSourceStack source = level.getServer()
                    .createCommandSourceStack()
                    .withLevel(level)
                    .withPosition(spawnVec)
                    .withPermission(4)
                    .withSuppressedOutput();

            String command = "pokespawnat "
                    + spawnVec.x + " "
                    + spawnVec.y + " "
                    + spawnVec.z + " "
                    + pokemonProperties;

            level.getServer()
                    .getCommands()
                    .performPrefixedCommand(source, command);

            return true;

        } catch (Exception exception) {
            exception.printStackTrace();
            return false;
        }
    }

    /**
     * Busca Pokémon de Cobblemon cerca de una posición.
     *
     * No usamos PokemonEntity directamente para no depender de Cobblemon al compilar.
     */
    public static List<Entity> getPokemonNear(ServerLevel level, Vec3 center, double radius) {
        AABB box = new AABB(
                center.x - radius,
                center.y - radius,
                center.z - radius,
                center.x + radius,
                center.y + radius,
                center.z + radius
        );

        return level.getEntities(
                (Entity) null,
                box,
                entity -> BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().equals(COBBLEMON_POKEMON_ENTITY_ID)
        );
    }

    /**
     * Guarda los UUIDs de Pokémon que ya existían antes de ejecutar /pokespawnat.
     * Luego sirve para detectar cuál es el nuevo.
     */
    public static Set<UUID> getPokemonUuidsNear(ServerLevel level, Vec3 center, double radius) {
        Set<UUID> uuids = new HashSet<>();

        for (Entity entity : getPokemonNear(level, center, radius)) {
            uuids.add(entity.getUUID());
        }

        return uuids;
    }

    /**
     * Compatibilidad sin dependencia de compilación: escucha BATTLE_FAINTED
     * para recordar qué Pokémon cayó y BATTLE_VICTORY para confirmar el ganador.
     */
    public static synchronized void registerBattleFaintedListener() {
        if (!isCobblemonLoaded()) {
            return;
        }

        if (!battleFaintedListenerRegistered) {
            battleFaintedListenerRegistered = subscribeBattleEvent("BATTLE_FAINTED", CobblemonCompat::handleBattleFainted);
        }

        if (!battleVictoryListenerRegistered) {
            battleVictoryListenerRegistered = subscribeBattleEvent("BATTLE_VICTORY", CobblemonCompat::handleBattleVictory);
        }
    }

    @FunctionalInterface
    private interface CobblemonBattleEventHandler {
        void accept(Object event) throws Exception;
    }

    /**
     * Cobblemon ofrece EventObservable.subscribe(Priority, Function1).
     * El proxy implementa Function1 sin enlazar ninguna clase del mod en Java.
     */
    private static boolean subscribeBattleEvent(String eventField, CobblemonBattleEventHandler callback) {
        try {
            Class<?> eventsClass = Class.forName("com.cobblemon.mod.common.api.events.CobblemonEvents");
            Object observable = eventsClass.getField(eventField).get(null);

            Method subscribe = null;
            for (Method method : observable.getClass().getMethods()) {
                if (method.getName().equals("subscribe") && method.getParameterCount() == 2
                        && method.getParameterTypes()[1].getName().equals("kotlin.jvm.functions.Function1")) {
                    subscribe = method;
                    break;
                }
            }

            if (subscribe == null) {
                throw new IllegalStateException("Cobblemon no expone subscribe(Priority, Function1) para " + eventField);
            }

            Class<?> priorityClass = Class.forName("com.cobblemon.mod.common.api.Priority");
            Object normalPriority = null;
            for (Object constant : priorityClass.getEnumConstants()) {
                if (constant instanceof Enum<?> enumConstant && enumConstant.name().equals("NORMAL")) {
                    normalPriority = constant;
                    break;
                }
            }
            if (normalPriority == null) {
                throw new IllegalStateException("Cobblemon Priority.NORMAL no disponible");
            }

            Class<?> handlerType = subscribe.getParameterTypes()[1];
            Object handler = Proxy.newProxyInstance(
                    handlerType.getClassLoader(),
                    new Class<?>[]{handlerType},
                    (proxy, method, args) -> {
                        if (method.getName().equals("invoke") && args != null && args.length == 1) {
                            Object cobblemonEvent = args[0];
                            MMSpawnManager.executeOnServerThread(() -> {
                                try {
                                    callback.accept(cobblemonEvent);
                                } catch (Exception exception) {
                                    exception.printStackTrace();
                                }
                            });
                            return kotlinUnit();
                        }

                        if (method.getName().equals("toString")) {
                            return "CraftersMobManagerSpawn-" + eventField;
                        }
                        if (method.getName().equals("hashCode")) {
                            return System.identityHashCode(proxy);
                        }
                        if (method.getName().equals("equals")) {
                            return args != null && args.length == 1 && proxy == args[0];
                        }
                        return null;
                    }
            );

            subscribe.invoke(observable, normalPriority, handler);
            return true;
        } catch (Throwable throwable) {
            System.err.println("[MMSpawn] No se pudo registrar " + eventField + " de Cobblemon");
            throwable.printStackTrace();
            return false;
        }
    }

    private static void handleBattleFainted(Object event) throws Exception {
        Object killed = invokeNoArgs(event, "getKilled");
        Object battle = invokeNoArgs(event, "getBattle");
        if (killed == null || battle == null) {
            return;
        }

        Object actor = invokeNoArgs(killed, "getActor");
        UUID defeatedActorId = actor == null ? null : uuidOf(actor);

        Object pokemonEntity = invokeNoArgs(killed, "getEntity");
        Object pokemon = invokeNoArgs(killed, "getEffectedPokemon");
        if (!(pokemonEntity instanceof Entity) && pokemon != null) {
            pokemonEntity = invokeNoArgs(pokemon, "getEntity");
        }

        // Sólo interesan los Pokémon del mundo etiquetados por este SpawnManager.
        if (!(pokemonEntity instanceof Entity entity)) {
            return;
        }

        Component pokemonName = Component.literal("Pokémon");
        if (pokemon != null) {
            Object displayName = invokeNoArgs(pokemon, "getDisplayName");
            if (displayName instanceof Component component) {
                pokemonName = component;
            }
        }

        // La causa del faint identifica al Pokémon que dio el golpe final,
        // incluso en batallas cooperativas, veneno u otros efectos persistentes.
        UUID attackerPlayerId = null;
        Object context = invokeNoArgs(event, "getContext");
        if (context != null) {
            Object origin = invokeNoArgs(context, "getOrigin");
            if (origin != null) {
                Object originActor = invokeNoArgs(origin, "getActor");
                if (originActor != null && "PLAYER".equals(String.valueOf(invokeNoArgs(originActor, "getType")))) {
                    attackerPlayerId = uuidOf(originActor);
                }
            }
        }

        MMSpawnManager.queuePokemonBattleReward(
                (UUID) invokeNoArgs(battle, "getBattleId"),
                entity,
                defeatedActorId,
                attackerPlayerId,
                pokemonName
        );
    }

    private static void handleBattleVictory(Object event) throws Exception {
        Object battle = invokeNoArgs(event, "getBattle");
        if (battle == null) {
            return;
        }

        Object winnersObject = invokeNoArgs(event, "getWinners");
        Object losersObject = invokeNoArgs(event, "getLosers");
        Object captureObject = invokeNoArgs(event, "getWasWildCapture");

        List<UUID> winnerPlayerIds = new ArrayList<>();
        Set<UUID> losingActorIds = new HashSet<>();

        if (winnersObject instanceof Iterable<?> winners) {
            for (Object winner : winners) {
                if ("PLAYER".equals(String.valueOf(invokeNoArgs(winner, "getType")))) {
                    UUID uuid = uuidOf(winner);
                    if (uuid != null) {
                        winnerPlayerIds.add(uuid);
                    }
                }
            }
        }

        if (losersObject instanceof Iterable<?> losers) {
            for (Object loser : losers) {
                UUID uuid = uuidOf(loser);
                if (uuid != null) {
                    losingActorIds.add(uuid);
                }
            }
        }

        UUID battleId = (UUID) invokeNoArgs(battle, "getBattleId");
        MMSpawnManager.completePokemonBattleRewards(
                battleId,
                winnerPlayerIds,
                losingActorIds,
                Boolean.TRUE.equals(captureObject)
        );
    }

    private static UUID uuidOf(Object battleActor) throws Exception {
        Object value = invokeNoArgs(battleActor, "getUuid");
        return value instanceof UUID uuid ? uuid : null;
    }

    private static Object invokeNoArgs(Object target, String methodName) throws Exception {
        Method method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }

    private static Object kotlinUnit() {
        try {
            Class<?> unitClass = Class.forName("kotlin.Unit");
            return unitClass.getField("INSTANCE").get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }
}