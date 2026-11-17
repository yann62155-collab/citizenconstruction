package fr.citizen.worldbuilder;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

@Mod(CitizenWorldBuilder.MODID)
public final class CitizenWorldBuilder {
    public static final String MODID = "citizenworldbuilder";
    public static final Logger LOGGER = LogUtils.getLogger();
    public CitizenWorldBuilder(IEventBus modBus) {
        NeoForge.EVENT_BUS.register(this);
        LOGGER.info("Citizen World Builder chargé");
    }
    @SubscribeEvent public void registerCommands(RegisterCommandsEvent event) {
        CitizenCommands.register(event.getDispatcher());
    }
    @SubscribeEvent public void onServerTick(ServerTickEvent.Post event) {
        BuildQueue.tick();
    }
}
