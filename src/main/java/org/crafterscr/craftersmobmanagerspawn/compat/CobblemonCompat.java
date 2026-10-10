package org.crafterscr.craftersmobmanagerspawn.compat;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;
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
    private static final String MANAGED_ZONE_DATA_KEY = "crafterscr_mmspawn_zone";

    // Se registra una sola vez por proceso. Cobblemon mantiene el listener en su observable global.
    private static boolean battleFaintedListenerRegistered = false;
    private static boolean battleVictoryListenerRegistered = false;
    private static boolean captureHitListenerRegistered = false;
    private static boolean captureCalculatedListenerRegistered = false;

    private CobblemonCompat() {
    }

    public static boolean isBattleFaintedListenerRegistered() {
        return battleFaintedListenerRegistered;
    }

    public static boolean isBattleVictoryListenerRegistered() {
        return battleVictoryListenerRegistered;
    }

    public static boolean isCaptureHitListenerRegistered() {
        return captureHitListenerRegistered;
    }

    public static boolean isCaptureCalculatedListenerRegistered() {
        return captureCalculatedListenerRegistered;
    }

    /**
     * Revisa si Cobblemon está cargado.
     */
    public static boolean isCobblemonLoaded() {
        return ModList.get().isLoaded(COBBLEMON_MOD_ID);
    }

    /**
     * Marca únicamente el objeto Pokémon administrado; este dato persiste y
     * sigue con el Pokémon cuando Cobblemon recrea su entidad para un combate.
     */
    public static void markManagedPokemonZone(Entity entity, String zoneId, boolean denyCapture) {
        if (entity == null || zoneId == null) {
            return;
        }
        try {
            Object pokemon = invokeNoArgs(entity, "getPokemon");
            Object data = invokeNoArgs(pokemon, "getPersistentData");
            if (data instanceof CompoundTag tag) {
                if (!zoneId.equals(tag.getString(MANAGED_ZONE_DATA_KEY))) {
                    tag.putString(MANAGED_ZONE_DATA_KEY, zoneId);
                }
                synchronizeNativeCaptureProperty(pokemon, tag, denyCapture);
            }
        } catch (ReflectiveOperationException exception) {
            org.crafterscr.craftersmobmanagerspawn.CraftersMobManagerSpawn.LOGGER.warn(
                    "MMSpawn: no se pudo actualizar captura de Pokémon de zona {}",
                    zoneId, exception
            );
        }
    }

    /**
     * Cobblemon comprueba 'uncatchable' ANTES de registrar BattleCaptureAction
     * y forceChoose: así un lanzamiento durante batalla no bloquea el turno.
     * Sólo retiramos la bandera si fue puesta por MMSpawn.
     */
    public static void synchronizeNativeCaptureProperty(Entity entity, boolean denyCapture) {
        if (entity == null) {
            return;
        }
        try {
            Object pokemon = invokeNoArgs(entity, "getPokemon");
            Object data = invokeNoArgs(pokemon, "getPersistentData");
            if (data instanceof CompoundTag tag) {
                synchronizeNativeCaptureProperty(pokemon, tag, denyCapture);
            }
        } catch (ReflectiveOperationException exception) {
            org.crafterscr.craftersmobmanagerspawn.CraftersMobManagerSpawn.LOGGER.warn(
                    "MMSpawn: no se pudo sincronizar la protección nativa de captura", exception);
        }
    }

    private static final String NATIVE_UNCATCHABLE_OWNED_KEY = "crafterscr_mmspawn_uncatchable";

    private static void synchronizeNativeCaptureProperty(Object pokemon, CompoundTag tag, boolean denied)
            throws ReflectiveOperationException {
        // getCustomProperties() contiene la lista real utilizada por
        // UncatchableProperty.isCatchable(PokemonEntity), antes de procesar bolas.
        // Evitamos la invocación reflectiva de FlagProperty.apply, que podía
        // fallar sin dejar el flag configurado.
        Object custom = invokeNoArgs(pokemon, "getCustomProperties");
        if (!(custom instanceof List<?> rawProperties)) {
            throw new IllegalStateException("Cobblemon Pokemon.getCustomProperties() no devolvió List");
        }
        @SuppressWarnings("unchecked")
        List<Object> properties = (List<Object>) rawProperties;
        boolean flagged = hasUncatchableProperty(properties);
        boolean appliedByUs = tag.getBoolean(NATIVE_UNCATCHABLE_OWNED_KEY);

        if (denied && !flagged) {
            Class<?> flagType = Class.forName(
                    "com.cobblemon.mod.common.pokemon.properties.FlagProperty");
            Object flag = flagType.getConstructor(String.class, boolean.class)
                    .newInstance("uncatchable", false);
            properties.add(flag);
            tag.putBoolean(NATIVE_UNCATCHABLE_OWNED_KEY, true);
        } else if (!denied && appliedByUs) {
            properties.removeIf(CobblemonCompat::isUncatchableFlag);
            tag.remove(NATIVE_UNCATCHABLE_OWNED_KEY);
        }

        boolean after = Boolean.TRUE.equals(invokeNoArgs(pokemon, "isUncatchable"));
        if (after != denied && !(after && !denied && !appliedByUs)) {
            org.crafterscr.craftersmobmanagerspawn.CraftersMobManagerSpawn.LOGGER.error(
                    "MMSpawn: protección de captura NO aplicada correctamente (deny={}, nativeUncatchable={})",
                    denied, after);
        }
    }

    private static boolean hasUncatchableProperty(List<?> properties) {
        return properties.stream().anyMatch(CobblemonCompat::isUncatchableFlag);
    }

    private static boolean isUncatchableFlag(Object item) {
        if (item == null || !item.getClass().getName().endsWith(".FlagProperty")) {
            return false;
        }
        try {
            return "uncatchable".equalsIgnoreCase(String.valueOf(invokeNoArgs(item, "getKey")));
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    public static String getManagedZoneIdFromEntity(Entity entity) {
        if (entity == null) {
            return null;
        }
        try {
            Object pokemon = invokeNoArgs(entity, "getPokemon");
            Object data = invokeNoArgs(pokemon, "getPersistentData");
            if (data instanceof CompoundTag tag) {
                String id = tag.getString(MANAGED_ZONE_DATA_KEY);
                return id.isBlank() ? null : id;
            }
        } catch (ReflectiveOperationException ignored) {
            // Se recurrirá a las etiquetas persistentes de la entidad o al UUID.
        }
        return null;
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
    public static boolean runPokeSpawnAt(ServerLevel level, BlockPos pos, String entryId, boolean denyCapture) {
        if (!isCobblemonLoaded()) {
            return false;
        }

        String pokemonProperties = toPokemonProperties(entryId);

        if (pokemonProperties.isBlank()) {
            return false;
        }

        // Es una propiedad NATIVA de Cobblemon: bloquea antes de que se cree
        // BattleCaptureAction y por tanto evita el bloqueo de turnos en batalla.
        // No modificar las entradas guardadas por el admin.
        if (denyCapture && !pokemonProperties.matches("(?i).*\\buncatchable(?:=(?:true|yes))?(?:\\s|$).*")) {
            pokemonProperties += " uncatchable=yes";
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

    /**
     * A diferencia de los eventos de victoria, los eventos de captura DEBEN
     * ejecutarse dentro del callback original: cancelar en el siguiente tick
     * sería demasiado tarde y permitiría capturas con Master Ball.
     */
    public static synchronized void registerCaptureProtectionListeners() {
        if (!isCobblemonLoaded()) {
            return;
        }
        if (!captureHitListenerRegistered) {
            captureHitListenerRegistered = subscribeSynchronousCaptureEvent(
                    "THROWN_POKEBALL_HIT", "HIGHEST", CobblemonCompat::onThrownPokeBallHit
            );
        }
        if (!captureCalculatedListenerRegistered) {
            captureCalculatedListenerRegistered = subscribeSynchronousCaptureEvent(
                    "POKE_BALL_CAPTURE_CALCULATED", "LOWEST", CobblemonCompat::onCaptureCalculated
            );
        }
    }

    private static boolean subscribeSynchronousCaptureEvent(
            String eventField, String priorityName, CobblemonBattleEventHandler callback
    ) {
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
                throw new IllegalStateException("No subscribe(Priority, Function1) for " + eventField);
            }

            Class<?> priorityClass = Class.forName("com.cobblemon.mod.common.api.Priority");
            Object priority = null;
            for (Object option : priorityClass.getEnumConstants()) {
                if (option instanceof Enum<?> e && e.name().equals(priorityName)) {
                    priority = option;
                    break;
                }
            }
            if (priority == null) {
                throw new IllegalStateException("Unknown Cobblemon priority: " + priorityName);
            }

            Class<?> handlerType = subscribe.getParameterTypes()[1];
            Object handler = Proxy.newProxyInstance(
                    handlerType.getClassLoader(), new Class<?>[]{handlerType},
                    (proxy, method, args) -> {
                        if (method.getName().equals("invoke") && args != null && args.length == 1) {
                            // Deliberadamente síncrono: Cobblemon consulta el resultado
                            // del evento antes de continuar el cálculo de captura.
                            try {
                                callback.accept(args[0]);
                            } catch (Exception exception) {
                                org.crafterscr.craftersmobmanagerspawn.CraftersMobManagerSpawn.LOGGER.error(
                                        "MMSpawn: error bloqueando captura en " + eventField, exception
                                );
                            }
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

            subscribe.invoke(observable, priority, handler);
            org.crafterscr.craftersmobmanagerspawn.CraftersMobManagerSpawn.LOGGER.info(
                    "MMSpawn: protección de captura {} registrada", eventField
            );
            return true;
        } catch (Throwable exception) {
            org.crafterscr.craftersmobmanagerspawn.CraftersMobManagerSpawn.LOGGER.error(
                    "MMSpawn: no se pudo registrar protección de captura " + eventField, exception
            );
            return false;
        }
    }

    private static void onThrownPokeBallHit(Object event) throws Exception {
        Object target = invokeNoArgs(event, "getPokemon");
        if (target instanceof Entity pokemonEntity && MMSpawnManager.isCaptureDenied(pokemonEntity)) {
            // En batalla Cobblemon ya creó BattleCaptureAction ANTES de este
            // evento. Cancelarlo aquí dejaría el turno bloqueado. La propiedad
            // nativa 'uncatchable' lo evita antes de crear esa acción; si alguna
            // versión llega hasta aquí, el cálculo posterior forzará un fallo
            // normal que completará BattleCaptureAction y liberará el turno.
            if (invokeNoArgs(pokemonEntity, "getBattleId") != null) {
                return;
            }
            invokeNoArgs(event, "cancel");
            sendCaptureDeniedToBallOwner(invokeNoArgs(event, "getPokeBall"));
        }
    }

    private static void onCaptureCalculated(Object event) throws Exception {
        Object target = invokeNoArgs(event, "getPokemonEntity");
        if (!(target instanceof Entity pokemonEntity) || !MMSpawnManager.isCaptureDenied(pokemonEntity)) {
            return;
        }

        // Ruta de respaldo para capturas dentro de batalla y bolas que alcanzan
        // el cálculo de captura: incluso una Master Ball resulta fallida.
        Class<?> resultClass = Class.forName(
                "com.cobblemon.mod.common.api.pokeball.catching.CaptureContext"
        );
        Object deniedResult = resultClass.getConstructor(int.class, boolean.class, boolean.class)
                .newInstance(0, false, false);
        event.getClass().getMethod("setCaptureResult", resultClass).invoke(event, deniedResult);
        Object thrower = invokeNoArgs(event, "getThrower");
        if (thrower instanceof ServerPlayer player) {
            player.sendSystemMessage(Component.literal("Este Pokémon no se puede capturar.")
                    .withStyle(ChatFormatting.RED));
        }
    }

    private static void sendCaptureDeniedToBallOwner(Object pokeBall) {
        if (pokeBall == null) {
            return;
        }
        try {
            Object owner = invokeNoArgs(pokeBall, "getOwner");
            if (owner instanceof ServerPlayer player) {
                player.sendSystemMessage(Component.literal("Este Pokémon no se puede capturar.")
                        .withStyle(ChatFormatting.RED));
            }
        } catch (ReflectiveOperationException ignored) {
            // El bloqueo se mantiene aunque alguna versión no exponga owner.
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
            org.crafterscr.craftersmobmanagerspawn.CraftersMobManagerSpawn.LOGGER.info(
                    "MMSpawn: listener Cobblemon {} registrado correctamente", eventField);
            return true;
        } catch (Throwable throwable) {
            System.err.println("[MMSpawn] No se pudo registrar " + eventField + " de Cobblemon");
            throwable.printStackTrace();
            return false;
        }
    }

    /**
     * UUID persistente del objeto Pokemon, diferente del UUID de PokemonEntity.
     */
    public static UUID getPokemonUuidFromEntity(Entity entity) {
        if (entity == null) {
            return null;
        }
        try {
            Object pokemon = invokeNoArgs(entity, "getPokemon");
            Object uuid = invokeNoArgs(pokemon, "getUuid");
            return uuid instanceof UUID found ? found : null;
        } catch (Exception ignored) {
            return null;
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
        Object pokemon = invokeNoArgs(killed, "getEffectedPokemon");
        if (pokemon == null) {
            return;
        }

        Object pokemonUuidObject = invokeNoArgs(pokemon, "getUuid");
        UUID pokemonUuid = pokemonUuidObject instanceof UUID uuid ? uuid : null;
        if (pokemonUuid == null) {
            return;
        }

        Component pokemonName = Component.literal("Pokémon");
        pokemonName = pokemonDisplayName(pokemon);

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

        MMSpawnManager.queuePokemonBattleRewardByPokemonUuid(
                (UUID) invokeNoArgs(battle, "getBattleId"),
                pokemonUuid, defeatedActorId, attackerPlayerId, pokemonName
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
        boolean capture = Boolean.TRUE.equals(captureObject);

        // CORRECCIÓN: BATTLE_FAINTED no siempre puede identificar el Pokémon (se
        // retira su entidad y algunos complementos clonan el objeto Pokemon).
        // BATTLE_VICTORY incluye a los actores derrotados y a sus Pokémon,
        // por lo que constituye una segunda ruta confiable.
        if (!capture && losersObject instanceof Iterable<?> losers) {
            for (Object loser : losers) {
                if (!"WILD".equals(String.valueOf(invokeNoArgs(loser, "getType")))) {
                    continue;
                }
                UUID defeatedActorId = uuidOf(loser);
                Object pokemonList = invokeNoArgs(loser, "getPokemonList");
                if (!(pokemonList instanceof Iterable<?> defeatedPokemon)) {
                    continue;
                }
                for (Object battlePokemon : defeatedPokemon) {
                    Object health = invokeNoArgs(battlePokemon, "getHealth");
                    if (!(health instanceof Number n) || n.intValue() > 0) {
                        continue;
                    }

                    Object effected = invokeNoArgs(battlePokemon, "getEffectedPokemon");
                    Object original = invokeNoArgs(battlePokemon, "getOriginalPokemon");
                    Component name = Component.literal("Pokémon");
                    if (effected != null) {
                        name = pokemonDisplayName(effected);
                    }

                    for (Object candidate : new Object[]{original, effected}) {
                        if (candidate == null) {
                            continue;
                        }
                        Object possibleUuid = invokeNoArgs(candidate, "getUuid");
                        if (possibleUuid instanceof UUID pokemonUuid) {
                            MMSpawnManager.queuePokemonBattleRewardByPokemonUuid(
                                    battleId, pokemonUuid, defeatedActorId, null, name);
                        }

                        // Recuperación adicional cuando la entidad todavía existe.
                        Object possibleEntity = invokeNoArgs(candidate, "getEntity");
                        if (possibleEntity instanceof Entity entity) {
                            MMSpawnManager.queuePokemonBattleReward(
                                    battleId, entity, defeatedActorId, null, name);
                        }
                    }
                }
            }
        }

        org.crafterscr.craftersmobmanagerspawn.CraftersMobManagerSpawn.LOGGER.info(
                "MMSpawn: BATTLE_VICTORY {} | ganadores jugadores={} | actores vencidos={} | captura={}",
                battleId, winnerPlayerIds.size(), losingActorIds.size(), capture);
        MMSpawnManager.completePokemonBattleRewards(
                battleId, winnerPlayerIds, losingActorIds, capture
        );
    }

    /**
     * En Cobblemon 1.7.x Pokemon.getDisplayName(boolean showTitle) tiene
     * parámetro Kotlin por defecto, pero no ofrece sobrecarga Java sin args.
     * No debemos lanzar NoSuchMethodException durante el evento de batalla.
     */
    private static Component pokemonDisplayName(Object pokemon) {
        if (pokemon == null) {
            return Component.literal("Pokémon");
        }
        try {
            Method getter = pokemon.getClass().getMethod("getDisplayName", boolean.class);
            Object name = getter.invoke(pokemon, false);
            if (name instanceof Component component) {
                return component.copy();
            }
        } catch (ReflectiveOperationException ignored) {
            // Un nombre ausente no debe cancelar una recompensa válida.
        }
        return Component.literal("Pokémon");
    }

    private static UUID uuidOf(Object battleActor) throws Exception {
        Object value = invokeNoArgs(battleActor, "getUuid");
        return value instanceof UUID uuid ? uuid : null;
    }

    private static Object invokeNoArgs(Object target, String methodName) throws ReflectiveOperationException {
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