package org.crafterscr.craftersmobmanagerspawn.logic;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;

/**
 * Detecta muertes reales de mobs y protege los premios sobrantes de Pokémon.
 * No interfiere con la recogida de ítems ordinarios.
 */
public class ManagedDropEvents {

    private ManagedDropEvents() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.isCanceled()) {
            return;
        }

        // Los Pokémon sólo se premian después de BATTLE_VICTORY.
        String typeId = BuiltInRegistries.ENTITY_TYPE.getKey(event.getEntity().getType()).toString();
        if (!"cobblemon:pokemon".equals(typeId)) {
            MMSpawnManager.dropConfiguredRewards(event.getEntity());
        }
    }

    /**
     * En creativo el intento de recoger una entidad de ítem puede eliminarla
     * incluso con el inventario lleno. Rechazamos EXCLUSIVAMENTE esas
     * recogidas sin espacio para los premios etiquetados por MMSpawn.
     *
     * No restringimos por UUID: cualquier jugador con espacio puede recogerlos.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRewardItemPickup(ItemEntityPickupEvent.Pre event) {
        boolean reward = event.getItemEntity().getTags().stream()
                .anyMatch(tag -> tag.startsWith("mmspawn_reward_for_"));
        if (!reward) {
            return;
        }

        ItemStack item = event.getItemEntity().getItem();
        if (!item.isEmpty() && !hasMainInventoryRoom(event.getPlayer(), item)) {
            event.setCanPickup(TriState.FALSE);
        }
    }

    private static boolean hasMainInventoryRoom(Player player, ItemStack item) {
        Inventory inventory = player.getInventory();
        for (ItemStack existing : inventory.items) {
            if (existing.isEmpty()) {
                return true;
            }

            if (ItemStack.isSameItemSameComponents(existing, item)
                    && existing.getCount() < existing.getMaxStackSize()) {
                return true;
            }
        }

        return false;
    }
}
