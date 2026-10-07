package org.crafterscr.craftersmobmanagerspawn.data;

import java.util.ArrayList;
import java.util.List;

public class SpawnMobEntry {

    private String entityId;
    private int weight;
    private List<SpawnDropEntry> drops = new ArrayList<>();

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

    public List<SpawnDropEntry> getDrops() {
        if (drops == null) {
            drops = new ArrayList<>();
        }
        return drops;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public void setWeight(int weight) {
        this.weight = Math.max(1, weight);
    }

    /**
     * Agrega o reemplaza el drop de un mismo item.
     * Todos los drops configurados son garantizados.
     */
    public void addDrop(String itemId, int count) {
        for (SpawnDropEntry drop : getDrops()) {
            if (drop.getItemId().equalsIgnoreCase(itemId)) {
                drop.setCount(count);
                return;
            }
        }

        getDrops().add(new SpawnDropEntry(itemId, count));
    }

    public boolean removeDrop(String itemId) {
        return getDrops().removeIf(drop -> drop.getItemId().equalsIgnoreCase(itemId));
    }

    public void clearDrops() {
        getDrops().clear();
    }
}
