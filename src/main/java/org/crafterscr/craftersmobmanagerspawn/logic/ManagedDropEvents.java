package org.crafterscr.craftersmobmanagerspawn.logic;

import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * Detecta muertes reales de entidades vanilla/modded.
 *
 * Las eliminaciones administrativas mediante Entity#discard no disparan este evento,
 * por lo que /mmspawn clear o borrar una zona no generan recompensas.
 */
public class ManagedDropEvents {

    private ManagedDropEvents() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.isCanceled()) {
            return;
        }

        // Los Pokémon sólo se premian después de BATTLE_VICTORY, nunca aquí.
        // Así una derrota en combate no crea ítems sin dueño en el suelo.
        String typeId = BuiltInRegistries.ENTITY_TYPE.getKey(event.getEntity().getType()).toString();
        if (!"cobblemon:pokemon".equals(typeId)) {
            MMSpawnManager.dropConfiguredRewards(event.getEntity());
        }
    }
}
