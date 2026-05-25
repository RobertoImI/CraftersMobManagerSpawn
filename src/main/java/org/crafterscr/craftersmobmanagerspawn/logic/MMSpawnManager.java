package org.crafterscr.craftersmobmanagerspawn.logic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.crafterscr.craftersmobmanagerspawn.data.MMSpawnStorage;
import org.crafterscr.craftersmobmanagerspawn.data.SpawnMobEntry;
import org.crafterscr.craftersmobmanagerspawn.data.SpawnPointData;
import org.crafterscr.craftersmobmanagerspawn.data.SpawnZone;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/**
 * Clase central del sistema de spawn.
 *
 * Aquí se guardan las zonas en memoria, se cargan/guardan desde JSON,
 * se ejecuta el tick del servidor, se controla el respawn progresivo,
 * se etiquetan mobs del mod y se muestran partículas temporales.
 */
public class MMSpawnManager {

    // Todas las zonas cargadas, organizadas por ID.
    private static final Map<String, SpawnZone> ZONES = new LinkedHashMap<>();
    // Tareas temporales para mostrar partículas por varios segundos.
    private static final Map<String, VisualTask> VISUAL_TASKS = new LinkedHashMap<>();

    // Random compartido para elegir mobs, posiciones y pesos.
    private static final Random RANDOM = new Random();

    // Etiqueta general que se agrega a todo mob creado por este mod.
    private static final String MANAGED_TAG = "mmspawn_managed";

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
    }

    /**
     * Tick del servidor.
     * El juego corre a 20 ticks por segundo, pero este sistema trabaja una vez por segundo.
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();

        tickCounter++;

        if (tickCounter < 20) {
            return;
        }

        tickCounter = 0;

        for (SpawnZone zone : ZONES.values()) {
            tickZone(server, zone);
        }

        tickVisualTasks(server);
    }

    /**
     * Lógica principal de una zona.
     *
     * Flujo:
     * 1. Si la zona está detenida, no hace nada.
     * 2. Re-sincroniza mobs con etiquetas del mod.
     * 3. Limpia UUIDs de mobs muertos.
     * 4. Si hay jugadores dentro, cancela el contador de zona vacía.
     * 5. Si la zona está vacía, espera emptyDelay.
     * 6. Después repone mobs faltantes poco a poco según spawnInterval.
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
        cleanupDeadMobs(level, zone);

        if (zone.getMobs().isEmpty()) {
            return;
        }

        if (zone.getActiveMobUuids().size() >= zone.getMaxAlive()) {
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

        long emptyDelayTicks = zone.getEmptyDelaySeconds() * 20L;

        if (gameTime - zone.getEmptySinceTick() < emptyDelayTicks) {
            return;
        }

        long intervalTicks = zone.getSpawnIntervalSeconds() * 20L;

        if (zone.getLastSpawnTick() >= 0 && gameTime - zone.getLastSpawnTick() < intervalTicks) {
            return;
        }

        boolean spawned = spawnOneMob(level, zone);

        // Solo actualizamos el intervalo si realmente se pudo crear un mob.
        if (spawned) {
            zone.setLastSpawnTick(gameTime);
        }
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
     *
     * Usa dos sistemas:
     * - UUIDs registrados en memoria.
     * - Etiquetas persistentes en los mobs, útil después de reinicios.
     */
    public static void clearActiveMobs(MinecraftServer server, SpawnZone zone) {
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
     * Respeta maxAlive, pesos, puntos manuales y modo de altura.
     */
    public static int forceRespawn(ServerLevel level, SpawnZone zone) {
        syncTaggedMobs(level, zone);
        cleanupDeadMobs(level, zone);

        int spawned = 0;

        while (zone.getActiveMobUuids().size() < zone.getMaxAlive()) {
            boolean success = spawnOneMob(level, zone);

            if (!success) {
                break;
            }

            spawned++;
        }

        return spawned;
    }

    /**
     * Crea un solo mob de la zona.
     * El mob se elige por peso y se etiqueta para reconocerlo después.
     */
    private static boolean spawnOneMob(ServerLevel level, SpawnZone zone) {
        SpawnMobEntry entry = pickWeightedMob(zone);

        if (entry == null) {
            return false;
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

        BlockPos spawnPos = chooseSpawnPos(level, zone);

        if (spawnPos == null) {
            return false;
        }

        entity.moveTo(
                spawnPos.getX() + 0.5,
                spawnPos.getY(),
                spawnPos.getZ() + 0.5,
                RANDOM.nextFloat() * 360.0F,
                0.0F
        );

        // Etiquetas persistentes. Sirven para limpiar/reconocer mobs incluso después de reiniciar.
        entity.addTag(MANAGED_TAG);
        entity.addTag(getZoneTag(zone));

        if (entity instanceof Mob mob) {
            mob.setPersistenceRequired();
        }

        boolean added = level.addFreshEntity(entity);

        if (added) {
            zone.getActiveMobUuids().add(entity.getUUID());
        }

        return added;
    }

    /**
     * Elige un mob según su peso.
     * Peso alto = más probabilidad de salir.
     */
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

        return zone.getMobs().getFirst();
    }

    /**
     * Decide la posición final del spawn.
     *
     * Si hay puntos manuales, intenta usarlos primero.
     * Si no hay puntos manuales, busca una posición aleatoria dentro del radio
     * usando el modo de altura configurado.
     */
    private static BlockPos chooseSpawnPos(ServerLevel level, SpawnZone zone) {
        if (!zone.getManualPoints().isEmpty()) {
            // Probamos varios puntos manuales por si uno está bloqueado.
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

    /**
     * EXACT: usa exactamente la altura del centro de la zona.
     * Ideal para bosses o spawns muy controlados.
     */
    private static BlockPos findExactPos(ServerLevel level, int x, int z, SpawnZone zone) {
        int y = clamp((int) Math.round(zone.getCenterY()), zone.getYMin(), zone.getYMax());
        BlockPos pos = new BlockPos(x, y, z);

        if (isSpawnSpaceValid(level, pos)) {
            return pos;
        }

        return null;
    }

    /**
     * GROUND: busca desde arriba hacia abajo hasta encontrar suelo sólido.
     * Ideal para exteriores, bosques, campos o zonas naturales.
     */
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

    /**
     * FLOOR: busca un suelo cerca de la altura central de la zona.
     * Ideal para interiores, pisos de mazmorras, torres o edificios.
     */
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

    /**
     * AIR: busca un espacio libre aleatorio dentro del rango Y.
     * Ideal para mobs voladores.
     */
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

    /**
     * Verifica que el mob tenga dos bloques de aire para aparecer sin ahogarse en bloques.
     */
    private static boolean isSpawnSpaceValid(ServerLevel level, BlockPos pos) {
        BlockState current = level.getBlockState(pos);
        BlockState above = level.getBlockState(pos.above());

        return current.isAir() && above.isAir();
    }

    /**
     * Reconecta mobs que ya existen en el mundo y tienen etiquetas del mod.
     * Esto ayuda después de reiniciar el servidor.
     */
    private static void syncTaggedMobs(ServerLevel level, SpawnZone zone) {
        for (Entity entity : getTaggedMobsInZone(level, zone)) {
            UUID uuid = entity.getUUID();

            if (!zone.getActiveMobUuids().contains(uuid)) {
                zone.getActiveMobUuids().add(uuid);
            }
        }
    }

    private static void cleanupDeadMobs(ServerLevel level, SpawnZone zone) {
        zone.getActiveMobUuids().removeIf(uuid -> {
            Entity entity = level.getEntity(uuid);
            return entity == null || !entity.isAlive();
        });
    }

    /**
     * Busca entidades con etiquetas del mod dentro del área aproximada de la zona.
     */
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

    /**
     * Crea o reemplaza una tarea visual para mostrar una zona con partículas por varios segundos.
     */
    public static void showZone(ServerLevel level, SpawnZone zone, int seconds) {
        int safeSeconds = Math.max(1, Math.min(seconds, 120));
        long endTick = level.getGameTime() + safeSeconds * 20L;

        VISUAL_TASKS.put(zone.getId(), new VisualTask(zone.getId(), zone.getDimension(), endTick));
        showZoneParticles(level, zone);
    }

    /**
     * Dibuja centro, radio y puntos manuales con partículas.
     */
    private static void showZoneParticles(ServerLevel level, SpawnZone zone) {
        double cx = zone.getCenterX();
        double cy = zone.getCenterY();
        double cz = zone.getCenterZ();

        level.sendParticles(ParticleTypes.END_ROD, cx, cy + 1.0, cz, 20, 0.3, 0.8, 0.3, 0.01);

        int radius = zone.getRadius();

        for (int i = 0; i < 128; i++) {
            double angle = (Math.PI * 2.0D) * i / 128.0D;

            double x = cx + Math.cos(angle) * radius;
            double z = cz + Math.sin(angle) * radius;

            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, x, cy + 0.2, z, 1, 0, 0, 0, 0);
        }

        for (SpawnPointData point : zone.getManualPoints()) {
            BlockPos pos = point.toBlockPos();
            level.sendParticles(ParticleTypes.FLAME, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 8, 0.2, 0.4, 0.2, 0.01);
        }
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
     * Crea una etiqueta segura para los mobs de una zona específica.
     */
    private static String getZoneTag(SpawnZone zone) {
        return "mmspawn_zone_" + zone.getId().replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Datos de una visualización temporal de zona.
     */
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
}