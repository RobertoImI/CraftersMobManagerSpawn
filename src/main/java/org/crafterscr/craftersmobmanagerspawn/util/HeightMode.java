package org.crafterscr.craftersmobmanagerspawn.util;

/**
 * Define la manera en la que el mod decide la altura final donde aparece un mob.
 *
 * GROUND:
 * Busca desde arriba hacia abajo hasta encontrar suelo sólido.
 * Ideal para zonas exteriores, bosques, campos, montañas, etc.
 *
 * FLOOR:
 * Busca cerca de la altura del centro de la zona.
 * Ideal para interiores, mazmorras, casas, castillos o pisos específicos.
 *
 * EXACT:
 * Usa exactamente la altura del centro de la zona.
 * Ideal para bosses o puntos muy controlados.
 *
 * AIR:
 * Busca un espacio vacío en el aire dentro del rango Y configurado.
 * Ideal para mobs voladores.
 */
public enum HeightMode {
    GROUND,
    FLOOR,
    EXACT,
    AIR;

    /**
     * Convierte el texto escrito en el comando a un HeightMode válido.
     * Si el admin escribe algo incorrecto, se usa GROUND como modo seguro.
     */
    public static HeightMode fromString(String value) {
        for (HeightMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value)) {
                return mode;
            }
        }

        return GROUND;
    }
}
