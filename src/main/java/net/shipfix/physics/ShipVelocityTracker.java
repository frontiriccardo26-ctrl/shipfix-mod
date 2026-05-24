package net.shipfix.physics;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.shipfix.core.ShipFixMod;
import org.joml.Vector3d;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-ship velocity, direction, acceleration, and angular velocity.
 * Uses reflection to access VS API to avoid compile-time dependency issues.
 */
public class ShipVelocityTracker {

    private final Map<Long, ShipMotionData> motionDataMap = new ConcurrentHashMap<>();
    private final Map<Long, Vector3d> previousVelocityMap = new ConcurrentHashMap<>();

    // Reflection cache
    private Class<?> shipClass;
    private Class<?> shipWorldClass;
    private Method getAllShipsMethod;
    private Method getIdMethod;
    private Method getVelocityMethod;
    private Method getOmegaMethod;
    private Method getTransformMethod;
    private Method getPositionMethod;
    private Method getShipWorldMethod;
    private boolean reflectionInitialized = false;
    private boolean reflectionFailed = false;

    private void initReflection() {
        if (reflectionInitialized || reflectionFailed) return;
        try {
            shipClass = Class.forName("org.valkyrienskies.core.api.ships.ServerShip");
            shipWorldClass = Class.forName("org.valkyrienskies.core.api.world.ShipWorld");
            getAllShipsMethod = shipWorldClass.getMethod("getAllShips");

            getIdMethod = Class.forName("org.valkyrienskies.core.api.ships.Ship")
                    .getMethod("getId");
            getVelocityMethod = shipClass.getMethod("getVelocity");
            getOmegaMethod = shipClass.getMethod("getOmega");
            getTransformMethod = Class.forName("org.valkyrienskies.core.api.ships.Ship")
                    .getMethod("getTransform");

            Class<?> transformClass = Class.forName("org.valkyrienskies.core.api.ships.properties.ShipTransform");
            getPositionMethod = transformClass.getMethod("getPositionInWorld");

            Class<?> vsUtilsClass = Class.forName("org.valkyrienskies.mod.common.VSGameUtilsKt");
            getShipWorldMethod = vsUtilsClass.getMethod("getShipObjectWorld",
                    net.minecraft.server.level.ServerLevel.class);

            reflectionInitialized = true;
            ShipFixMod.LOGGER.info("[ShipFix] VS reflection initialized successfully.");
        } catch (Exception e) {
            reflectionFailed = true;
            ShipFixMod.LOGGER.warn("[ShipFix] VS reflection failed: {} — ship tracking disabled.", e.getMessage());
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        initReflection();
        if (reflectionFailed) return;

        for (net.minecraft.server.level.ServerLevel level :
                ServerLifecycleHooks.getCurrentServer().getAllLevels()) {
            try {
                Object shipWorld = getShipWorldMethod.invoke(null, level);
                if (shipWorld == null) continue;

                Iterable<?> ships = (Iterable<?>) getAllShipsMethod.invoke(shipWorld);
                for (Object ship : ships) {
                    updateShipData(ship);
                }
            } catch (Exception e) {
                // Ignore per-tick errors silently
            }
        }
    }

    private void updateShipData(Object ship) {
        try {
            long id = (Long) getIdMethod.invoke(ship);

            Object vel = getVelocityMethod.invoke(ship);
            Object angVel = getOmegaMethod.invoke(ship);
            Object transform = getTransformMethod.invoke(ship);
            Object pos = getPositionMethod.invoke(transform);

            double vx = (double) vel.getClass().getMethod("x").invoke(vel);
            double vy = (double) vel.getClass().getMethod("y").invoke(vel);
            double vz = (double) vel.getClass().getMethod("z").invoke(vel);

            double ax = (double) angVel.getClass().getMethod("x").invoke(angVel);
            double ay = (double) angVel.getClass().getMethod("y").invoke(angVel);
            double az = (double) angVel.getClass().getMethod("z").invoke(angVel);

            double px = (double) pos.getClass().getMethod("x").invoke(pos);
            double py = (double) pos.getClass().getMethod("y").invoke(pos);
            double pz = (double) pos.getClass().getMethod("z").invoke(pos);

            Vector3d velocity = new Vector3d(vx, vy, vz);
            double speed = velocity.length();
            Vector3d direction = speed > 1e-6 ? new Vector3d(velocity).div(speed) : new Vector3d(0, 0, 1);

            Vector3d prevVel = previousVelocityMap.getOrDefault(id, new Vector3d(velocity));
            Vector3d acceleration = new Vector3d(velocity).sub(prevVel);
            previousVelocityMap.put(id, new Vector3d(velocity));

            ShipMotionData data = new ShipMotionData(
                    id,
                    new Vector3d(px, py, pz),
                    velocity,
                    direction,
                    acceleration,
                    speed,
                    new Vector3d(ax, ay, az),
                    System.currentTimeMillis()
            );
            motionDataMap.put(id, data);
        } catch (Exception e) {
            // Skip this ship silently
        }
    }

    public ShipMotionData getMotionData(long shipId) { return motionDataMap.get(shipId); }
    public Collection<ShipMotionData> getAllMotionData() { return motionDataMap.values(); }
    public void clearStale(Set<Long> activeIds) {
        motionDataMap.keySet().retainAll(activeIds);
        previousVelocityMap.keySet().retainAll(activeIds);
    }

    public static final class ShipMotionData {
        public final long shipId;
        public final Vector3d position;
        public final Vector3d velocity;
        public final Vector3d direction;
        public final Vector3d acceleration;
        public final double speed;
        public final Vector3d angularVelocity;
        public final long timestamp;

        public ShipMotionData(long shipId, Vector3d position, Vector3d velocity,
                              Vector3d direction, Vector3d acceleration, double speed,
                              Vector3d angularVelocity, long timestamp) {
            this.shipId = shipId; this.position = position; this.velocity = velocity;
            this.direction = direction; this.acceleration = acceleration;
            this.speed = speed; this.angularVelocity = angularVelocity;
            this.timestamp = timestamp;
        }

        public double speedBlocksPerSecond() { return speed * 20.0; }
        public boolean isMovingFast(double threshold) { return speed > threshold; }
    }
}
