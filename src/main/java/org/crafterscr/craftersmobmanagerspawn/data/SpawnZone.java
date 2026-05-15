package org.crafterscr.craftersmobmanagerspawn.data;

import org.crafterscr.craftersmobmanagerspawn.util.HeightMode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SpawnZone {

    private String id;

    private String dimension = "minecraft:overworld";

    private double centerX;
    private double centerY;
    private double centerZ;

    private int radius = 20;

    private int yMin = -64;
    private int yMax = 320;

    private HeightMode heightMode = HeightMode.GROUND;

    private int maxAlive = 5;

    private int emptyDelaySeconds = 180;
    private int spawnIntervalSeconds = 5;

    private boolean active = false;

    private List<SpawnMobEntry> mobs = new ArrayList<>();
    private List<SpawnPointData> manualPoints = new ArrayList<>();

    private transient List<UUID> activeMobUuids = new ArrayList<>();

    private transient long emptySinceTick = -1;
    private transient long lastSpawnTick = -1;

    public SpawnZone() {
    }

    public SpawnZone(String id) {
        this.id = id;
    }

    public void resetRuntime() {
        this.activeMobUuids = new ArrayList<>();
        this.emptySinceTick = -1;
        this.lastSpawnTick = -1;
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

    public int getMaxAlive() {
        return maxAlive;
    }

    public void setMaxAlive(int maxAlive) {
        this.maxAlive = Math.max(0, maxAlive);
    }

    public int getEmptyDelaySeconds() {
        return emptyDelaySeconds;
    }

    public void setEmptyDelaySeconds(int emptyDelaySeconds) {
        this.emptyDelaySeconds = Math.max(0, emptyDelaySeconds);
    }

    public int getSpawnIntervalSeconds() {
        return spawnIntervalSeconds;
    }

    public void setSpawnIntervalSeconds(int spawnIntervalSeconds) {
        this.spawnIntervalSeconds = Math.max(1, spawnIntervalSeconds);
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
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

    public boolean isInside(double x, double y, double z) {
        if (y < yMin || y > yMax) {
            return false;
        }

        double dx = x - centerX;
        double dz = z - centerZ;

        return dx * dx + dz * dz <= radius * radius;
    }

    public void addMob(String entityId, int weight) {
        mobs.removeIf(entry -> entry.getEntityId().equalsIgnoreCase(entityId));
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