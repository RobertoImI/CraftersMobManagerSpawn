package org.crafterscr.craftersmobmanagerspawn.logic;

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

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        MMSpawnManager.dropConfiguredRewards(event.getEntity());
    }
}
