package net.shipfix.physics;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.shipfix.chunk.PredictiveChunkLoader;
import net.shipfix.core.ShipFixConfig;
import net.shipfix.core.ShipFixMod;
import net.shipfix.physics.ShipVelocityTracker.ShipMotionData;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PhysicsSafetyManager {

    private static final double STALL_POSITION_DELTA_THRESHOLD = 0.01;
    private static final double MOVING_VELOCITY_THRESHOLD = 0.5;

    private final MinecraftServer server;
    private final ShipVelocityTracker velocityTracker;
    private final PredictiveChunkLoader predictiveLoader;
    private final Map<Long, FreezeMonitorEntry> freezeMonitor = new ConcurrentHashMap<>();

    private volatile int totalFreezeEvents = 0;
    private volatile int activeFreezeCount = 0;

    // Reflection
    private Method getAllShipsMethod;
    private Method getShipWorldMethod;
    private Method getIdMethod;
    private Method getTransformMethod;
    private Method getPositionMethod;
    private boolean reflectionReady = false;

    public PhysicsSafetyManager(MinecraftServer server,
                                 ShipVelocityTracker velocityTracker,
                                 PredictiveChunkLoader predictiveLoader) {
        this.server = server;
        this.velocityTracker = velocityTracker;
        this.predictiveLoader = predictiveLoader;
        initReflection();
    }

    private void initReflection() {
        try {
            Class<?> shipWorldClass = Class.forName("org.valkyrienskies.core.api.world.ShipWorld");
            getAllShipsMethod = shipWorldClass.getMethod("getAllShips");
            Class<?> vsUtilsClass = Class.forName("org.valkyrienskies.mod.common.VSGameUtilsKt");
            getShipWorldMethod = vsUtilsClass.getMethod("getShipObjectWorld", ServerLevel.class);
            Class<?> shipBase = Class.forName("org.valkyrienskies.core.api.ships.Ship");
            getIdMethod = shipBase.getMethod("getId");
            getTransformMethod = shipBase.getMethod("getTransform");
            Class<?> transformClass = Class.forName("org.valkyrienskies.core.api.ships.properties.ShipTransform");
            getPositionMethod = transformClass.getMethod("getPositionInWorld");
            reflectionReady = true;
        } catch (Exception e) {
            ShipFixMod.LOGGER.warn("[ShipFix] PhysicsSafetyManager reflection failed: {}", e.getMessage());
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!ShipFixConfig.ENABLE_PHYSICS_SAFETY.get() || !reflectionReady) return;

        int active = 0;
        for (ServerLevel level : server.getAllLevels()) {
            try {
                Object shipWorld = getShipWorldMethod.invoke(null, level);
                if (shipWorld == null) continue;
                for (Object ship : (Iterable<?>) getAllShipsMethod.invoke(shipWorld)) {
                    if (checkShipFreeze(ship)) active++;
                }
            } catch (Exception ignored) {}
        }
        activeFreezeCount = active;
    }

    private boolean checkShipFreeze(Object ship) {
        try {
            long id = (Long) getIdMethod.invoke(ship);
            ShipMotionData data = velocityTracker.getMotionData(id);
            if (data == null || data.speed < MOVING_VELOCITY_THRESHOLD) {
                freezeMonitor.remove(id);
                return false;
            }

            Object transform = getTransformMethod.invoke(ship);
            Object pos = getPositionMethod.invoke(transform);
            double wx = (double) pos.getClass().getMethod("x").invoke(pos);
            double wy = (double) pos.getClass().getMethod("y").invoke(pos);
            double wz = (double) pos.getClass().getMethod("z").invoke(pos);

            FreezeMonitorEntry entry = freezeMonitor.computeIfAbsent(id,
                    k -> new FreezeMonitorEntry(wx, wy, wz));

            double delta = Math.sqrt(Math.pow(wx - entry.lastX, 2) +
                    Math.pow(wy - entry.lastY, 2) + Math.pow(wz - entry.lastZ, 2));
            long now = System.currentTimeMillis();

            if (delta < STALL_POSITION_DELTA_THRESHOLD) {
                long stalledMs = now - entry.stallStartMs;
                if (stalledMs > ShipFixConfig.FREEZE_DETECT_THRESHOLD_MS.get()) {
                    if (!entry.freezeReported) {
                        totalFreezeEvents++;
                        if (ShipFixConfig.DEBUG_FREEZE_DETECTION.get()) {
                            ShipFixMod.LOGGER.warn("[ShipFix] FREEZE ship={} speed={} stalledMs={}",
                                    id, data.speed, stalledMs);
                        }
                        entry.freezeReported = true;
                    }
                    return true;
                }
            } else {
                entry.lastX = wx; entry.lastY = wy; entry.lastZ = wz;
                entry.stallStartMs = now;
                entry.freezeReported = false;
            }
        } catch (Exception ignored) {}
        return false;
    }

    public int getTotalFreezeEvents() { return totalFreezeEvents; }
    public int getActiveFreezeCount() { return activeFreezeCount; }

    private static final class FreezeMonitorEntry {
        double lastX, lastY, lastZ;
        long stallStartMs;
        boolean freezeReported;
        FreezeMonitorEntry(double x, double y, double z) {
            lastX = x; lastY = y; lastZ = z;
            stallStartMs = System.currentTimeMillis();
        }
    }
}
