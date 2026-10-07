package org.crafterscr.craftersmobmanagerspawn;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.crafterscr.craftersmobmanagerspawn.command.MMSpawnCommands;
import org.crafterscr.craftersmobmanagerspawn.logic.MMSpawnManager;
import org.crafterscr.craftersmobmanagerspawn.logic.ManagedDropEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(CraftersMobManagerSpawn.MODID)
public class CraftersMobManagerSpawn {

    public static final String MODID = "craftersmobmanagerspawn";
    public static final Logger LOGGER = LoggerFactory.getLogger("Crafters Mob Manager Spawn");

    public CraftersMobManagerSpawn(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.register(MMSpawnCommands.class);
        NeoForge.EVENT_BUS.register(MMSpawnManager.class);
        NeoForge.EVENT_BUS.register(ManagedDropEvents.class);

        LOGGER.info("Crafters Mob Manager Spawn cargado correctamente.");
    }
}