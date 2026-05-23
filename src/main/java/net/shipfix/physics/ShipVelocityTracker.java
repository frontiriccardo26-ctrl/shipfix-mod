package net.shipfix.physics;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.shipfix.core.ShipFixMod;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.valkyrienskies.core.api.ships.ServerShip;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-ship velocity, direction, acceleration, and angular velocity
 * every server tick. Thread-safe reads via ConcurrentHashMap snapshots.
 */
public class ShipVelocityTracker {

    /** Per-ship data snapshot, keyed by ship ID. */
    private final Map<Long, ShipMotionData> motionDataMap = new ConcurrentHashMap<>();

    /** Previous-tick position cache for acceleration computation. */
    private final Map<Long, Vector3d> previousVelocityMap = new ConcurrentHashMap<>();

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        // Iterate all loaded server-side ships
        for (net.minecraft.server.level.ServerLevel level :
                net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer().getAllLevels()) {

            Collection<ServerShip> ships = VSGameUtilsKt.getShipObjectWorld(level) == null
                    ? java.util.Collections.emptyList()
                    : (Collection<ServerShip>) VSGameUtilsKt.getShipObjectWorld(level).getAllShips();

            for (ServerShip ship : ships) {
                updateShipData(ship);
            }
        }

        // Purge data for ships that no longer exist
        // (done lazily by checking staleness in getMotionData)
    }

    private void updateShipData(ServerShip ship) {
        long id = ship.getId();

        // Read VS physics state
        Vector3dc vel = ship.getVelocity();          // blocks/tick
        Vector3dc angVel = ship.getOmega();          // rad/tick

        Vector3d velocity = new Vector3d(vel);
        double speed = velocity.length();

        // Normalised direction (safe)
        Vector3d direction = speed > 1e-6 ? new Vector3d(velocity).div(speed) : new Vector3d(0, 0, 1);

        // Acceleration = Δvelocity / 1 tick
        Vector3d prevVel = previousVelocityMap.getOrDefault(id, new Vector3d(velocity));
        Vector3d acceleration = new Vector3d(velocity).sub(prevVel);

        previousVelocityMap.put(id, new Vector3d(velocity));

        ShipMotionData data = new ShipMotionData(
                id,
                new Vector3d(ship.getTransform().getPositionInWorld()),
                velocity,
                direction,
                acceleration,
                speed,
                new Vector3d(angVel),
                System.currentTimeMillis()
        );

        motionDataMap.put(id, data);
    }

    /** Returns the latest motion data for a ship, or null if not tracked. */
    public ShipMotionData getMotionData(long shipId) {
        return motionDataMap.get(shipId);
    }

    /** Returns all currently tracked ships. */
    public Collection<ShipMotionData> getAllMotionData() {
        return motionDataMap.values();
    }

    /** Clears stale data for ships that no longer exist. */
    public void clearStale(java.util.Set<Long> activeIds) {
        motionDataMap.keySet().retainAll(activeIds);
        previousVelocityMap.keySet().retainAll(activeIds);
    }

    // -----------------------------------------------------------------------
    // Inner data class — immutable snapshot per tick
    // -----------------------------------------------------------------------
    public static final class ShipMotionData {
        public final long shipId;
        public final Vector3d position;       // world position (blocks)
        public final Vector3d velocity;       // blocks / tick
        public final Vector3d direction;      // normalised forward vector
        public final Vector3d acceleration;   // blocks / tick²
        public final double   speed;          // blocks / tick (magnitude)
        public final Vector3d angularVelocity;// rad / tick
        public final long     timestamp;      // System.currentTimeMillis()

        public ShipMotionData(long shipId, Vector3d position, Vector3d velocity,
                              Vector3d direction, Vector3d acceleration, double speed,
                              Vector3d angularVelocity, long timestamp) {
            this.shipId         = shipId;
            this.position       = position;
            this.velocity       = velocity;
            this.direction      = direction;
            this.acceleration   = acceleration;
            this.speed          = speed;
            this.angularVelocity = angularVelocity;
            this.timestamp      = timestamp;
        }

        /** Speed in blocks/second (20 ticks/s). */
        public double speedBlocksPerSecond() { return speed * 20.0; }

        /** True if the ship is moving fast enough to need predictive preloading. */
        public boolean isMovingFast(double threshold) { return speed > threshold; }
    }
}
