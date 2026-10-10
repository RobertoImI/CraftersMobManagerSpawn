package org.crafterscr.craftersmobmanagerspawn.logic;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import org.crafterscr.craftersmobmanagerspawn.CraftersMobManagerSpawn;
import org.crafterscr.craftersmobmanagerspawn.compat.CobblemonCompat;
import org.crafterscr.craftersmobmanagerspawn.data.MMSpawnStorage;
import org.crafterscr.craftersmobmanagerspawn.data.SpawnDropEntry;
import org.crafterscr.craftersmobmanagerspawn.data.SpawnMobEntry;
import org.crafterscr.craftersmobmanagerspawn.data.SpawnPointData;
import org.crafterscr.craftersmobmanagerspawn.data.SpawnZone;
import org.crafterscr.craftersmobmanagerspawn.util.RespawnMode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Clase central del sistema de spawn.
 *
 * Aquí se guardan las zonas en memoria, se cargan/guardan desde JSON,
 * se ejecuta el tick del servidor, se controla el respawn progresivo,
 * se etiquetan mobs del mod, se manejan Pokémon pendientes de Cobblemon
 * y se muestran partículas temporales.
 */
public class MMSpawnManager {

    // Todas las zonas cargadas, organizadas por ID.
    private static final Map<String, SpawnZone> ZONES = new LinkedHashMap<>();

    // Tareas temporales para mostrar partículas por varios segundos.
    private static final Map<String, VisualTask> VISUAL_TASKS = new LinkedHashMap<>();

    /*
     * Spawns pendientes de Cobblemon.
     *
     * Esto evita el bug del doble spawn:
     * - Mandamos /pokespawnat.
     * - Contamos ese intento como pendiente.
     * - Esperamos unos ticks hasta detectar el Pokémon real.
     * - Cuando aparece, lo etiquetamos y lo pasamos a activeMobUuids.
     */
    private static final List<PendingCobblemonSpawn> PENDING_COBBLEMON_SPAWNS = new ArrayList<>();

    // Tiempo máximo que esperamos para detectar un Pokémon después de ejecutar /pokespawnat.
    private static final int COBBLEMON_PENDING_TIMEOUT_TICKS = 100;

    // Radio para buscar el Pokémon creado por /pokespawnat.
    private static final double COBBLEMON_PENDING_SEARCH_RADIUS = 8.0D;

    // Random compartido para elegir mobs, posiciones y pesos.
    private static final Random RANDOM = new Random();

    // Etiqueta general que se agrega a todo mob creado por este mod.
    private static final String MANAGED_TAG = "mmspawn_managed";

    /*
     * Evita entregar dos veces la recompensa si un Pokémon dispara tanto un evento
     * vanilla como BATTLE_FAINTED. Las entradas se purgan tras 10 minutos.
     */
    private static final Map<UUID, Long> PROCESSED_DROP_UUIDS = new LinkedHashMap<>();
    private static final long PROCESSED_DROP_TTL_MILLIS = 10L * 60L * 1000L;

    // El faint anota una recompensa potencial; únicamente BATTLE_VICTORY puede entregarla.
    private static final Map<UUID, List<PendingPokemonBattleReward>> PENDING_BATTLE_REWARDS = new LinkedHashMap<>();
    private static final long BATTLE_REWARD_TTL_MILLIS = 30L * 60L * 1000L;

    // Identidad propia del Pokémon != UUID de la entidad Minecraft. Sobrevive al retiro
    // temporal de PokemonEntity durante combate.
    private static final Map<UUID, TrackedPokemonSpawn> TRACKED_POKEMON = new LinkedHashMap<>();

    // Referencia al servidor actual. Se usa para guardar el JSON desde otros métodos.
    private static MinecraftServer currentServer;

    // Contador simple para ejecutar la lógica pesada una vez por segundo y no cada tick.
    private static int tickCounter = 0;

    /**
     * Cuando el servidor termina de iniciar, cargamos las zonas desde el JSON.
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        currentServer = event.getServer();

        ZONES.clear();
        ZONES.putAll(MMSpawnStorage.load(event.getServer()));

        VISUAL_TASKS.clear();
        PENDING_COBBLEMON_SPAWNS.clear();
        PROCESSED_DROP_UUIDS.clear();
        PENDING_BATTLE_REWARDS.clear();
        TRACKED_POKEMON.clear();

        // Se registra aquí, con el servidor ya iniciado, para mantener Cobblemon opcional.
        CobblemonCompat.registerBattleFaintedListener();
        CobblemonCompat.registerCaptureProtectionListeners();
    }

    /**
     * Antes de apagar el servidor, guardamos las zonas actuales en JSON.
     */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        if (currentServer != null) {
            MMSpawnStorage.save(currentServer, ZONES);
        }

        currentServer = null;

        ZONES.clear();
        VISUAL_TASKS.clear();
        PENDING_COBBLEMON_SPAWNS.clear();
        PROCESSED_DROP_UUIDS.clear();
        PENDING_BATTLE_REWARDS.clear();
        TRACKED_POKEMON.clear();
    }

    /**
     * Tick del servidor.
     *
     * Minecraft corre a 20 ticks por segundo.
     * Este sistema trabaja una vez por segundo para evitar cargar demasiado el servidor.
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();

        tickCounter++;

        if (tickCounter < 20) {
            return;
        }

        tickCounter = 0;

        // Primero resolvemos Pokémon pendientes para que cuenten antes de spawnear más.
        tickPendingCobblemonSpawns(server);
        cleanupProcessedDropUuids();
        cleanupExpiredBattleRewards();
        cleanupTrackedPokemon();

        for (SpawnZone zone : ZONES.values()) {
            tickZone(server, zone);
        }

        tickVisualTasks(server);
    }

    /**
     * Lógica principal de una zona.
     *
     * EMPTY:
     * - La zona debe estar vacía durante respawnDelaySeconds.
     * - Si un jugador entra, el contador se reinicia.
     *
     * COOLDOWN:
     * - Al morir/faltar mobs, se programa un próximo respawn con tiempo real.
     * - Ese tiempo NO se reinicia si un jugador entra.
     * - Si el cooldown terminó pero hay jugadores dentro, espera a que se vayan.
     */
    private static void tickZone(MinecraftServer server, SpawnZone zone) {
        if (!zone.isActive()) {
            return;
        }

        ServerLevel level = getLevel(server, zone);

        if (level == null) {
            return;
        }

        long gameTime = level.getGameTime();

        syncTaggedMobs(level, zone);

        int trackedBeforeCleanup = getTrackedMobCount(zone);
        cleanupDeadMobs(level, zone);
        int trackedAfterCleanup = getTrackedMobCount(zone);

        if (zone.getMobs().isEmpty()) {
            return;
        }

        if (zone.getMaxAlive() <= 0) {
            return;
        }

        if (zone.getRespawnMode() == RespawnMode.COOLDOWN) {
            tickCooldownZone(level, zone, gameTime, trackedBeforeCleanup, trackedAfterCleanup);
        } else {
            tickEmptyZone(level, zone, gameTime);
        }
    }

    /**
     * Modo EMPTY: mismo comportamiento clásico del mod.
     * La zona debe estar vacía durante respawnDelaySeconds.
     */
    private static void tickEmptyZone(ServerLevel level, SpawnZone zone, long gameTime) {
        if (getTrackedMobCount(zone) >= zone.getMaxAlive()) {
            zone.setEmptySinceTick(-1);
            return;
        }

        boolean hasPlayersInside = hasPlayersInside(level, zone);

        if (hasPlayersInside) {
            zone.setEmptySinceTick(-1);
            return;
        }

        if (zone.getEmptySinceTick() < 0) {
            zone.setEmptySinceTick(gameTime);
            return;
        }

        long delayTicks = zone.getRespawnDelaySeconds() * 20L;

        if (gameTime - zone.getEmptySinceTick() < delayTicks) {
            return;
        }

        long intervalTicks = zone.getSpawnIntervalSeconds() * 20L;

        if (zone.getLastSpawnTick() >= 0 && gameTime - zone.getLastSpawnTick() < intervalTicks) {
            return;
        }

        boolean spawned = spawnOneMob(level, zone);

        if (spawned) {
            zone.setLastSpawnTick(gameTime);
        }
    }

    /**
     * Modo COOLDOWN: pensado para bosses o spawns raros.
     *
     * El contador se basa en tiempo real del sistema y se guarda en JSON.
     * Eso permite cooldowns largos como 345600 segundos, incluso con reinicios.
     */
    private static void tickCooldownZone(ServerLevel level, SpawnZone zone, long gameTime, int trackedBeforeCleanup, int trackedAfterCleanup) {
        long now = System.currentTimeMillis();

        /*
         * Si antes estaba lleno y ahora falta algo, significa que murió un mob/boss.
         * Programamos el próximo respawn.
         */
        if (trackedBeforeCleanup >= zone.getMaxAlive()
                && trackedAfterCleanup < zone.getMaxAlive()
                && zone.getNextRespawnEpochMillis() < 0L) {
            scheduleCooldown(zone, now);
            return;
        }

        if (getTrackedMobCount(zone) >= zone.getMaxAlive()) {
            zone.setEmptySinceTick(-1);
            return;
        }

        /*
         * Si no hay cooldown programado y faltan mobs, la zona queda lista.
         * Esto permite que un boss aparezca la primera vez cuando activas la zona.
         */
        if (zone.getNextRespawnEpochMillis() < 0L) {
            zone.setNextRespawnEpochMillis(0L);
            save();
        }

        if (zone.getNextRespawnEpochMillis() > now) {
            return;
        }

        /*
         * Si ya terminó el cooldown pero hay jugadores dentro,
         * NO se reinicia el contador. Solo esperamos a que se vayan.
         */
        if (hasPlayersInside(level, zone)) {
            return;
        }

        long intervalTicks = zone.getSpawnIntervalSeconds() * 20L;

        if (zone.getLastSpawnTick() >= 0 && gameTime - zone.getLastSpawnTick() < intervalTicks) {
            return;
        }

        boolean spawned = spawnOneMob(level, zone);

        if (spawned) {
            zone.setLastSpawnTick(gameTime);

            /*
             * Si ya llenamos el máximo, limpiamos el cooldown pendiente.
             * Cuando el boss muera otra vez, se programará de nuevo.
             */
            if (getTrackedMobCount(zone) >= zone.getMaxAlive()) {
                zone.setNextRespawnEpochMillis(-1L);
                save();
            }
        }
    }

    /**
     * Programa el próximo respawn para modo COOLDOWN.
     */
    private static void scheduleCooldown(SpawnZone zone, long nowMillis) {
        long delayMillis = zone.getRespawnDelaySeconds() * 1000L;
        zone.setNextRespawnEpochMillis(nowMillis + delayMillis);
        zone.setEmptySinceTick(-1);
        zone.setLastSpawnTick(-1);
        save();
    }

    /**
     * Mantiene visibles las partículas de /mmspawn show <id> <segundos>.
     */
    private static void tickVisualTasks(MinecraftServer server) {
        Iterator<Map.Entry<String, VisualTask>> iterator = VISUAL_TASKS.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<String, VisualTask> entry = iterator.next();
            VisualTask task = entry.getValue();

            SpawnZone zone = getZone(task.zoneId);

            if (zone == null) {
                iterator.remove();
                continue;
            }

            ServerLevel level = getLevel(server, zone);

            if (level == null) {
                iterator.remove();
                continue;
            }

            if (level.getGameTime() > task.endTick) {
                iterator.remove();
                continue;
            }

            showZoneParticles(level, zone);
        }
    }

    /**
     * Ejecuta callbacks de Cobblemon en el hilo principal del servidor.
     * Evita manipular inventarios o tablas de premios desde otros hilos.
     */
    public static void executeOnServerThread(Runnable action) {
        MinecraftServer server = currentServer;
        if (server == null) {
            return;
        }
        if (server.isSameThread()) {
            action.run();
        } else {
            server.execute(action);
        }
    }

    /**
     * Guarda manualmente todas las zonas en el JSON.
     */
    public static void save() {
        if (currentServer != null) {
            MMSpawnStorage.save(currentServer, ZONES);
        }
    }

    public static boolean exists(String id) {
        return ZONES.containsKey(id.toLowerCase());
    }

    public static SpawnZone getZone(String id) {
        return ZONES.get(id.toLowerCase());
    }

    public static Collection<SpawnZone> getZones() {
        return ZONES.values();
    }

    public static List<String> getZoneIds() {
        return ZONES.keySet()
                .stream()
                .sorted()
                .toList();
    }

    /**
     * Crea una zona nueva en memoria y luego la guarda en JSON.
     */
    public static SpawnZone createZone(String id) {
        String cleanId = id.toLowerCase();

        SpawnZone zone = new SpawnZone(cleanId);
        ZONES.put(cleanId, zone);

        save();
        return zone;
    }

    /**
     * Elimina una zona completamente.
     * También borra los mobs vivos asociados a esa zona usando UUIDs y etiquetas.
     */
    public static boolean deleteZone(MinecraftServer server, String id) {
        SpawnZone zone = getZone(id);

        if (zone == null) {
            return false;
        }

        clearActiveMobs(server, zone);
        VISUAL_TASKS.remove(zone.getId());
        ZONES.remove(id.toLowerCase());
        save();

        return true;
    }

    /**
     * Elimina del mundo todos los mobs activos asociados a una zona.
     */
    public static void clearActiveMobs(MinecraftServer server, SpawnZone zone) {
        PENDING_COBBLEMON_SPAWNS.removeIf(pending -> pending.zoneId.equalsIgnoreCase(zone.getId()));

        ServerLevel level = getLevel(server, zone);

        if (level == null) {
            zone.getActiveMobUuids().clear();
            return;
        }

        for (Entity entity : getTaggedMobsInZone(level, zone)) {
            entity.discard();
        }

        for (UUID uuid : new ArrayList<>(zone.getActiveMobUuids())) {
            Entity entity = level.getEntity(uuid);

            if (entity != null) {
                entity.discard();
            }
        }

        zone.getActiveMobUuids().clear();
    }

    /**
     * Fuerza el respawn hasta llenar la zona al máximo permitido.
     */
    public static int forceRespawn(ServerLevel level, SpawnZone zone) {
        syncTaggedMobs(level, zone);
        cleanupDeadMobs(level, zone);

        int spawned = 0;

        while (getTrackedMobCount(zone) < zone.getMaxAlive()) {
            boolean success = spawnOneMob(level, zone);

            if (!success) {
                break;
            }

            spawned++;
        }

        if (getTrackedMobCount(zone) >= zone.getMaxAlive() && zone.getRespawnMode() == RespawnMode.COOLDOWN) {
            zone.setNextRespawnEpochMillis(-1L);
            save();
        }

        return spawned;
    }

    /**
     * Crea un solo mob de la zona.
     */
    private static boolean spawnOneMob(ServerLevel level, SpawnZone zone) {
        SpawnMobEntry entry = pickWeightedMob(zone);

        if (entry == null) {
            return false;
        }

        BlockPos spawnPos = chooseSpawnPos(level, zone);

        if (spawnPos == null) {
            return false;
        }

        if (CobblemonCompat.isCobblemonPokemonEntry(entry.getEntityId())) {
            Vec3 spawnVec = new Vec3(
                    spawnPos.getX() + 0.5D,
                    spawnPos.getY(),
                    spawnPos.getZ() + 0.5D
            );

            Set<UUID> beforeUuids = CobblemonCompat.getPokemonUuidsNear(
                    level,
                    spawnVec,
                    COBBLEMON_PENDING_SEARCH_RADIUS
            );

            boolean commandExecuted = CobblemonCompat.runPokeSpawnAt(level, spawnPos, entry.getEntityId(), zone.isCaptureDenied());

            if (!commandExecuted) {
                return false;
            }

            PendingCobblemonSpawn pending = new PendingCobblemonSpawn(
                    zone.getId(),
                    entry.getEntityId(),
                    spawnVec,
                    beforeUuids,
                    level.getGameTime()
            );

            boolean resolvedNow = tryResolvePendingCobblemonSpawn(level, zone, pending);

            if (!resolvedNow) {
                PENDING_COBBLEMON_SPAWNS.add(pending);
            }

            return true;
        }

        Optional<EntityType<?>> optionalType = BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.parse(entry.getEntityId()));

        if (optionalType.isEmpty()) {
            return false;
        }

        EntityType<?> entityType = optionalType.get();
        Entity entity = entityType.create(level);

        if (entity == null) {
            return false;
        }

        entity.moveTo(
                spawnPos.getX() + 0.5D,
                spawnPos.getY(),
                spawnPos.getZ() + 0.5D,
                RANDOM.nextFloat() * 360.0F,
                0.0F
        );

        entity.addTag(MANAGED_TAG);
        entity.addTag(getZoneTag(zone));
        entity.addTag(getEntryTag(entry));

        if (entity instanceof Mob mob) {
            mob.setPersistenceRequired();
        }

        boolean added = level.addFreshEntity(entity);

        if (added) {
            zone.getActiveMobUuids().add(entity.getUUID());
        }

        return added;
    }

    private static SpawnMobEntry pickWeightedMob(SpawnZone zone) {
        int totalWeight = 0;

        for (SpawnMobEntry entry : zone.getMobs()) {
            totalWeight += Math.max(1, entry.getWeight());
        }

        if (totalWeight <= 0) {
            return null;
        }

        int roll = RANDOM.nextInt(totalWeight);
        int current = 0;

        for (SpawnMobEntry entry : zone.getMobs()) {
            current += Math.max(1, entry.getWeight());

            if (roll < current) {
                return entry;
            }
        }

        return zone.getMobs().get(0);
    }

    private static BlockPos chooseSpawnPos(ServerLevel level, SpawnZone zone) {
        if (!zone.getManualPoints().isEmpty()) {
            for (int attempt = 0; attempt < Math.min(20, zone.getManualPoints().size()); attempt++) {
                SpawnPointData point = zone.getManualPoints().get(RANDOM.nextInt(zone.getManualPoints().size()));
                BlockPos manual = point.toBlockPos();

                if (isSpawnSpaceValid(level, manual)) {
                    return manual;
                }
            }

            return null;
        }

        for (int attempt = 0; attempt < 60; attempt++) {
            int dx = RANDOM.nextInt(zone.getRadius() * 2 + 1) - zone.getRadius();
            int dz = RANDOM.nextInt(zone.getRadius() * 2 + 1) - zone.getRadius();

            double distanceSq = dx * dx + dz * dz;

            if (distanceSq > zone.getRadius() * zone.getRadius()) {
                continue;
            }

            int x = (int) Math.floor(zone.getCenterX()) + dx;
            int z = (int) Math.floor(zone.getCenterZ()) + dz;

            BlockPos pos = switch (zone.getHeightMode()) {
                case EXACT -> findExactPos(level, x, z, zone);
                case AIR -> findAirPos(level, x, z, zone);
                case FLOOR -> findFloorPos(level, x, z, zone);
                case GROUND -> findGroundPos(level, x, z, zone);
            };

            if (pos != null && isSpawnSpaceValid(level, pos)) {
                return pos;
            }
        }

        return null;
    }

    private static BlockPos findExactPos(ServerLevel level, int x, int z, SpawnZone zone) {
        int y = clamp((int) Math.round(zone.getCenterY()), zone.getYMin(), zone.getYMax());
        BlockPos pos = new BlockPos(x, y, z);

        if (isSpawnSpaceValid(level, pos)) {
            return pos;
        }

        return null;
    }

    private static BlockPos findGroundPos(ServerLevel level, int x, int z, SpawnZone zone) {
        int min = Math.min(zone.getYMin(), zone.getYMax());
        int max = Math.max(zone.getYMin(), zone.getYMax());

        for (int y = max; y >= min; y--) {
            BlockPos ground = new BlockPos(x, y, z);
            BlockPos above = ground.above();

            if (isSolidGround(level, ground) && isSpawnSpaceValid(level, above)) {
                return above;
            }
        }

        return null;
    }

    private static BlockPos findFloorPos(ServerLevel level, int x, int z, SpawnZone zone) {
        int centerY = (int) Math.round(zone.getCenterY());

        int min = Math.min(zone.getYMin(), zone.getYMax());
        int max = Math.max(zone.getYMin(), zone.getYMax());

        int start = clamp(centerY + 4, min, max);
        int end = clamp(centerY - 12, min, max);

        for (int y = start; y >= end; y--) {
            BlockPos floor = new BlockPos(x, y, z);
            BlockPos above = floor.above();

            if (isSolidGround(level, floor) && isSpawnSpaceValid(level, above)) {
                return above;
            }
        }

        return null;
    }

    private static BlockPos findAirPos(ServerLevel level, int x, int z, SpawnZone zone) {
        int min = Math.min(zone.getYMin(), zone.getYMax());
        int max = Math.max(zone.getYMin(), zone.getYMax());

        for (int attempt = 0; attempt < 30; attempt++) {
            int y = min + RANDOM.nextInt(Math.max(1, max - min + 1));
            BlockPos pos = new BlockPos(x, y, z);

            if (isSpawnSpaceValid(level, pos)) {
                return pos;
            }
        }

        return null;
    }

    private static boolean isSolidGround(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isSolidRender(level, pos);
    }

    private static boolean isSpawnSpaceValid(ServerLevel level, BlockPos pos) {
        BlockState current = level.getBlockState(pos);
        BlockState above = level.getBlockState(pos.above());

        return current.isAir() && above.isAir();
    }

    private static void syncTaggedMobs(ServerLevel level, SpawnZone zone) {
        for (Entity entity : getTaggedMobsInZone(level, zone)) {
            UUID uuid = entity.getUUID();

            if (!zone.getActiveMobUuids().contains(uuid)) {
                zone.getActiveMobUuids().add(uuid);
            }
            trackPokemonIdentity(entity, zone);
        }
    }

    private static void cleanupDeadMobs(ServerLevel level, SpawnZone zone) {
        zone.getActiveMobUuids().removeIf(uuid -> {
            Entity entity = level.getEntity(uuid);
            return entity == null || !entity.isAlive();
        });
    }

    private static List<Entity> getTaggedMobsInZone(ServerLevel level, SpawnZone zone) {
        double radius = zone.getRadius() + 8.0D;

        AABB box = new AABB(
                zone.getCenterX() - radius,
                Math.min(zone.getYMin(), zone.getYMax()) - 8.0D,
                zone.getCenterZ() - radius,
                zone.getCenterX() + radius,
                Math.max(zone.getYMin(), zone.getYMax()) + 8.0D,
                zone.getCenterZ() + radius
        );

        String zoneTag = getZoneTag(zone);

        return level.getEntities(
                (Entity) null,
                box,
                entity -> entity.getTags().contains(MANAGED_TAG)
                        && entity.getTags().contains(zoneTag)
        );
    }

    private static boolean hasPlayersInside(ServerLevel level, SpawnZone zone) {
        for (Player player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }

            if (zone.isInside(player.getX(), player.getY(), player.getZ())) {
                return true;
            }
        }

        return false;
    }

    public static ServerLevel getLevel(MinecraftServer server, SpawnZone zone) {
        ResourceLocation location = ResourceLocation.parse(zone.getDimension());
        ResourceKey<Level> key = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, location);

        return server.getLevel(key);
    }

    public static void showZone(ServerLevel level, SpawnZone zone, int seconds) {
        int safeSeconds = Math.max(1, Math.min(seconds, 120));
        long endTick = level.getGameTime() + safeSeconds * 20L;

        VISUAL_TASKS.put(zone.getId(), new VisualTask(zone.getId(), zone.getDimension(), endTick));
        showZoneParticles(level, zone);
    }

    private static void showZoneParticles(ServerLevel level, SpawnZone zone) {
        double cx = zone.getCenterX();
        double cy = zone.getCenterY();
        double cz = zone.getCenterZ();

        level.sendParticles(ParticleTypes.END_ROD, cx, cy + 1.0D, cz, 20, 0.3D, 0.8D, 0.3D, 0.01D);

        int radius = zone.getRadius();

        for (int i = 0; i < 128; i++) {
            double angle = (Math.PI * 2.0D) * i / 128.0D;

            double x = cx + Math.cos(angle) * radius;
            double z = cz + Math.sin(angle) * radius;

            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, x, cy + 0.2D, z, 1, 0, 0, 0, 0);
        }

        for (SpawnPointData point : zone.getManualPoints()) {
            BlockPos pos = point.toBlockPos();
            level.sendParticles(ParticleTypes.FLAME, pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D, 8, 0.2D, 0.4D, 0.2D, 0.01D);
        }
    }

    private static int getTrackedMobCount(SpawnZone zone) {
        return zone.getActiveMobUuids().size() + getPendingCobblemonCount(zone);
    }

    private static int getPendingCobblemonCount(SpawnZone zone) {
        int count = 0;

        for (PendingCobblemonSpawn pending : PENDING_COBBLEMON_SPAWNS) {
            if (pending.zoneId.equalsIgnoreCase(zone.getId())) {
                count++;
            }
        }

        return count;
    }

    private static void tickPendingCobblemonSpawns(MinecraftServer server) {
        Iterator<PendingCobblemonSpawn> iterator = PENDING_COBBLEMON_SPAWNS.iterator();

        while (iterator.hasNext()) {
            PendingCobblemonSpawn pending = iterator.next();

            SpawnZone zone = getZone(pending.zoneId);

            if (zone == null) {
                iterator.remove();
                continue;
            }

            ServerLevel level = getLevel(server, zone);

            if (level == null) {
                iterator.remove();
                continue;
            }

            boolean resolved = tryResolvePendingCobblemonSpawn(level, zone, pending);

            if (resolved) {
                iterator.remove();
                continue;
            }

            long age = level.getGameTime() - pending.createdTick;

            if (age > COBBLEMON_PENDING_TIMEOUT_TICKS) {
                iterator.remove();
            }
        }
    }

    private static boolean tryResolvePendingCobblemonSpawn(ServerLevel level, SpawnZone zone, PendingCobblemonSpawn pending) {
        for (Entity entity : CobblemonCompat.getPokemonNear(level, pending.position, COBBLEMON_PENDING_SEARCH_RADIUS)) {
            UUID uuid = entity.getUUID();

            if (pending.beforeUuids.contains(uuid)) {
                continue;
            }

            if (entity.getTags().contains(MANAGED_TAG)) {
                continue;
            }

            entity.addTag(MANAGED_TAG);
            entity.addTag(getZoneTag(zone));

            SpawnMobEntry entry = findEntryById(zone, pending.entryId);
            if (entry != null) {
                entity.addTag(getEntryTag(entry));
            }

            if (!zone.getActiveMobUuids().contains(uuid)) {
                zone.getActiveMobUuids().add(uuid);
            }

            trackPokemonIdentity(entity, zone);
            return true;
        }

        return false;
    }

    /**
     * Entrega todos los drops configurados para una entidad administrada.
     *
     * Se llama únicamente desde una muerte real de NeoForge o desde BATTLE_FAINTED
     * de Cobblemon. Entity#discard, /mmspawn clear y borrar zonas no pasan por aquí.
     */
    public static void dropConfiguredRewards(Entity entity) {
        if (entity == null || !(entity.level() instanceof ServerLevel level)) {
            return;
        }

        if (!entity.getTags().contains(MANAGED_TAG)) {
            return;
        }

        SpawnZone zone = null;
        for (SpawnZone candidate : ZONES.values()) {
            if (entity.getTags().contains(getZoneTag(candidate))) {
                zone = candidate;
                break;
            }
        }

        if (zone == null) {
            return;
        }

        SpawnMobEntry entry = findEntryForTaggedEntity(zone, entity);

        if (entry == null || entry.getDrops().isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        Long previous = PROCESSED_DROP_UUIDS.putIfAbsent(entity.getUUID(), now);

        if (previous != null) {
            return;
        }

        for (SpawnDropEntry drop : entry.getDrops()) {
            ResourceLocation itemId;

            try {
                itemId = ResourceLocation.parse(drop.getItemId());
            } catch (Exception ignored) {
                continue;
            }

            Optional<Item> optionalItem = BuiltInRegistries.ITEM.getOptional(itemId);

            if (optionalItem.isEmpty() || optionalItem.get() == net.minecraft.world.item.Items.AIR) {
                continue;
            }

            Item item = optionalItem.get();
            int remaining = Math.max(1, drop.getCount());

            while (remaining > 0) {
                ItemStack stack = new ItemStack(item, 1);
                int amount = Math.min(remaining, stack.getMaxStackSize());
                stack.setCount(amount);

                ItemEntity itemEntity = new ItemEntity(
                        level,
                        entity.getX(),
                        entity.getY() + 0.25D,
                        entity.getZ(),
                        stack
                );
                itemEntity.setDefaultPickUpDelay();
                level.addFreshEntity(itemEntity);

                remaining -= amount;
            }
        }
    }

    /**
     * Asocia el UUID interno de Cobblemon con una entrada concreta de zona.
     * Mantiene el dato aunque la entidad deje de existir durante BATTLE_FAINTED.
     */
    private static void trackPokemonIdentity(Entity entity, SpawnZone zone) {
        if (!entity.getTags().contains(MANAGED_TAG)
                || !"cobblemon:pokemon".equals(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString())) {
            return;
        }

        // La marca viaja con el objeto Pokémon de Cobblemon a través de batallas
        // y sus recreaciones de entidad, sin depender de coordenadas.
        CobblemonCompat.markManagedPokemonZone(entity, zone.getId(), zone.isCaptureDenied());

        SpawnMobEntry entry = findEntryForTaggedEntity(zone, entity);
        if (entry == null || !CobblemonCompat.isCobblemonPokemonEntry(entry.getEntityId())) {
            return;
        }

        UUID pokemonUuid = CobblemonCompat.getPokemonUuidFromEntity(entity);
        if (pokemonUuid != null) {
            TrackedPokemonSpawn previous = TRACKED_POKEMON.put(pokemonUuid, new TrackedPokemonSpawn(
                    entity.getUUID(), zone.getId(), entry.getEntityId(), System.currentTimeMillis()
            ));
            if (previous == null || !previous.entityId().equals(entity.getUUID())) {
                CraftersMobManagerSpawn.LOGGER.info(
                        "MMSpawn: Pokémon vinculado a zona={} entrada={} pokemonUUID={}",
                        zone.getId(), entry.getEntityId(), pokemonUuid
                );
            }
        }
    }

    /**
     * Actualiza en el acto a los Pokémon cargados de la zona cuando el admin
     * usa 'pokemon capture deny/allow'; no recrea entidades ni cambia drops.
     */
    public static void refreshZoneCaptureProtection(MinecraftServer server, SpawnZone zone) {
        ServerLevel level = getLevel(server, zone);
        if (level == null) {
            return;
        }
        for (Entity entity : getTaggedMobsInZone(level, zone)) {
            CobblemonCompat.markManagedPokemonZone(entity, zone.getId(), zone.isCaptureDenied());
        }
        for (UUID id : zone.getActiveMobUuids()) {
            Entity entity = level.getEntity(id);
            if (entity != null && entity.getTags().contains(MANAGED_TAG)) {
                CobblemonCompat.markManagedPokemonZone(entity, zone.getId(), zone.isCaptureDenied());
            }
        }
    }

    /**
     * Mantiene el indicador nativo de Cobblemon actualizado también si el
     * Pokémon sale del radio original, cambia de entidad o se descarga/carga.
     * Se ejecuta sólo una vez por segundo por Pokémon, siempre en servidor.
     */
    @SubscribeEvent
    public static void onPokemonEntityTick(EntityTickEvent.Post event) {
        Entity entity = event.getEntity();
        if (entity.level().isClientSide || entity.tickCount % 20 != 0
                || !"cobblemon:pokemon".equals(
                    BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString())) {
            return;
        }

        if (entity.getTags().contains(MANAGED_TAG)) {
            for (SpawnZone zone : ZONES.values()) {
                if (entity.getTags().contains(getZoneTag(zone))) {
                    CobblemonCompat.markManagedPokemonZone(entity, zone.getId(), zone.isCaptureDenied());
                    return;
                }
            }
        }

        String zoneId = CobblemonCompat.getManagedZoneIdFromEntity(entity);
        if (zoneId != null) {
            SpawnZone zone = getZone(zoneId);
            CobblemonCompat.synchronizeNativeCaptureProperty(entity,
                    zone != null && zone.isCaptureDenied());
        }
    }

    /**
     * Consulta inmediata desde eventos cancelables de Cobblemon.
     * La protección afecta sólo a Pokémon creados y reconocidos por MMSpawn,
     * no a otros salvajes que compartan especie o estén dentro de la zona.
     */
    public static boolean isCaptureDenied(Entity pokemonEntity) {
        if (pokemonEntity == null
                || !"cobblemon:pokemon".equals(
                        BuiltInRegistries.ENTITY_TYPE.getKey(pokemonEntity.getType()).toString())) {
            return false;
        }

        // Los tags de entidad cubren los spawns anteriores a este parche.
        if (pokemonEntity.getTags().contains(MANAGED_TAG)) {
            for (SpawnZone zone : ZONES.values()) {
                if (pokemonEntity.getTags().contains(getZoneTag(zone))) {
                    return zone.isCaptureDenied();
                }
            }
        }

        // La persistencia de Cobblemon mantiene la asignación incluso si
        // la entidad cambia, entra en combate o se ha alejado de su spawn.
        String markedZone = CobblemonCompat.getManagedZoneIdFromEntity(pokemonEntity);
        if (markedZone != null) {
            SpawnZone zone = getZone(markedZone);
            return zone != null && zone.isCaptureDenied();
        }

        UUID pokemonUuid = CobblemonCompat.getPokemonUuidFromEntity(pokemonEntity);
        TrackedPokemonSpawn tracked = pokemonUuid == null ? null : TRACKED_POKEMON.get(pokemonUuid);
        if (tracked != null) {
            SpawnZone zone = getZone(tracked.zoneId());
            return zone != null && zone.isCaptureDenied();
        }
        return false;
    }

    public static int getTrackedPokemonCount() {
        return TRACKED_POKEMON.size();
    }

    public static int getPendingPokemonRewardCount() {
        return PENDING_BATTLE_REWARDS.values().stream().mapToInt(List::size).sum();
    }

    private static void cleanupTrackedPokemon() {
        // Cobblemon puede retirar del mundo a sus entidades al entrar en batalla.
        // No borramos la asociación inmediatamente cuando dejan de estar presentes.
        long cutoff = System.currentTimeMillis() - BATTLE_REWARD_TTL_MILLIS;
        TRACKED_POKEMON.entrySet().removeIf(entry -> entry.getValue().trackedAt() < cutoff);
    }

    public static void queuePokemonBattleRewardByPokemonUuid(
            UUID battleId, UUID pokemonUuid, UUID defeatedActorId,
            UUID attackerPlayerId, Component pokemonName
    ) {
        if (battleId == null || pokemonUuid == null) {
            return;
        }

        TrackedPokemonSpawn tracked = TRACKED_POKEMON.get(pokemonUuid);
        if (tracked == null) {
            CraftersMobManagerSpawn.LOGGER.debug(
                    "Faint Pokémon {} ignorado: no pertenece a una zona MMSpawn registrada", pokemonUuid);
            return;
        }

        SpawnZone zone = ZONES.get(tracked.zoneId());
        SpawnMobEntry entry = zone == null ? null : findEntryById(zone, tracked.entryId());
        if (entry == null || PROCESSED_DROP_UUIDS.containsKey(tracked.entityId())) {
            return;
        }

        List<PendingPokemonBattleReward> pending =
                PENDING_BATTLE_REWARDS.computeIfAbsent(battleId, ignored -> new ArrayList<>());

        for (PendingPokemonBattleReward previous : pending) {
            if (previous.entityId().equals(tracked.entityId())) {
                return;
            }
        }

        List<SpawnDropEntry> drops = new ArrayList<>();
        for (SpawnDropEntry drop : entry.getDrops()) {
            if (drop.getItemId() != null) {
                drops.add(new SpawnDropEntry(drop.getItemId(), drop.getCount()));
            }
        }

        pending.add(new PendingPokemonBattleReward(
                tracked.entityId(), defeatedActorId, attackerPlayerId,
                pokemonName == null ? Component.literal("Pokémon") : pokemonName.copy(),
                drops, System.currentTimeMillis()
        ));
        CraftersMobManagerSpawn.LOGGER.info(
                "MMSpawn: faint registrado para {} en combate {}; premios configurados: {}",
                pokemonName == null ? pokemonUuid : pokemonName.getString(), battleId, drops.size());
    }

    private record TrackedPokemonSpawn(UUID entityId, String zoneId, String entryId, long trackedAt) {
    }

    /**
     * Registra un KO en el combate, sin entregar todavía premios. Puede llamarse
     * cuando el Pokémon hace faint, incluso si el equipo todavía puede pelear.
     */
    public static void queuePokemonBattleReward(
            UUID battleId,
            Entity defeatedEntity,
            UUID defeatedActorId,
            UUID finalAttackerPlayerId,
            Component pokemonName
    ) {
        if (battleId == null || defeatedEntity == null
                || !(defeatedEntity.level() instanceof ServerLevel)
                || !defeatedEntity.getTags().contains(MANAGED_TAG)) {
            return;
        }

        SpawnZone zone = null;
        for (SpawnZone candidate : ZONES.values()) {
            if (defeatedEntity.getTags().contains(getZoneTag(candidate))) {
                zone = candidate;
                break;
            }
        }

        if (zone == null || PROCESSED_DROP_UUIDS.containsKey(defeatedEntity.getUUID())) {
            return;
        }

        SpawnMobEntry entry = findEntryForTaggedEntity(zone, defeatedEntity);
        if (entry == null || !CobblemonCompat.isCobblemonPokemonEntry(entry.getEntityId())) {
            return;
        }

        List<PendingPokemonBattleReward> pending =
                PENDING_BATTLE_REWARDS.computeIfAbsent(battleId, ignored -> new ArrayList<>());

        for (PendingPokemonBattleReward previous : pending) {
            if (previous.entityId().equals(defeatedEntity.getUUID())) {
                return;
            }
        }

        List<SpawnDropEntry> snapshot = new ArrayList<>();
        for (SpawnDropEntry drop : entry.getDrops()) {
            if (drop.getItemId() != null) {
                snapshot.add(new SpawnDropEntry(drop.getItemId(), drop.getCount()));
            }
        }

        pending.add(new PendingPokemonBattleReward(
                defeatedEntity.getUUID(),
                defeatedActorId,
                finalAttackerPlayerId,
                pokemonName == null ? Component.literal("Pokémon") : pokemonName.copy(),
                snapshot,
                System.currentTimeMillis()
        ));
    }

    /**
     * Única ruta que entrega premios de Pokémon: una victoria confirmada de Cobblemon.
     * No paga en capturas, huidas, derrotas del jugador ni limpiezas administrativas.
     */
    public static void completePokemonBattleRewards(
            UUID battleId,
            List<UUID> winnerPlayerIds,
            Set<UUID> losingActorIds,
            boolean wasWildCapture
    ) {
        List<PendingPokemonBattleReward> rewards = PENDING_BATTLE_REWARDS.remove(battleId);
        if (rewards == null || rewards.isEmpty() || wasWildCapture
                || winnerPlayerIds == null || winnerPlayerIds.isEmpty() || currentServer == null) {
            CraftersMobManagerSpawn.LOGGER.debug(
                    "MMSpawn: victoria {} sin premios: registros={}, captura={}, jugadores ganadores={}",
                    battleId, rewards == null ? 0 : rewards.size(),
                    wasWildCapture, winnerPlayerIds == null ? 0 : winnerPlayerIds.size()
            );
            return;
        }

        for (PendingPokemonBattleReward reward : rewards) {
            if (reward.defeatedActorId() == null
                    || losingActorIds == null
                    || !losingActorIds.contains(reward.defeatedActorId())) {
                continue;
            }

            // Preferimos el Pokémon que ejecutó el KO; de no ser posible, el vencedor.
            UUID playerId = reward.finalAttackerPlayerId();
            if (playerId == null || !winnerPlayerIds.contains(playerId)) {
                playerId = winnerPlayerIds.get(0);
            }

            ServerPlayer player = currentServer.getPlayerList().getPlayer(playerId);
            if (player == null) {
                CraftersMobManagerSpawn.LOGGER.warn(
                        "No se pudieron entregar premios al ganador desconectado {} para el Pokémon {}",
                        playerId, reward.pokemonName().getString()
                );
                continue;
            }

            if (PROCESSED_DROP_UUIDS.putIfAbsent(reward.entityId(), System.currentTimeMillis()) != null) {
                continue;
            }

            for (SpawnDropEntry drop : reward.drops()) {
                ResourceLocation itemId;
                try {
                    itemId = ResourceLocation.parse(drop.getItemId());
                } catch (Exception ignored) {
                    continue;
                }

                Optional<Item> optionalItem = BuiltInRegistries.ITEM.getOptional(itemId);
                if (optionalItem.isEmpty() || optionalItem.get() == net.minecraft.world.item.Items.AIR) {
                    continue;
                }

                Item item = optionalItem.get();
                int remaining = Math.max(1, drop.getCount());
                while (remaining > 0) {
                    ItemStack stack = new ItemStack(item, 1);
                    int amount = Math.min(remaining, stack.getMaxStackSize());
                    stack.setCount(amount);

                    // Insertar explícitamente sólo lo que realmente cabe en el
                    // inventario principal. Inventory.add puede vaciar el stack
                    // en creativo incluso si el inventario está lleno.
                    stack = insertRewardIntoInventory(player, stack);

                    // Los sobrantes aparecen como drops públicos normales.
                    // Guardamos el UUID del destinatario original en una etiqueta
                    // persistente de la entidad SOLO para trazabilidad; NO usamos
                    // setTarget/setOwner, pues restringirían quién puede recogerla.
                    if (!stack.isEmpty()) {
                        ItemEntity overflow = new ItemEntity(
                                player.serverLevel(),
                                player.getX(),
                                player.getY() + 0.25D,
                                player.getZ(),
                                stack.copy()
                        );
                        overflow.addTag("mmspawn_reward_for_" + player.getUUID().toString().replace("-", ""));
                        // Da tiempo a que la entidad aparezca y evita su recogida
                        // inmediata al generarse sobre el jugador.
                        overflow.setPickUpDelay(40);
                        if (player.serverLevel().addFreshEntity(overflow)) {
                            CraftersMobManagerSpawn.LOGGER.info(
                                    "MMSpawn: premio en suelo para {}: {}x {} (entidad={}, público)",
                                    player.getGameProfile().getName(),
                                    stack.getCount(), drop.getItemId(), overflow.getUUID()
                            );
                        } else {
                            CraftersMobManagerSpawn.LOGGER.error(
                                    "MMSpawn: no se pudo generar un premio sobrante para {}: {}x {}",
                                    player.getGameProfile().getName(),
                                    stack.getCount(), drop.getItemId()
                            );
                        }
                    }
                    remaining -= amount;
                }
            }

            player.getInventory().setChanged();

            Component announcement = Component.literal(player.getGameProfile().getName() + " derrotó a ")
                    .withStyle(ChatFormatting.YELLOW)
                    .append(reward.pokemonName().copy().withStyle(ChatFormatting.YELLOW));
            currentServer.getPlayerList().broadcastSystemMessage(announcement, false);
        }
    }

    /**
     * Inserta recompensas sin la excepción del modo creativo que puede
     * consumir ItemStacks sobrantes sin crear un objeto visible.
     *
     * Devuelve una pila nueva con la cantidad exacta que NO cupo.
     * Recorre exclusivamente los 36 slots principales (incluida hotbar).
     */
    private static ItemStack insertRewardIntoInventory(ServerPlayer player, ItemStack reward) {
        ItemStack leftover = reward.copy();
        var inventory = player.getInventory();

        // 1) Completar pilas compatibles existentes.
        for (int slot = 0; slot < inventory.items.size() && !leftover.isEmpty(); slot++) {
            ItemStack inSlot = inventory.items.get(slot);
            if (inSlot.isEmpty() || !ItemStack.isSameItemSameComponents(inSlot, leftover)) {
                continue;
            }

            int room = Math.max(0, inSlot.getMaxStackSize() - inSlot.getCount());
            if (room > 0) {
                int moved = Math.min(room, leftover.getCount());
                inSlot.grow(moved);
                leftover.shrink(moved);
            }
        }

        // 2) Llenar slots principales vacíos, respetando el máximo por pila.
        for (int slot = 0; slot < inventory.items.size() && !leftover.isEmpty(); slot++) {
            if (!inventory.items.get(slot).isEmpty()) {
                continue;
            }

            int moved = Math.min(leftover.getCount(), leftover.getMaxStackSize());
            inventory.setItem(slot, leftover.copyWithCount(moved));
            leftover.shrink(moved);
        }

        inventory.setChanged();
        player.containerMenu.broadcastChanges();
        return leftover;
    }

    private static void cleanupExpiredBattleRewards() {
        long cutoff = System.currentTimeMillis() - BATTLE_REWARD_TTL_MILLIS;
        PENDING_BATTLE_REWARDS.entrySet().removeIf(entry -> {
            entry.getValue().removeIf(reward -> reward.createdAt() < cutoff);
            return entry.getValue().isEmpty();
        });
    }

    private record PendingPokemonBattleReward(
            UUID entityId,
            UUID defeatedActorId,
            UUID finalAttackerPlayerId,
            Component pokemonName,
            List<SpawnDropEntry> drops,
            long createdAt
    ) {
    }

    private static SpawnMobEntry findEntryForTaggedEntity(SpawnZone zone, Entity entity) {
        for (SpawnMobEntry entry : zone.getMobs()) {
            if (entity.getTags().contains(getEntryTag(entry))) {
                return entry;
            }
        }

        /*
         * Fallback para mobs normales que hayan quedado vivos desde una versión anterior
         * del mod, cuando todavía no existía la etiqueta de entrada.
         */
        String actualEntityId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();

        if (!"cobblemon:pokemon".equals(actualEntityId)) {
            for (SpawnMobEntry entry : zone.getMobs()) {
                if (entry.getEntityId().equalsIgnoreCase(actualEntityId)) {
                    return entry;
                }
            }
        }

        return null;
    }

    private static SpawnMobEntry findEntryById(SpawnZone zone, String entryId) {
        if (entryId == null) {
            return null;
        }

        for (SpawnMobEntry entry : zone.getMobs()) {
            if (entry.getEntityId().equalsIgnoreCase(entryId)) {
                return entry;
            }
        }

        return null;
    }

    private static String getEntryTag(SpawnMobEntry entry) {
        UUID stableId = UUID.nameUUIDFromBytes(
                entry.getEntityId().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8)
        );
        return "mmspawn_entry_" + stableId.toString().replace("-", "");
    }

    private static void cleanupProcessedDropUuids() {
        long cutoff = System.currentTimeMillis() - PROCESSED_DROP_TTL_MILLIS;
        PROCESSED_DROP_UUIDS.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }

    public static boolean isValidItemId(ResourceLocation itemId) {
        Optional<Item> item = BuiltInRegistries.ITEM.getOptional(itemId);
        return item.isPresent() && item.get() != net.minecraft.world.item.Items.AIR;
    }

    public static List<String> getItemIds() {
        return BuiltInRegistries.ITEM
                .keySet()
                .stream()
                .map(ResourceLocation::toString)
                .filter(id -> !"minecraft:air".equals(id))
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    public static List<String> getEntityIds() {
        return BuiltInRegistries.ENTITY_TYPE
                .keySet()
                .stream()
                .map(ResourceLocation::toString)
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    /**
     * Devuelve segundos restantes para el cooldown de una zona.
     * -1 significa que no hay cooldown pendiente.
     */
    public static long getRespawnRemainingSeconds(SpawnZone zone) {
        if (zone.getRespawnMode() != RespawnMode.COOLDOWN) {
            return -1L;
        }

        long next = zone.getNextRespawnEpochMillis();

        if (next < 0L) {
            return -1L;
        }

        if (next == 0L) {
            return 0L;
        }

        long diff = next - System.currentTimeMillis();
        return Math.max(0L, (diff + 999L) / 1000L);
    }

    private static String getZoneTag(SpawnZone zone) {
        return "mmspawn_zone_" + zone.getId().replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static class VisualTask {
        private final String zoneId;
        private final String dimension;
        private final long endTick;

        private VisualTask(String zoneId, String dimension, long endTick) {
            this.zoneId = zoneId;
            this.dimension = dimension;
            this.endTick = endTick;
        }
    }

    private static class PendingCobblemonSpawn {
        private final String zoneId;
        private final String entryId;
        private final Vec3 position;
        private final Set<UUID> beforeUuids;
        private final long createdTick;

        private PendingCobblemonSpawn(String zoneId, String entryId, Vec3 position, Set<UUID> beforeUuids, long createdTick) {
            this.zoneId = zoneId;
            this.entryId = entryId;
            this.position = position;
            this.beforeUuids = beforeUuids;
            this.createdTick = createdTick;
        }
    }
}
