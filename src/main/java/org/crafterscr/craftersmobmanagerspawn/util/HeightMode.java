package org.crafterscr.craftersmobmanagerspawn.util;

public enum HeightMode {
    GROUND,
    FLOOR,
    EXACT,
    AIR;

    public static HeightMode fromString(String value) {
        for (HeightMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value)) {
                return mode;
            }
        }

        return GROUND;
    }
}