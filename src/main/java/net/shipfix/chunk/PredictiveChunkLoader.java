package net.shipfix.chunk;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.shipfix.core.ShipFixConfig;
import net.shipfix.core.ShipFixMod;
import net.shipfix.physics.ShipMovementPredictor;
import net.shipfix.physics.ShipMovementPredictor.ConeZone;
import net.shipfix.physics.ShipVelocityTracker;
import net.shipfix.physics.ShipVelocityTracker.ShipMotionData;
import org.joml.Vector3d;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import java.util.*;

/**
 * Core orchestrator: every server tick it
 *  1. reads all ship motion data,
 *  2. predicts their trajectories,
 *  3. generates the chunk cone footprint,
 *  4. submits preload requests with correct priorities,
 *  5. decays expired tickets,
 *  6. drains the async queue.
 *
 * Designed to run entirely on the main server tick thread with minimal
 * allocations per tick.
 */
public class PredictiveChunkLoader {

    private static final double BLOCKS_PER_CHUNK = 16.0;

    private final ShipVelocityTracker    velocityTracker;
    private final ShipMovementPredictor  movementPredictor;
    private final ChunkPriorityManager   priorityManager;
    private final AsyncChunkPreloader    asyncPreloader;

    /** Rolling average server TPS estimator. */
    private final long[]  tickTimestamps  = new long[20];
    private       int     tickIndex       = 0;
    private       double  estimatedTps    = 20.0;

    /** Set of chunk positions scheduled this tick (avoids redundant submits). */
    private final Set<Long> scheduledThisTick = new HashSet<>(256);

    public PredictiveChunkLoader(ShipVelocityTracker velocityTracker,
                                  ShipMovementPredictor movementPredictor,
                                  ChunkPriorityManager priorityManager,
                                  AsyncChunkPreloader asyncPreloader) {
        this.velocityTracker   = velocityTracker;
        this.movementPredictor = movementPredictor;
        this.priorityManager   = priorityManager;
        this.asyncPreloader    = asyncPreloader;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;

        updateTpsEstimate();
        scheduledThisTick.clear();

        Collection<ShipMotionData> allShips = velocityTracker.getAllMotionData();

        for (ShipMotionData data : allShips) {
            // Only run predictive loading for ships that are actually moving
            double speedThreshold = 0.3; // blocks/tick
            if (data.speed < speedThreshold) continue;

            ServerLevel level = findLevelForShip(data.shipId);
            if (level == null) continue;

            processShip(data, level);
        }

        // Decay expired tickets
        priorityManager.tickDecay();

        // Drain the async queue on this main thread (rate-limited)
        asyncPreloader.drainOnMainThread();
    }

    private void processShip(ShipMotionData data, ServerLevel level) {
        int predictionTicks = ShipFixConfig.PREDICTION_TICKS.get();
        int sampleInterval  = Math.max(1, predictionTicks / 10);

        // Get trajectory waypoints
        List<Vector3d> trajectory = movementPredictor.predictTrajectory(data, predictionTicks, sampleInterval);

        // Also always include current position
        trajectory.add(0, new Vector3d(data.position));

        // Preload radius scales with speed and TPS
        int radius = movementPredictor.computePreloadRadius(data, estimatedTps);
        double halfAngle = ShipFixConfig.CONE_HALF_ANGLE_DEGREES.get();

        for (Vector3d waypoint : trajectory) {
            submitChunkCone(level, waypoint, data.direction, radius, halfAngle);
        }
    }

    /**
     * Generates and submits all chunks within the cone footprint centred on
     * {@code centre} facing {@code direction} with the given {@code radius}.
     */
    private void submitChunkCone(ServerLevel level, Vector3d centre, Vector3d direction,
                                  int radius, double halfAngleDeg) {
        int centreChunkX = (int) Math.floor(centre.x / BLOCKS_PER_CHUNK);
        int centreChunkZ = (int) Math.floor(centre.z / BLOCKS_PER_CHUNK);

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue; // circular bound

                int cx = centreChunkX + dx;
                int cz = centreChunkZ + dz;
                long key = ChunkPos.asLong(cx, cz);

                if (!scheduledThisTick.add(key)) continue; // already scheduled this tick

                ConeZone zone = ShipMovementPredictor.classifyChunk(centre, direction, cx, cz, halfAngleDeg);
                ChunkPos pos  = new ChunkPos(cx, cz);

                asyncPreloader.enqueue(level, pos, zone);
            }
        }
    }

    /** Finds the ServerLevel containing the given ship ID. */
    private ServerLevel findLevelForShip(long shipId) {
        for (ServerLevel level : ServerLifecycleHooks.getCurrentServer().getAllLevels()) {
            var shipWorld = VSGameUtilsKt.getShipObjectWorld(level);
            if (shipWorld != null && shipWorld.getAllShips().stream()
                    .anyMatch(s -> s.getId() == shipId)) {
                return level;
            }
        }
        return null;
    }

    /** Updates the rolling TPS estimate. */
    private void updateTpsEstimate() {
        long now = System.currentTimeMillis();
        long prev = tickTimestamps[tickIndex];
        tickTimestamps[tickIndex] = now;
        tickIndex = (tickIndex + 1) % tickTimestamps.length;

        if (prev > 0 && now > prev) {
            double windowMs = now - prev;
            estimatedTps = Math.min(20.0, (tickTimestamps.length * 1000.0) / windowMs);
        }
    }

    public double getEstimatedTps() { return estimatedTps; }
}
