package net.shipfix.chunk;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.shipfix.core.ShipFixConfig;
import net.shipfix.core.ShipFixMod;
import net.shipfix.physics.ShipMovementPredictor.ConeZone;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Asynchronous chunk preloader with priority-ordered work queue.
 *
 * Uses a bounded {@link PriorityBlockingQueue} so FRONT-cone chunks are
 * always processed before SIDE and REAR chunks. A configurable thread pool
 * issues load requests and delegates ticket issuance back to the main thread
 * via the server's execute queue.
 *
 * Rate-limiting is applied to avoid flooding the server tick with ticket calls.
 */
public class AsyncChunkPreloader {

    /** Max chunk requests submitted to the server per tick. */
    private static final int MAX_REQUESTS_PER_TICK = 32;

    private final MinecraftServer server;
    private final ChunkPriorityManager priorityManager;

    /** Priority queue ordered by ConeZone priority (FRONT first). */
    private final PriorityBlockingQueue<ChunkLoadRequest> workQueue;

    private final ExecutorService executor;

    // Metrics
    private final AtomicInteger processedThisTick = new AtomicInteger(0);
    private final AtomicInteger totalQueued       = new AtomicInteger(0);
    private final AtomicLong    totalProcessed    = new AtomicLong(0);
    private volatile long       lastTickReset     = System.currentTimeMillis();

    public AsyncChunkPreloader(MinecraftServer server, ChunkPriorityManager priorityManager) {
        this.server          = server;
        this.priorityManager = priorityManager;
        this.workQueue       = new PriorityBlockingQueue<>(
                ShipFixConfig.MAX_QUEUED_CHUNKS.get(),
                ChunkLoadRequest.COMPARATOR);

        int threads = ShipFixConfig.PRELOAD_THREADS.get();
        if (threads <= 0) {
            threads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        }

        this.executor = Executors.newFixedThreadPool(threads,
                new NamedThreadFactory("shipfix-preloader"));

        ShipFixMod.LOGGER.info("[ShipFix] AsyncChunkPreloader started with {} threads.", threads);
    }

    public void start() {
        // Worker threads pull from queue and dispatch to main thread
        int threadCount = ((ThreadPoolExecutor) executor).getCorePoolSize();
        for (int i = 0; i < threadCount; i++) {
            executor.submit(this::workerLoop);
        }
    }

    /**
     * Enqueues a chunk for preloading. Drops the request silently if the
     * queue is full to avoid OOM conditions.
     */
    public void enqueue(ServerLevel level, ChunkPos pos, ConeZone zone) {
        if (workQueue.size() >= ShipFixConfig.MAX_QUEUED_CHUNKS.get()) {
            return; // Queue full — drop low-priority work
        }
        // Skip if already ticketed
        if (priorityManager.isChunkTicketed(level, pos)) return;

        workQueue.offer(new ChunkLoadRequest(level, pos, zone));
        totalQueued.incrementAndGet();
    }

    /**
     * Called every server tick — drains up to MAX_REQUESTS_PER_TICK items
     * from the queue on the main thread where ticket operations are safe.
     *
     * This is intentionally called from the main tick (via PredictiveChunkLoader).
     */
    public void drainOnMainThread() {
        int processed = 0;
        while (processed < MAX_REQUESTS_PER_TICK) {
            ChunkLoadRequest req = workQueue.poll();
            if (req == null) break;

            priorityManager.requestChunk(req.level, req.chunkPos, req.zone);
            processed++;
            totalProcessed.incrementAndGet();
        }
        processedThisTick.set(processed);
    }

    /** Worker loop — validates chunk positions; main thread does the actual forcing. */
    private void workerLoop() {
        while (!Thread.currentThread().isInterrupted() && !executor.isShutdown()) {
            try {
                ChunkLoadRequest req = workQueue.poll(100, TimeUnit.MILLISECONDS);
                if (req == null) continue;

                // Submit ticket request to main thread
                server.execute(() -> priorityManager.requestChunk(req.level, req.chunkPos, req.zone));
                totalProcessed.incrementAndGet();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                ShipFixMod.LOGGER.warn("[ShipFix] Preloader worker error: {}", e.getMessage());
            }
        }
    }

    public void shutdown() {
        executor.shutdownNow();
        workQueue.clear();
    }

    // -----------------------------------------------------------------------
    // Metrics accessors (for debug)
    // -----------------------------------------------------------------------
    public int getQueueSize()       { return workQueue.size(); }
    public long getTotalProcessed() { return totalProcessed.get(); }
    public int getTotalQueued()     { return totalQueued.get(); }

    // -----------------------------------------------------------------------
    // Inner classes
    // -----------------------------------------------------------------------

    /** A prioritised request to preload a single chunk. */
    private static final class ChunkLoadRequest {
        final ServerLevel level;
        final ChunkPos    chunkPos;
        final ConeZone    zone;

        static final java.util.Comparator<ChunkLoadRequest> COMPARATOR =
                java.util.Comparator.comparingInt(r -> r.zone.ordinal()); // FRONT=0, SIDE=1, REAR=2

        ChunkLoadRequest(ServerLevel level, ChunkPos chunkPos, ConeZone zone) {
            this.level    = level;
            this.chunkPos = chunkPos;
            this.zone     = zone;
        }
    }

    /** Named thread factory for easier profiling/debugging. */
    private static final class NamedThreadFactory implements ThreadFactory {
        private final String namePrefix;
        private final AtomicInteger count = new AtomicInteger(0);

        NamedThreadFactory(String namePrefix) { this.namePrefix = namePrefix; }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, namePrefix + "-" + count.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY + 1); // slightly above normal
            return t;
        }
    }
}
