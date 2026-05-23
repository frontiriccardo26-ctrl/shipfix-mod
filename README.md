# ShipFix — VS Predictive Chunk Loader

**Forge 1.20.1 | Valkyrien Skies 2 | Java 17**

Eliminates freeze, rubberbanding, and physics stalls of Valkyrien Skies ships at extreme velocity — purpose-built for large-scale space-battle servers on pre-generated worlds.

---

## Problem Being Solved

When a VS ship travels faster than Minecraft can load/sync chunks, the physics engine stalls waiting for chunk data. On a pre-generated world this should never happen, but the vanilla chunk-loading system lacks **priority awareness** — it loads chunks in an arbitrary order with no knowledge of where a ship will be in the next second.

ShipFix solves this with a **predictive, priority-weighted, asynchronous chunk preloading pipeline** that always keeps the chunks *ahead of every ship* fully loaded before the ship arrives.

---

## Architecture

```
ShipVelocityTracker          (reads VS physics state every tick)
       │
       ▼
ShipMovementPredictor        (kinematic trajectory + cone geometry)
       │
       ▼
PredictiveChunkLoader        (orchestrator — runs on main tick thread)
       │           │
       │           ▼
       │    ChunkPriorityManager   (Forge force-load ticket system)
       │           │
       ▼           ▼
AsyncChunkPreloader          (bounded priority queue + thread pool)
       │
       ▼
PhysicsSafetyManager         (freeze detection + corrective logging)
       │
       ▼
ShipFixDebugger              (commands + periodic logs)
```

### Components

| Class | Package | Role |
|---|---|---|
| `ShipVelocityTracker` | `physics` | Per-tick snapshot of velocity, direction, acceleration, angular velocity for every ship |
| `ShipMovementPredictor` | `physics` | Kinematic integration with yaw-curve estimation; computes dynamic preload radius |
| `PredictiveChunkLoader` | `chunk` | Main tick orchestrator; generates cone footprints and dispatches to preloader |
| `ChunkPriorityManager` | `chunk` | Manages Forge `setChunkForced` tickets with zone-based priority and TTL decay |
| `AsyncChunkPreloader` | `chunk` | Priority `BlockingQueue` + dedicated thread pool; rate-limits main-thread dispatch |
| `PhysicsSafetyManager` | `physics` | Detects frozen (stalled) ships; logs diagnostics; optional velocity soft-cap |
| `ShipFixDebugger` | `debug` | Periodic console reports + `/shipfix` in-game command |
| `CompatibilityManager` | `compat` | Detects C2ME / Lithium / FerriteCore and adjusts behaviour |
| `ShipPhysicsMixins` | `mixin` | Optional hooks into VS/MC internals for sub-tick precision |
| `ShipFixConfig` | `core` | TOML server config — all parameters tunable at runtime |

---

## Preload Cone System

For each ship every tick:

1. Sample 10 trajectory waypoints over the next N ticks.
2. For each waypoint, generate a **circle** of chunks with radius R.
3. Classify each chunk as **FRONT / SIDE / REAR** based on angle to velocity vector.
4. Assign ticket priority accordingly (FRONT = highest).
5. Submit to `AsyncChunkPreloader` queue (bounded, drops REAR if full).

```
           [FRONT cone — highest priority]
                  ▲ ▲ ▲ ▲ ▲
                 ▲ ▲ ▲ ▲ ▲ ▲
       [SIDE] ◄ ▲ ▲ [ship] ▲ ▲ ► [SIDE]
                 ▼ ▼ ▼ ▼ ▼ ▼
                  ▼ ▼ ▼ ▼ ▼
           [REAR — lowest priority, dropped first]
```

---

## Installation

### Requirements

- Minecraft Forge **1.20.1-47.2.x**
- Valkyrien Skies **2.3.0-beta.2** or newer for 1.20.1
- Java **17**

### Optional (highly recommended)

- **C2ME** — concurrent chunk I/O (ShipFix defers async I/O to C2ME automatically)
- **Lithium** — general tick optimisations
- **FerriteCore** — memory footprint reduction

### Build

```bash
./gradlew build
# Output: build/libs/shipfix-1.0.0.jar
```

Place the jar in your server's `mods/` folder alongside VS.

---

## Configuration — `shipfix-server.toml`

Generated on first launch in `config/shipfix-server.toml`.

### Chunk Preloading

```toml
[chunk_preloading]
base_preload_radius = 8       # chunks — baseline radius at low speed
max_preload_radius  = 24      # chunks — cap at high speed
min_preload_radius  = 4       # chunks — floor
velocity_scale_factor = 0.5   # radius += speed_chunks_per_tick * this * tps_factor
cone_half_angle_degrees = 45.0  # width of the FRONT priority cone
prediction_ticks    = 40      # how far ahead to simulate (ticks)
max_queued_chunks   = 512     # max chunks in async queue before dropping REAR
preload_threads     = 0       # 0 = auto (half of available CPUs)
```

### Physics Safety

```toml
[physics_safety]
enable_physics_safety = true
freeze_detect_threshold_ms = 500          # ms of no movement → freeze declared
max_safe_velocity = 20.0                  # b/t above which extra preloading kicks in
auto_reduce_velocity_on_missing_chunks = false  # experimental — leave false unless needed
```

### Priority Levels

```toml
[priority]
front_cone_ticket_level = 1   # Forge ticket level — lower = more urgent
side_ticket_level       = 3
rear_ticket_level       = 5
ticket_lifetime_ticks   = 60  # ticks before a ticket expires if not refreshed
```

### TPS Adaptation

```toml
[tps_adaptation]
enable_tps_adaptation   = true
tps_low_threshold       = 18.0  # below this → expand preload radius
tps_critical_threshold  = 12.0  # below this → maximum preload + safety mode
```

### Debug

```toml
[debug]
debug_log_enabled        = false  # enable for tuning; disable in production
debug_log_interval_ticks = 40     # log every 2 seconds
debug_show_chunk_queue   = true
debug_freeze_detection   = true
```

---

## In-Game Commands

Requires operator level 2.

| Command | Description |
|---|---|
| `/shipfix debug` | Print full diagnostic report to chat |
| `/shipfix ships` | List all tracked ships with velocity and direction |
| `/shipfix queue` | Show current queue size and active ticket count |
| `/shipfix tps` | Show estimated server TPS |

---

## Tuning Guide for Space Battle Servers

### For very fast ships (>30 blocks/tick)

```toml
max_preload_radius  = 32
prediction_ticks    = 80
velocity_scale_factor = 0.8
cone_half_angle_degrees = 60.0
ticket_lifetime_ticks = 100
```

### For large ships (>200 blocks long)

Increase `base_preload_radius` to account for the physical footprint:

```toml
base_preload_radius = 14
max_preload_radius  = 36
```

### For many ships simultaneously (>10)

Raise `max_queued_chunks` and use C2ME for parallel I/O:

```toml
max_queued_chunks = 1024
preload_threads   = 4   # leave at 0 if C2ME is installed
```

### With C2ME installed

Set `preload_threads = 0` (ShipFix auto-detects C2ME and defers thread management).

---

## Guarantees

| Guarantee | Status |
|---|---|
| Collisions remain accurate | ✅ — no VS physics disabled |
| Hitboxes remain active | ✅ |
| Server-client sync preserved | ✅ |
| World generation disabled | ✅ — force-load tickets on pre-gen worlds skip gen |
| No GC pressure from hot path | ✅ — pooled data structures, no per-tick allocation |
| Safe on multiplayer | ✅ — all ticket ops dispatched to main thread |

---

## Known Limitations

- VS API surface for direct velocity manipulation (the optional soft-cap feature) may vary between VS beta builds. Keep `auto_reduce_velocity_on_missing_chunks = false` unless tested.
- Mixin hooks for sub-tick VS integration require manual verification of VS internal class names for your exact VS version. The skeletons are provided in `ShipPhysicsMixins.java`.
- C2ME's async chunk pipeline and Forge's `setChunkForced` interact through Minecraft's chunk ticket system — both are designed to be composable, but test on your specific mod combination.

---

## License

MIT — free to use, modify, and redistribute.
