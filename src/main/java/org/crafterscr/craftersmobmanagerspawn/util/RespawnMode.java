package org.crafterscr.craftersmobmanagerspawn.util;

/**
 * Define cómo una zona decide cuándo reponer mobs faltantes.
 *
 * EMPTY:
 * La zona debe estar vacía durante respawnDelaySeconds.
 * Si un jugador entra, el contador se reinicia.
 * Ideal para zonas normales de farmeo, bosques, cuevas y mazmorras.
 *
 * COOLDOWN:
 * El contador empieza cuando el mob/boss muere o falta.
 * El tiempo NO se reinicia si un jugador entra.
 * Si el cooldown ya terminó pero hay jugadores dentro, espera a que la zona quede libre.
 * Ideal para bosses diarios, semanales o spawns raros.
 */
public enum RespawnMode {
    EMPTY,
    COOLDOWN;

    /**
     * Convierte texto de comando a RespawnMode.
     * Si el texto no coincide, usa EMPTY como modo seguro.
     */
    public static RespawnMode fromString(String value) {
        for (RespawnMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value)) {
                return mode;
            }
        }

        return EMPTY;
    }
}
