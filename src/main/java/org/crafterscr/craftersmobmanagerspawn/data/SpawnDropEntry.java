package org.crafterscr.craftersmobmanagerspawn.data;

/**
 * Recompensa garantizada asociada a una entrada de spawn.
 *
 * Cada vez que la entidad/Pokémon administrado es derrotado de verdad,
 * se genera siempre la cantidad configurada de este item.
 */
public class SpawnDropEntry {

    private String itemId;
    private int count = 1;

    public SpawnDropEntry() {
    }

    public SpawnDropEntry(String itemId, int count) {
        this.itemId = itemId;
        this.count = Math.max(1, count);
    }

    public String getItemId() {
        return itemId;
    }

    public int getCount() {
        return Math.max(1, count);
    }

    public void setItemId(String itemId) {
        this.itemId = itemId;
    }

    public void setCount(int count) {
        this.count = Math.max(1, count);
    }
}
