package net.shipfix.core;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

public class ShipFixConfig {

    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    // --- Chunk Preloading ---
    public static final ForgeConfigSpec.IntValue BASE_PRELOAD_RADIUS;
    public static final ForgeConfigSpec.IntValue MAX_PRELOAD_RADIUS;
    public static final ForgeConfigSpec.IntValue MIN_PRELOAD_RADIUS;
    public static final ForgeConfigSpec.DoubleValue VELOCITY_SCALE_FACTOR;
    public static final ForgeConfigSpec.DoubleValue CONE_HALF_ANGLE_DEGREES;
    public static final ForgeConfigSpec.IntValue PREDICTION_TICKS;
    public static final ForgeConfigSpec.IntValue MAX_QUEUED_CHUNKS;
    public static final ForgeConfigSpec.IntValue PRELOAD_THREADS;

    // --- Physics Safety ---
    public static final ForgeConfigSpec.BooleanValue ENABLE_PHYSICS_SAFETY;
    public static final ForgeConfigSpec.IntValue FREEZE_DETECT_THRESHOLD_MS;
    public static final ForgeConfigSpec.DoubleValue MAX_SAFE_VELOCITY;
    public static final ForgeConfigSpec.BooleanValue AUTO_REDUCE_VELOCITY_ON_MISSING_CHUNKS;

    // --- Priority System ---
    public static final ForgeConfigSpec.IntValue FRONT_CONE_TICKET_LEVEL;
    public static final ForgeConfigSpec.IntValue SIDE_TICKET_LEVEL;
    public static final ForgeConfigSpec.IntValue REAR_TICKET_LEVEL;
    public static final ForgeConfigSpec.IntValue TICKET_LIFETIME_TICKS;

    // --- TPS Adaptation ---
    public static final ForgeConfigSpec.BooleanValue ENABLE_TPS_ADAPTATION;
    public static final ForgeConfigSpec.DoubleValue TPS_LOW_THRESHOLD;
    public static final ForgeConfigSpec.DoubleValue TPS_CRITICAL_THRESHOLD;

    // --- Debug ---
    public static final ForgeConfigSpec.BooleanValue DEBUG_LOG_ENABLED;
    public static final ForgeConfigSpec.IntValue DEBUG_LOG_INTERVAL_TICKS;
    public static final ForgeConfigSpec.BooleanValue DEBUG_SHOW_CHUNK_QUEUE;
    public static final ForgeConfigSpec.BooleanValue DEBUG_FREEZE_DETECTION;

    static {
        BUILDER.push("chunk_preloading");
        BASE_PRELOAD_RADIUS         = BUILDER.comment("Base chunk preload radius in chunks")
                .defineInRange("base_preload_radius", 8, 2, 32);
        MAX_PRELOAD_RADIUS          = BUILDER.comment("Maximum preload radius at high speed")
                .defineInRange("max_preload_radius", 24, 8, 64);
        MIN_PRELOAD_RADIUS          = BUILDER.comment("Minimum preload radius")
                .defineInRange("min_preload_radius", 4, 1, 16);
        VELOCITY_SCALE_FACTOR       = BUILDER.comment("How strongly velocity scales the preload radius")
                .defineInRange("velocity_scale_factor", 0.5, 0.1, 5.0);
        CONE_HALF_ANGLE_DEGREES     = BUILDER.comment("Half-angle of the forward preload cone in degrees")
                .defineInRange("cone_half_angle_degrees", 45.0, 15.0, 90.0);
        PREDICTION_TICKS            = BUILDER.comment("Ticks ahead to predict ship position")
                .defineInRange("prediction_ticks", 40, 10, 200);
        MAX_QUEUED_CHUNKS           = BUILDER.comment("Maximum chunks held in the preload queue at once")
                .defineInRange("max_queued_chunks", 512, 64, 4096);
        PRELOAD_THREADS             = BUILDER.comment("Async preload worker threads (0 = auto)")
                .defineInRange("preload_threads", 0, 0, 16);
        BUILDER.pop();

        BUILDER.push("physics_safety");
        ENABLE_PHYSICS_SAFETY               = BUILDER.comment("Enable physics stall prevention")
                .define("enable_physics_safety", true);
        FREEZE_DETECT_THRESHOLD_MS          = BUILDER.comment("Milliseconds before declaring a physics freeze")
                .defineInRange("freeze_detect_threshold_ms", 500, 100, 5000);
        MAX_SAFE_VELOCITY                   = BUILDER.comment("Max ship velocity (blocks/tick) before extra safety kicks in")
                .defineInRange("max_safe_velocity", 20.0, 5.0, 200.0);
        AUTO_REDUCE_VELOCITY_ON_MISSING_CHUNKS = BUILDER.comment("Temporarily reduce ship velocity if ahead chunks are not ready")
                .define("auto_reduce_velocity_on_missing_chunks", false);
        BUILDER.pop();

        BUILDER.push("priority");
        FRONT_CONE_TICKET_LEVEL  = BUILDER.comment("Chunk ticket level for forward cone (lower = higher priority, min 1)")
                .defineInRange("front_cone_ticket_level", 1, 1, 31);
        SIDE_TICKET_LEVEL        = BUILDER.comment("Chunk ticket level for lateral chunks")
                .defineInRange("side_ticket_level", 3, 1, 31);
        REAR_TICKET_LEVEL        = BUILDER.comment("Chunk ticket level for rear chunks")
                .defineInRange("rear_ticket_level", 5, 1, 31);
        TICKET_LIFETIME_TICKS    = BUILDER.comment("How long a preload ticket stays active (ticks)")
                .defineInRange("ticket_lifetime_ticks", 60, 10, 600);
        BUILDER.pop();

        BUILDER.push("tps_adaptation");
        ENABLE_TPS_ADAPTATION   = BUILDER.comment("Dynamically adjust preload based on server TPS")
                .define("enable_tps_adaptation", true);
        TPS_LOW_THRESHOLD       = BUILDER.comment("TPS below this value triggers expanded preload")
                .defineInRange("tps_low_threshold", 18.0, 5.0, 20.0);
        TPS_CRITICAL_THRESHOLD  = BUILDER.comment("TPS below this value triggers maximum safety mode")
                .defineInRange("tps_critical_threshold", 12.0, 1.0, 18.0);
        BUILDER.pop();

        BUILDER.push("debug");
        DEBUG_LOG_ENABLED         = BUILDER.comment("Enable periodic debug logging")
                .define("debug_log_enabled", false);
        DEBUG_LOG_INTERVAL_TICKS  = BUILDER.comment("Ticks between debug log entries")
                .defineInRange("debug_log_interval_ticks", 40, 1, 1200);
        DEBUG_SHOW_CHUNK_QUEUE    = BUILDER.comment("Log chunk queue size in debug output")
                .define("debug_show_chunk_queue", true);
        DEBUG_FREEZE_DETECTION    = BUILDER.comment("Log freeze detection events")
                .define("debug_freeze_detection", true);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    public static void register(IEventBus bus) {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SPEC, "shipfix-server.toml");
    }
}
