package net.shipfix.core;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.shipfix.chunk.AsyncChunkPreloader;
import net.shipfix.chunk.ChunkPriorityManager;
import net.shipfix.chunk.PredictiveChunkLoader;
import net.shipfix.debug.ShipFixDebugger;
import net.shipfix.physics.PhysicsSafetyManager;
import net.shipfix.physics.ShipMovementPredictor;
import net.shipfix.physics.ShipVelocityTracker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(ShipFixMod.MOD_ID)
public class ShipFixMod {

    public static final String MOD_ID = "shipfix";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    // Singleton instances of core systems
    private static ShipVelocityTracker velocityTracker;
    private static ShipMovementPredictor movementPredictor;
    private static PredictiveChunkLoader predictiveChunkLoader;
    private static ChunkPriorityManager chunkPriorityManager;
    private static AsyncChunkPreloader asyncChunkPreloader;
    private static PhysicsSafetyManager physicsSafetyManager;
    private static ShipFixDebugger debugger;

    public ShipFixMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        ShipFixConfig.register(modEventBus);
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("[ShipFix] Mod initialized — Predictive Chunk Loading for VS ships active.");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("[ShipFix] Server starting — initializing subsystems...");

        velocityTracker       = new ShipVelocityTracker();
        movementPredictor     = new ShipMovementPredictor(velocityTracker);
        chunkPriorityManager  = new ChunkPriorityManager(event.getServer());
        asyncChunkPreloader   = new AsyncChunkPreloader(event.getServer(), chunkPriorityManager);
        predictiveChunkLoader = new PredictiveChunkLoader(
                velocityTracker, movementPredictor, chunkPriorityManager, asyncChunkPreloader);
        physicsSafetyManager  = new PhysicsSafetyManager(event.getServer(), velocityTracker, predictiveChunkLoader);
        debugger              = new ShipFixDebugger(velocityTracker, predictiveChunkLoader,
                chunkPriorityManager, asyncChunkPreloader);

        MinecraftForge.EVENT_BUS.register(velocityTracker);
        MinecraftForge.EVENT_BUS.register(predictiveChunkLoader);
        MinecraftForge.EVENT_BUS.register(physicsSafetyManager);
        MinecraftForge.EVENT_BUS.register(debugger);

        asyncChunkPreloader.start();
        LOGGER.info("[ShipFix] All subsystems online.");
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("[ShipFix] Server stopping — shutting down subsystems...");
        if (asyncChunkPreloader != null) asyncChunkPreloader.shutdown();
        if (chunkPriorityManager != null) chunkPriorityManager.shutdown();
        LOGGER.info("[ShipFix] Shutdown complete.");
    }

    // Static accessors
    public static ShipVelocityTracker getVelocityTracker()           { return velocityTracker; }
    public static ShipMovementPredictor getMovementPredictor()        { return movementPredictor; }
    public static PredictiveChunkLoader getPredictiveChunkLoader()    { return predictiveChunkLoader; }
    public static ChunkPriorityManager getChunkPriorityManager()      { return chunkPriorityManager; }
    public static AsyncChunkPreloader getAsyncChunkPreloader()        { return asyncChunkPreloader; }
    public static PhysicsSafetyManager getPhysicsSafetyManager()     { return physicsSafetyManager; }
    public static ShipFixDebugger getDebugger()                       { return debugger; }
}
