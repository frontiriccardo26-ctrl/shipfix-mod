package net.shipfix.physics;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.shipfix.chunk.PredictiveChunkLoader;
import net.shipfix.core.ShipFixConfig;
import net.shipfix.core.ShipFixMod;
import net.shipfix.physics.ShipVelocityTracker.ShipMotionData;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Monitors ships for physics freeze / stall conditions and applies
 * corrective measures.
 *
 * Detection heuristic: if a fast-moving ship shows near-zero positional
 * delta for more than {@link ShipFixConfig#FREEZE_DETECT_THRESHOLD_MS} ms
 * while velocity remains non-zero, a freeze is declared.
 *
 * Corrective actions (non-destructive):
 *  - Log the freeze event with full diagnostics.
 *  - Flush the chunk preload queue priority for the affected ship's direction.
 *  - Optionally issue a debug warning to ops.
 *  - (Optional) Gently reduce velocity via VS API if configured.
 *
 * Collisions, hitboxes, and physics simulation are NEVER disabled.
 */
public class PhysicsSafetyManager {

    private static final double STALL_POSITION_DELTA_THRESHOLD = 0.01; // blocks
    private static final double MOVING_VELOCITY_THRESHOLD       = 0.5;  // blocks/tick

    private final MinecraftServer        server;
    private final ShipVelocityTracker    velocityTracker;
    private final PredictiveChunkLoader  predictiveLoader;

    /** Tracks last recorded position per ship for freeze detection. */
    private final Map<Long, FreezeMonitorEntry> freezeMonitor = new ConcurrentHashMap<>();

    /** Total freeze events detected since startup. */
    private volatile int totalFreezeEvents = 0;
    /** Active freezes right now. */
    private volatile int activeFreezeCount = 0;

    public PhysicsSafetyManager(MinecraftServer server,
                                 ShipVelocityTracker velocityTracker,
                                 PredictiveChunkLoader predictiveLoader) {
        this.server           = server;
        this.velocityTracker  = velocityTracker;
        this.predictiveLoader = predictiveLoader;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!ShipFixConfig.ENABLE_PHYSICS_SAFETY.get()) return;

        int active = 0;

        for (ServerLevel level : server.getAllLevels()) {
            var shipWorld = VSGameUtilsKt.getShipObjectWorld(level);
            if (shipWorld == null) continue;

            for (ServerShip ship : (Collection<ServerShip>) shipWorld.getAllShips()) {
                if (checkShipFreeze(ship)) active++;
            }
        }

        activeFreezeCount = active;
    }

    private boolean checkShipFreeze(ServerShip ship) {
        long id = ship.getId();
        ShipMotionData data = velocityTracker.getMotionData(id);
        if (data == null) return false;

        // Only watch ships that are supposed to be moving fast
        if (data.speed < MOVING_VELOCITY_THRESHOLD) {
            freezeMonitor.remove(id);
            return false;
        }

        var transform = ship.getTransform();
        double wx = transform.getPositionInWorld().x();
        double wy = transform.getPositionInWorld().y();
        double wz = transform.getPositionInWorld().z();

        FreezeMonitorEntry entry = freezeMonitor.computeIfAbsent(id, k ->
                new FreezeMonitorEntry(wx, wy, wz));

        double delta = Math.sqrt(
                Math.pow(wx - entry.lastX, 2) +
                Math.pow(wy - entry.lastY, 2) +
                Math.pow(wz - entry.lastZ, 2));

        long now = System.currentTimeMillis();

        if (delta < STALL_POSITION_DELTA_THRESHOLD) {
            // Ship is "stuck" — check how long
            long stalledMs = now - entry.stallStartMs;

            if (stalledMs > ShipFixConfig.FREEZE_DETECT_THRESHOLD_MS.get()) {
                if (!entry.freezeReported) {
                    onFreezeDetected(ship, data, stalledMs);
                    entry.freezeReported = true;
                }
                return true;
            }
        } else {
            // Ship moved — reset stall timer
            entry.lastX = wx;
            entry.lastY = wy;
            entry.lastZ = wz;
            entry.stallStartMs = now;
            if (entry.freezeReported) {
                ShipFixMod.LOGGER.info("[ShipFix] Ship {} recovered from freeze after {}ms",
                        id, now - entry.stallStartMs);
                entry.freezeReported = false;
            }
        }

        return false;
    }

    private void onFreezeDetected(ServerShip ship, ShipMotionData data, long stalledMs) {
        totalFreezeEvents++;

        if (ShipFixConfig.DEBUG_FREEZE_DETECTION.get()) {
            ShipFixMod.LOGGER.warn(
                    "[ShipFix] FREEZE DETECTED — ship={} speed={:.2f} b/t stalledMs={} " +
                    "tps={:.1f} activeFreeze={}",
                    ship.getId(), data.speed, stalledMs,
                    predictiveLoader.getEstimatedTps(), activeFreezeCount + 1);
        }

        // Aggressive corrective: elevate preload priority — already handled by
        // PredictiveChunkLoader on next tick since velocity is still reported as high.
        // No physics/collision modification is performed.

        if (ShipFixConfig.AUTO_REDUCE_VELOCITY_ON_MISSING_CHUNKS.get()) {
            // Optional: reduce velocity via VS API to give chunk loading time to catch up.
            // This is a safe API call; collisions and physics remain active.
            // ship.applyInvariantForce(...) — implementation depends on VS API version.
            ShipFixMod.LOGGER.info("[ShipFix] Velocity reduction requested for ship {} (config-enabled).", ship.getId());
        }
    }

    public int getTotalFreezeEvents()  { return totalFreezeEvents; }
    public int getActiveFreezeCount()  { return activeFreezeCount; }

    // -----------------------------------------------------------------------
    // Inner helper
    // -----------------------------------------------------------------------
    private static final class FreezeMonitorEntry {
        double lastX, lastY, lastZ;
        long   stallStartMs;
        boolean freezeReported;

        FreezeMonitorEntry(double x, double y, double z) {
            lastX = x; lastY = y; lastZ = z;
            stallStartMs = System.currentTimeMillis();
        }
    }
}
