package net.shipfix.physics;

import net.shipfix.core.ShipFixConfig;
import net.shipfix.physics.ShipVelocityTracker.ShipMotionData;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Predicts the future position, trajectory, and chunk footprint of a ship
 * based on current velocity, direction, and acceleration.
 *
 * All prediction is performed with simple kinematic integration augmented by
 * a steering-curvature estimate derived from angular velocity.
 */
public class ShipMovementPredictor {

    private static final double BLOCKS_PER_CHUNK = 16.0;

    private final ShipVelocityTracker velocityTracker;

    public ShipMovementPredictor(ShipVelocityTracker velocityTracker) {
        this.velocityTracker = velocityTracker;
    }

    /**
     * Predicts the ship centre position N ticks in the future.
     *
     * Uses constant-acceleration Euler integration with angular-velocity
     * curve estimation (approximated as circular arc in the XZ plane).
     */
    public Vector3d predictPosition(ShipMotionData data, int ticks) {
        // Clamp to config max
        ticks = Math.min(ticks, ShipFixConfig.PREDICTION_TICKS.get());

        Vector3d pos = new Vector3d(data.position);
        Vector3d vel = new Vector3d(data.velocity);
        Vector3d acc = new Vector3d(data.acceleration);

        // Max acceleration contribution capped to avoid divergence
        double accMag = acc.length();
        if (accMag > 2.0) acc.div(accMag / 2.0);

        // Angular velocity around Y (yaw) causes trajectory to curve
        double yawRate = data.angularVelocity.y; // rad/tick

        for (int t = 0; t < ticks; t++) {
            // Rotate velocity by yaw rate
            if (Math.abs(yawRate) > 1e-4) {
                double cosY = Math.cos(yawRate);
                double sinY = Math.sin(yawRate);
                double vx = vel.x * cosY - vel.z * sinY;
                double vz = vel.x * sinY + vel.z * cosY;
                vel.x = vx;
                vel.z = vz;
            }
            vel.add(acc);
            pos.add(vel);
        }
        return pos;
    }

    /**
     * Returns a list of future world positions sampled every {@code sampleInterval} ticks
     * up to {@code maxTicks} ticks ahead. Used to build the trajectory corridor.
     */
    public List<Vector3d> predictTrajectory(ShipMotionData data, int maxTicks, int sampleInterval) {
        List<Vector3d> trajectory = new ArrayList<>();
        Vector3d pos = new Vector3d(data.position);
        Vector3d vel = new Vector3d(data.velocity);
        Vector3d acc = new Vector3d(data.acceleration);

        double accMag = acc.length();
        if (accMag > 2.0) acc.div(accMag / 2.0);

        double yawRate = data.angularVelocity.y;
        maxTicks = Math.min(maxTicks, ShipFixConfig.PREDICTION_TICKS.get());

        for (int t = 1; t <= maxTicks; t++) {
            if (Math.abs(yawRate) > 1e-4) {
                double cosY = Math.cos(yawRate);
                double sinY = Math.sin(yawRate);
                double vx = vel.x * cosY - vel.z * sinY;
                double vz = vel.x * sinY + vel.z * cosY;
                vel.x = vx;
                vel.z = vz;
            }
            vel.add(acc);
            pos.add(vel);

            if (t % sampleInterval == 0) {
                trajectory.add(new Vector3d(pos));
            }
        }
        return trajectory;
    }

    /**
     * Computes the dynamic preload radius (in chunks) for a ship, scaled by:
     * - ship speed
     * - current server TPS
     * - config scale factor
     */
    public int computePreloadRadius(ShipMotionData data, double currentTps) {
        int base = ShipFixConfig.BASE_PRELOAD_RADIUS.get();
        int max  = ShipFixConfig.MAX_PRELOAD_RADIUS.get();
        int min  = ShipFixConfig.MIN_PRELOAD_RADIUS.get();

        // Speed in blocks/tick → scale to chunk distances
        double speedChunksPerTick = data.speed / BLOCKS_PER_CHUNK;
        double scaleFactor = ShipFixConfig.VELOCITY_SCALE_FACTOR.get();

        // TPS factor: below 18 TPS increase radius to compensate slower loading
        double tpsFactor = 1.0;
        if (ShipFixConfig.ENABLE_TPS_ADAPTATION.get()) {
            double low  = ShipFixConfig.TPS_LOW_THRESHOLD.get();
            double crit = ShipFixConfig.TPS_CRITICAL_THRESHOLD.get();
            if (currentTps < crit) {
                tpsFactor = 2.5;
            } else if (currentTps < low) {
                tpsFactor = 1.0 + (low - currentTps) / (low - crit) * 1.5;
            }
        }

        int radius = (int) Math.round(base + speedChunksPerTick * scaleFactor * tpsFactor);
        return Math.max(min, Math.min(max, radius));
    }

    /**
     * Determines whether a candidate chunk position falls inside the forward
     * preload cone centred on the ship's velocity direction.
     *
     * @param shipPos     ship centre (world blocks)
     * @param direction   normalised forward vector
     * @param chunkCX     candidate chunk X coordinate
     * @param chunkCZ     candidate chunk Z coordinate
     * @param halfAngleDeg half-angle of the cone in degrees
     */
    public static ConeZone classifyChunk(Vector3d shipPos, Vector3d direction,
                                          int chunkCX, int chunkCZ, double halfAngleDeg) {
        // Centre of candidate chunk in world space
        double cx = chunkCX * BLOCKS_PER_CHUNK + 8.0;
        double cz = chunkCZ * BLOCKS_PER_CHUNK + 8.0;

        double dx = cx - shipPos.x;
        double dz = cz - shipPos.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 1e-6) return ConeZone.FRONT;

        // Dot product with forward direction (XZ only)
        double dot = (direction.x * dx + direction.z * dz) / dist;

        // Angle between chunk direction and forward vector
        double angleDeg = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));

        if (angleDeg <= halfAngleDeg)          return ConeZone.FRONT;
        if (angleDeg <= halfAngleDeg * 2.0)    return ConeZone.SIDE;
        return ConeZone.REAR;
    }

    public enum ConeZone { FRONT, SIDE, REAR }
}
