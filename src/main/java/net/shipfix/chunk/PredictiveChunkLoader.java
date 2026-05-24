package net.shipfix.chunk;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.shipfix.core.ShipFixConfig;
import net.shipfix.physics.ShipMovementPredictor;
import net.shipfix.physics.ShipMovementPredictor.ConeZone;
import net.shipfix.physics.ShipVelocityTracker;
import net.shipfix.physics.ShipVelocityTracker.ShipMotionData;
import org.joml.Vector3d;

import java.lang.reflect.Method;
import java.util.*;

public class PredictiveChunkLoader {

    private static final double BLOCKS_PER_CHUNK = 16.0;

    private final ShipVelocityTracker    velocityTracker;
    private final ShipMovementPredictor  movementPredictor;
    private final ChunkPriorityManager   priorityManager;
    private final AsyncChunkPreloader    asyncPreloader;

    private final long[] tickTimestamps = new long[20];
    private int    tickIndex    = 0;
    private double estimatedTps = 20.0;

    private final Set<Long> scheduledThisTick = new HashSet<>(256);

    // Reflection cache
    private Method getShipWorldMethod;
    private Method getAllShipsMethod;
    private Method getIdMethod;
    private boolean reflectionReady = false;

    public PredictiveChunkLoader(ShipVelocityTracker velocityTracker,
                                  ShipMovementPredictor movementPredictor,
                                  ChunkPriorityManager priorityManager,
                                  AsyncChunkPreloader asyncPreloader) {
        this.velocityTracker   = velocityTracker;
        this.movementPredictor = movementPredictor;
        this.priorityManager   = priorityManager;
        this.asyncPreloader    = asyncPreloader;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> vsUtils     = Class.forName("org.valkyrienskies.mod.common.VSGameUtilsKt");
            Class<?> shipWorld   = Class.forName("org.valkyrienskies.core.api.world.ShipWorld");
            Class<?> shipBase    = Class.forName("org.valkyrienskies.core.api.ships.Ship");
            getShipWorldMethod   = vsUtils.getMethod("getShipObjectWorld", ServerLevel.class);
            getAllShipsMethod     = shipWorld.getMethod("getAllShips");
            getIdMethod          = shipBase.getMethod("getId");
            reflectionReady      = true;
        } catch (Exception e) {
            net.shipfix.core.ShipFixMod.LOGGER.warn("[ShipFix] PredictiveChunkLoader reflection failed: {}", e.getMessage());
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;

        updateTpsEstimate();
        scheduledThisTick.clear();

        Collection<ShipMotionData> allShips = velocityTracker.getAllMotionData();

        for (ShipMotionData data : allShips) {
            if (data.speed < 0.3) continue;
            ServerLevel level = findLevelForShip(data.shipId);
            if (level == null) continue;
            processShip(data, level);
        }

        priorityManager.tickDecay();
        asyncPreloader.drainOnMainThread();
    }

    private void processShip(ShipMotionData data, ServerLevel level) {
        int predictionTicks = ShipFixConfig.PREDICTION_TICKS.get();
        int sampleInterval  = Math.max(1, predictionTicks / 10);

        List<Vector3d> trajectory = movementPredictor.predictTrajectory(data, predictionTicks, sampleInterval);
        trajectory.add(0, new Vector3d(data.position));

        int radius     = movementPredictor.computePreloadRadius(data, estimatedTps);
        double halfAngle = ShipFixConfig.CONE_HALF_ANGLE_DEGREES.get();

        for (Vector3d waypoint : trajectory) {
            submitChunkCone(level, waypoint, data.direction, radius, halfAngle);
        }
    }

    private void submitChunkCone(ServerLevel level, Vector3d centre, Vector3d direction,
                                  int radius, double halfAngleDeg) {
        int centreChunkX = (int) Math.floor(centre.x / BLOCKS_PER_CHUNK);
        int centreChunkZ = (int) Math.floor(centre.z / BLOCKS_PER_CHUNK);

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;

                int cx = centreChunkX + dx;
                int cz = centreChunkZ + dz;
                long key = ChunkPos.asLong(cx, cz);

                if (!scheduledThisTick.add(key)) continue;

                ConeZone zone = ShipMovementPredictor.classifyChunk(centre, direction, cx, cz, halfAngleDeg);
                asyncPreloader.enqueue(level, new ChunkPos(cx, cz), zone);
            }
        }
    }

    private ServerLevel findLevelForShip(long shipId) {
        if (!reflectionReady) return null;
        try {
            for (ServerLevel level : ServerLifecycleHooks.getCurrentServer().getAllLevels()) {
                Object shipWorld = getShipWorldMethod.invoke(null, level);
                if (shipWorld == null) continue;
                Iterable<?> ships = (Iterable<?>) getAllShipsMethod.invoke(shipWorld);
                for (Object ship : ships) {
                    if ((Long) getIdMethod.invoke(ship) == shipId) return level;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void updateTpsEstimate() {
        long now  = System.currentTimeMillis();
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
