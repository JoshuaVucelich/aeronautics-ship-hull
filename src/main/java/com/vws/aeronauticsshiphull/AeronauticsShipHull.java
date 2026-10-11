package com.vws.aeronauticsshiphull;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.vws.aeronauticsshiphull.api.ShipCadence;
import com.vws.aeronauticsshiphull.command.ShipHullCommand;
import com.vws.aeronauticsshiphull.config.HullConfig;
import com.vws.aeronauticsshiphull.hull.ShipHullManager;
import com.vws.aeronauticsshiphull.mixin.MixinProbes;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
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
        NeoForge.EVENT_BUS.addListener(this::onPlayerLogin);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onLevelTickPost);
        NeoForge.EVENT_BUS.addListener(this::onLevelUnload);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        ShipCadence.registerFactoryLocator();
        LOGGER.info("{} phase-4 ship hull loaded (Aeronautics 1.3.2 / Sable 2.0.5 mixins; FactoryCadence {})",
                MOD_ID, ShipCadence.bridgeInfo());
    }

    private static final String[] CRITICAL_MIXINS = {
            "SubLevelPhysicsSystemMixin",
            "SubLevelPhysicsStepMixin",
            "ServerSubLevelMixin",
            "RapierPipelineHooksMixin",
            "RapierConstraintLinkMixin",
            "RapierConstraintRemoveMixin"
    };

    private void onServerStarting(ServerStartingEvent event) {
        var missing = MixinProbes.missing(CRITICAL_MIXINS);
        if (!missing.isEmpty()) {
            ShipHullManager.safeModeOverride = Boolean.TRUE;
            LOGGER.error("[shiphull] anomaly: critical mixin targets missing {}; safe-mode is on. Parking stays off.", missing);
        }
        LOGGER.info("{} ready; hull mode {} safeMode={} (parked state is not saved; ships register in FULL)",
                MOD_ID, HullConfig.enabled() ? "ENABLED" : "disabled", ShipHullManager.safeMode());
    }

    private void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel sl)) return;
        try {
            ShipHullManager.get(sl).wakeForPlayer(player);
        } catch (Throwable t) {
            ShipHullManager.anomaly("player login wake failed: " + t);
        }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        ShipHullManager.clearAll();
        ShipCadence.reset();
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
