package org.crafterscr.craftersmobmanagerspawn.data;

public class SpawnMobEntry {

    private String entityId;
    private int weight;

    public SpawnMobEntry() {
    }

    public SpawnMobEntry(String entityId, int weight) {
        this.entityId = entityId;
        this.weight = weight;
    }

    public String getEntityId() {
        return entityId;
    }

    public int getWeight() {
        return weight;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public void setWeight(int weight) {
        this.weight = weight;
    }
}