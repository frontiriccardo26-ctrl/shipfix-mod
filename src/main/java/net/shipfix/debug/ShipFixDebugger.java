package net.shipfix.debug;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.shipfix.chunk.AsyncChunkPreloader;
import net.shipfix.chunk.ChunkPriorityManager;
import net.shipfix.chunk.PredictiveChunkLoader;
import net.shipfix.core.ShipFixConfig;
import net.shipfix.core.ShipFixMod;
import net.shipfix.physics.ShipVelocityTracker;
import net.shipfix.physics.ShipVelocityTracker.ShipMotionData;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import static net.minecraft.commands.Commands.literal;

import java.util.Collection;

/**
 * Debug subsystem.
 *
 * Provides:
 *  - Periodic console log with TPS, queue size, preload radius, freeze count, etc.
 *  - /shipfix debug command for in-game diagnostics.
 *  - /shipfix ships command listing all tracked ships.
 */
public class ShipFixDebugger {

    private final ShipVelocityTracker   velocityTracker;
    private final PredictiveChunkLoader predictiveChunkLoader;
    private final ChunkPriorityManager  chunkPriorityManager;
    private final AsyncChunkPreloader   asyncChunkPreloader;

    private int tickCounter = 0;

    // Rolling chunk load time estimator
    private long totalLoadRequests = 0;
    private long totalLoadTimeMs   = 0;
    private long lastLoadStart     = 0;

    public ShipFixDebugger(ShipVelocityTracker velocityTracker,
                            PredictiveChunkLoader predictiveChunkLoader,
                            ChunkPriorityManager chunkPriorityManager,
                            AsyncChunkPreloader asyncChunkPreloader) {
        this.velocityTracker       = velocityTracker;
        this.predictiveChunkLoader = predictiveChunkLoader;
        this.chunkPriorityManager  = chunkPriorityManager;
        this.asyncChunkPreloader   = asyncChunkPreloader;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!ShipFixConfig.DEBUG_LOG_ENABLED.get()) return;

        tickCounter++;
        int interval = ShipFixConfig.DEBUG_LOG_INTERVAL_TICKS.get();

        if (tickCounter >= interval) {
            tickCounter = 0;
            logDebugReport();
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = literal("shipfix")
                .requires(src -> src.hasPermission(2))
                .then(literal("debug").executes(ctx -> {
                    logDebugReport();
                    sendDebugToSource(ctx.getSource());
                    return 1;
                }))
                .then(literal("ships").executes(ctx -> {
                    listShips(ctx.getSource());
                    return 1;
                }))
                .then(literal("queue").executes(ctx -> {
                    ctx.getSource().sendSuccess(() ->
                            Component.literal("[ShipFix] Queue size: " + asyncChunkPreloader.getQueueSize() +
                                    " | Active tickets: " + chunkPriorityManager.getActiveTicketCount()), false);
                    return 1;
                }))
                .then(literal("tps").executes(ctx -> {
                    ctx.getSource().sendSuccess(() ->
                            Component.literal(String.format("[ShipFix] Estimated TPS: %.2f",
                                    predictiveChunkLoader.getEstimatedTps())), false);
                    return 1;
                }));

        event.getDispatcher().register(root);
    }

    private void logDebugReport() {
        double tps = predictiveChunkLoader.getEstimatedTps();
        int queueSize = asyncChunkPreloader.getQueueSize();
        int activeTickets = chunkPriorityManager.getActiveTicketCount();
        long totalQueued = asyncChunkPreloader.getTotalQueued();
        long totalProcessed = asyncChunkPreloader.getTotalProcessed();

        Collection<ShipMotionData> ships = velocityTracker.getAllMotionData();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[ShipFix Debug] TPS=%.2f | QueueSize=%d | ActiveTickets=%d | TotalQueued=%d | TotalProcessed=%d | Ships=%d",
                tps, queueSize, activeTickets, totalQueued, totalProcessed, ships.size()));

        if (ShipFixConfig.DEBUG_SHOW_CHUNK_QUEUE.get()) {
            sb.append("\n");
            for (ShipMotionData data : ships) {
                sb.append(String.format("  Ship[%d] speed=%.3f b/t (%.1f b/s) dir=(%.2f,%.2f,%.2f) angVel=(%.3f,%.3f,%.3f)\n",
                        data.shipId,
                        data.speed,
                        data.speedBlocksPerSecond(),
                        data.direction.x, data.direction.y, data.direction.z,
                        data.angularVelocity.x, data.angularVelocity.y, data.angularVelocity.z));
            }
        }

        ShipFixMod.LOGGER.info(sb.toString());
    }

    private void sendDebugToSource(CommandSourceStack source) {
        double tps = predictiveChunkLoader.getEstimatedTps();
        int queueSize = asyncChunkPreloader.getQueueSize();
        int activeTickets = chunkPriorityManager.getActiveTicketCount();

        source.sendSuccess(() -> Component.literal(String.format(
                "§b[ShipFix]§r TPS=§e%.2f§r | Queue=§e%d§r | Tickets=§e%d§r | Ships=§e%d",
                tps, queueSize, activeTickets, velocityTracker.getAllMotionData().size())), false);
    }

    private void listShips(CommandSourceStack source) {
        Collection<ShipMotionData> ships = velocityTracker.getAllMotionData();
        if (ships.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§b[ShipFix]§r No tracked ships."), false);
            return;
        }
        for (ShipMotionData data : ships) {
            source.sendSuccess(() -> Component.literal(String.format(
                    "§b[ShipFix]§r Ship §e%d§r — speed=§a%.2f§r b/t  dir=(%.2f, %.2f, %.2f)",
                    data.shipId, data.speed,
                    data.direction.x, data.direction.y, data.direction.z)), false);
        }
    }
}
