package com.vws.aeronauticsshiphull;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.vws.aeronauticsshiphull.api.ShipCadence;
import com.vws.aeronauticsshiphull.command.ShipHullCommand;
import com.vws.aeronauticsshiphull.config.HullConfig;
import com.vws.aeronauticsshiphull.hull.ShipHullManager;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Aeronautics Ship Hull (Phase 3).
 * Server-side: each Sable ServerSubLevel is tracked as one rigid body + sparse voxel hull
 * + interactive cells. Unpiloted ships divert out of Rapier and integrate at 5–10 Hz;
 * full Rapier only for piloted / imminent collision / short rebuild after explosion.
 * Pilot never promotes to per-block physics. Separate jar from create_factory_graph.
 */
@Mod(AeronauticsShipHull.MOD_ID)
public class AeronauticsShipHull {
    public static final String MOD_ID = "aeronautics_ship_hull";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AeronauticsShipHull(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, HullConfig.SPEC);
        modEventBus.addListener(this::commonSetup);
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onLevelTickPost);
        NeoForge.EVENT_BUS.addListener(this::onLevelUnload);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("{} phase-3 ship hull loaded (Aeronautics 1.3.2 / Sable 2.0.5 mixins; FactoryCadence bridge={})",
                MOD_ID, ShipCadence.factoryBridgeActive());
    }

    private void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("{} ready; hull mode {}", MOD_ID, HullConfig.enabled() ? "ENABLED" : "disabled");
    }

    private void onServerStopping(ServerStoppingEvent event) {
        ShipHullManager.clearAll();
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        ShipHullCommand.register(event.getDispatcher());
    }

    private void onLevelTickPost(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel sl) {
            ShipHullManager.get(sl).endTick();
        }
    }

    private void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel sl) {
            ShipHullManager.remove(sl);
        }
    }
}
