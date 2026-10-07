package org.crafterscr.craftersmobmanagerspawn.compat;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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
     * Suscribe el sistema de drops al BATTLE_FAINTED real de Cobblemon sin enlazar
     * clases de Cobblemon durante compilación. De esta forma Cobblemon sigue siendo opcional.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static synchronized void registerBattleFaintedListener() {
        if (battleFaintedListenerRegistered || !isCobblemonLoaded()) {
            return;
        }

        try {
            Class<?> eventsClass = Class.forName("com.cobblemon.mod.common.api.events.CobblemonEvents");
            Object battleFaintedObservable = eventsClass.getField("BATTLE_FAINTED").get(null);

            Method subscribeMethod = null;

            for (Method method : battleFaintedObservable.getClass().getMethods()) {
                if (method.getName().equals("subscribe") && method.getParameterCount() == 2) {
                    subscribeMethod = method;
                    break;
                }
            }

            if (subscribeMethod == null) {
                throw new IllegalStateException("No se encontró Observable.subscribe(Priority, handler) de Cobblemon.");
            }

            Class<?> priorityClass = Class.forName("com.cobblemon.mod.common.api.Priority");
            Object normalPriority = Enum.valueOf((Class<? extends Enum>) priorityClass.asSubclass(Enum.class), "NORMAL");

            Class<?> handlerType = subscribeMethod.getParameterTypes()[1];

            Object handler = Proxy.newProxyInstance(
                    handlerType.getClassLoader(),
                    new Class<?>[]{handlerType},
                    (proxy, method, args) -> {
                        if (method.getName().equals("invoke") && args != null && args.length == 1) {
                            handleBattleFainted(args[0]);
                            return kotlinUnit();
                        }

                        if (method.getName().equals("toString")) {
                            return "CraftersMobManagerSpawnBattleFaintedHandler";
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

            subscribeMethod.invoke(battleFaintedObservable, normalPriority, handler);
            battleFaintedListenerRegistered = true;

        } catch (Throwable throwable) {
            /*
             * Cobblemon es una compatibilidad opcional. Un cambio de API no debe impedir
             * que CraftersMobManagerSpawn inicie sin Cobblemon.
             */
            throwable.printStackTrace();
        }
    }

    /**
     * BATTLE_FAINTED entrega un BattlePokemon. Obtenemos el Pokemon afectado y su
     * PokemonEntity todavía presente en el mundo; esa entidad conserva las tags
     * mmspawn_managed, mmspawn_zone_* y mmspawn_entry_*.
     */
    private static void handleBattleFainted(Object event) {
        try {
            Object killedBattlePokemon = invokeNoArgs(event, "getKilled");

            if (killedBattlePokemon == null) {
                return;
            }

            Object pokemon = invokeNoArgs(killedBattlePokemon, "getEffectedPokemon");

            if (pokemon == null) {
                return;
            }

            Object pokemonEntity = invokeNoArgs(pokemon, "getEntity");

            if (pokemonEntity instanceof Entity entity) {
                MMSpawnManager.dropConfiguredRewards(entity);
            }

        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
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