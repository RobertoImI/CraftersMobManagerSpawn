package org.crafterscr.craftersmobmanagerspawn.data;

import com.google.gson.annotations.SerializedName;
import org.crafterscr.craftersmobmanagerspawn.util.HeightMode;
import org.crafterscr.craftersmobmanagerspawn.util.RespawnMode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Representa una zona de spawn completa.
 *
 * Guarda configuración permanente en JSON:
 * ID, dimensión, centro, radio, alturas, mobs, modo de altura,
 * modo de respawn, delay, puntos manuales y estado activo.
 *
 * También maneja datos temporales en memoria usando transient:
 * UUIDs activos, contador de zona vacía y último spawn.
 */
public class SpawnZone {

    // ID único de la zona. Ejemplo: bosque_slimes, dungeon_piso1, boss_ruinas.
    private String id;

    // Dimensión donde existe la zona. Ejemplo: minecraft:overworld.
    private String dimension = "minecraft:overworld";

    // Centro de la zona.
    private double centerX;
    private double centerY;
    private double centerZ;

    // Radio horizontal de la zona. Se mide desde el centro hacia X/Z.
    private int radius = 20;

    // Rango vertical permitido para detectar jugadores y buscar puntos de spawn.
    private int yMin = -64;
    private int yMax = 320;

    // Modo que decide cómo buscar la altura final de spawn.
    private HeightMode heightMode = HeightMode.GROUND;

    // Define si la zona funciona por zona vacía o por cooldown fijo.
    private RespawnMode respawnMode = RespawnMode.EMPTY;

    // Cantidad máxima de mobs vivos que esta zona puede mantener.
    private int maxAlive = 5;

    /**
     * Tiempo general de respawn.
     *
     * En modo EMPTY:
     * La zona debe estar vacía estos segundos antes de reponer mobs.
     *
     * En modo COOLDOWN:
     * Después de que el boss/mob muere, espera estos segundos antes de volver.
     *
     * El alternate permite cargar JSON viejo que todavía tenga emptyDelaySeconds.
     */
    @SerializedName(value = "respawnDelaySeconds", alternate = {"emptyDelaySeconds"})
    private int respawnDelaySeconds = 180;

    // Tiempo entre cada mob generado durante el respawn progresivo.
    private int spawnIntervalSeconds = 5;

    /**
     * Próximo respawn programado en tiempo real del sistema.
     *
     * Solo se usa en modo COOLDOWN.
     * Se guarda en JSON para que el cooldown sobreviva reinicios del servidor.
     * -1 significa que no hay cooldown programado.
     * 0 significa que el respawn está listo.
     */
    private long nextRespawnEpochMillis = -1L;

    // Indica si la zona está funcionando o detenida.
    private boolean active = false;

    // Default false para mantener capturables todas las zonas existentes.
    // Sólo los Pokémon generados por esta zona quedan protegidos.
    private boolean captureDenied = false;

    // Lista de mobs posibles para esta zona, cada uno con su peso.
    private List<SpawnMobEntry> mobs = new ArrayList<>();

    // Puntos manuales de aparición. Si existen, el mod los usa antes que posiciones aleatorias.
    private List<SpawnPointData> manualPoints = new ArrayList<>();

    // UUIDs de mobs activos creados por esta zona. Es temporal y se reconstruye con etiquetas.
    private transient List<UUID> activeMobUuids = new ArrayList<>();

    // Tick en el que la zona quedó vacía. Solo se usa en modo EMPTY.
    private transient long emptySinceTick = -1;

    // Último tick en el que apareció un mob. Sirve para respetar spawnInterval.
    private transient long lastSpawnTick = -1;

    public SpawnZone() {
    }

    public SpawnZone(String id) {
        this.id = id;
    }

    /**
     * Se llama al cargar zonas desde JSON.
     * Limpia datos temporales que no deben venir guardados en el archivo.
     */
    public void resetRuntime() {
        this.activeMobUuids = new ArrayList<>();
        this.emptySinceTick = -1;
        this.lastSpawnTick = -1;

        if (this.respawnMode == null) {
            this.respawnMode = RespawnMode.EMPTY;
        }
    }

    public String getId() {
        return id;
    }

    public String getDimension() {
        return dimension;
    }

    public void setDimension(String dimension) {
        this.dimension = dimension;
    }

    public double getCenterX() {
        return centerX;
    }

    public double getCenterY() {
        return centerY;
    }

    public double getCenterZ() {
        return centerZ;
    }

    /**
     * Define el centro de la zona.
     */
    public void setCenter(double x, double y, double z) {
        this.centerX = x;
        this.centerY = y;
        this.centerZ = z;
    }

    public int getRadius() {
        return radius;
    }

    public void setRadius(int radius) {
        this.radius = Math.max(1, radius);
    }

    public int getYMin() {
        return yMin;
    }

    public void setYMin(int yMin) {
        this.yMin = yMin;
    }

    public int getYMax() {
        return yMax;
    }

    public void setYMax(int yMax) {
        this.yMax = yMax;
    }

    public HeightMode getHeightMode() {
        return heightMode;
    }

    public void setHeightMode(HeightMode heightMode) {
        this.heightMode = heightMode;
    }

    public RespawnMode getRespawnMode() {
        if (respawnMode == null) {
            respawnMode = RespawnMode.EMPTY;
        }

        return respawnMode;
    }

    public void setRespawnMode(RespawnMode respawnMode) {
        this.respawnMode = respawnMode == null ? RespawnMode.EMPTY : respawnMode;

        // Al cambiar de modo reiniciamos contadores temporales para evitar estados raros.
        this.emptySinceTick = -1;
        this.lastSpawnTick = -1;

        if (this.respawnMode == RespawnMode.EMPTY) {
            this.nextRespawnEpochMillis = -1L;
        }
    }

    public int getMaxAlive() {
        return maxAlive;
    }

    public void setMaxAlive(int maxAlive) {
        this.maxAlive = Math.max(0, maxAlive);
    }

    public int getRespawnDelaySeconds() {
        return respawnDelaySeconds;
    }

    public void setRespawnDelaySeconds(int respawnDelaySeconds) {
        this.respawnDelaySeconds = Math.max(0, respawnDelaySeconds);
    }

    public int getSpawnIntervalSeconds() {
        return spawnIntervalSeconds;
    }

    public void setSpawnIntervalSeconds(int spawnIntervalSeconds) {
        this.spawnIntervalSeconds = Math.max(1, spawnIntervalSeconds);
    }

    public long getNextRespawnEpochMillis() {
        return nextRespawnEpochMillis;
    }

    public void setNextRespawnEpochMillis(long nextRespawnEpochMillis) {
        this.nextRespawnEpochMillis = nextRespawnEpochMillis;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isCaptureDenied() {
        return captureDenied;
    }

    public void setCaptureDenied(boolean captureDenied) {
        this.captureDenied = captureDenied;
    }

    public List<SpawnMobEntry> getMobs() {
        return mobs;
    }

    public List<SpawnPointData> getManualPoints() {
        return manualPoints;
    }

    public List<UUID> getActiveMobUuids() {
        if (activeMobUuids == null) {
            activeMobUuids = new ArrayList<>();
        }

        return activeMobUuids;
    }

    public long getEmptySinceTick() {
        return emptySinceTick;
    }

    public void setEmptySinceTick(long emptySinceTick) {
        this.emptySinceTick = emptySinceTick;
    }

    public long getLastSpawnTick() {
        return lastSpawnTick;
    }

    public void setLastSpawnTick(long lastSpawnTick) {
        this.lastSpawnTick = lastSpawnTick;
    }

    /**
     * Revisa si una posición está dentro del cilindro de la zona.
     * Usa radio en X/Z y también respeta yMin/yMax.
     */
    public boolean isInside(double x, double y, double z) {
        if (y < yMin || y > yMax) {
            return false;
        }

        double dx = x - centerX;
        double dz = z - centerZ;

        return dx * dx + dz * dz <= radius * radius;
    }

    /**
     * Agrega un mob a la zona.
     * Si ya existía ese mismo entityId, lo reemplaza con el nuevo peso.
     */
    public void addMob(String entityId, int weight) {
        for (SpawnMobEntry entry : mobs) {
            if (entry.getEntityId().equalsIgnoreCase(entityId)) {
                // Actualizar el peso no debe borrar los drops ya configurados.
                entry.setWeight(weight);
                return;
            }
        }

        mobs.add(new SpawnMobEntry(entityId, Math.max(1, weight)));
    }

    public void removeMob(String entityId) {
        mobs.removeIf(entry -> entry.getEntityId().equalsIgnoreCase(entityId));
    }

    public void clearMobs() {
        mobs.clear();
    }

    public void addManualPoint(SpawnPointData point) {
        manualPoints.add(point);
    }

    public void clearManualPoints() {
        manualPoints.clear();
    }
}
