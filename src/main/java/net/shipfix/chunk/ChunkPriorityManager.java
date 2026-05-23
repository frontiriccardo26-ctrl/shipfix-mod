package net.shipfix.chunk;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.shipfix.core.ShipFixConfig;
import net.shipfix.core.ShipFixMod;
import net.shipfix.physics.ShipMovementPredictor.ConeZone;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages chunk loading tickets for predictive preloading.
 *
 * Assigns priority-based force-load tickets to chunks depending on their
 * position relative to ship trajectories (FRONT cone gets highest priority).
 *
 * Tickets expire after a configurable number of ticks to avoid unbounded
 * server-side ticket accumulation on pregeneated worlds.
 */
public class ChunkPriorityManager {

    private final MinecraftServer server;

    /** Key = dimension key + chunk pos hash, Value = ticket entry */
    private final Map<Long, TicketEntry> activeTickets = new ConcurrentHashMap<>();

    /** Unique ticket type identifier used with Forge's forceChunk system */
    public static final String TICKET_TYPE = "shipfix_preload";

    public ChunkPriorityManager(MinecraftServer server) {
        this.server = server;
    }

    /**
     * Registers or refreshes a chunk preload ticket for the given position.
     *
     * @param level    the server level
     * @param chunkPos chunk coordinates
     * @param zone     which zone of the ship's cone this chunk belongs to
     */
    public void requestChunk(ServerLevel level, ChunkPos chunkPos, ConeZone zone) {
        int ticketLevel = ticketLevelForZone(zone);
        long key = packKey(level, chunkPos);

        TicketEntry existing = activeTickets.get(key);
        int lifetime = ShipFixConfig.TICKET_LIFETIME_TICKS.get();

        if (existing != null) {
            // Refresh lifetime and upgrade priority if needed
            existing.refresh(ticketLevel, lifetime);
            return;
        }

        // Issue new force-load ticket via Forge (thread-safe schedule to main thread)
        TicketEntry entry = new TicketEntry(level.dimension(), chunkPos, ticketLevel, lifetime);
        activeTickets.put(key, entry);

        // Forge forceChunk must run on main thread
        server.execute(() -> {
            try {
                level.setChunkForced(chunkPos.x, chunkPos.z, true);
            } catch (Exception e) {
                ShipFixMod.LOGGER.warn("[ShipFix] Failed to force chunk {}: {}", chunkPos, e.getMessage());
            }
        });
    }

    /**
     * Called every server tick to decrement lifetimes and release expired tickets.
     */
    public void tickDecay() {
        Iterator<Map.Entry<Long, TicketEntry>> it = activeTickets.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, TicketEntry> entry = it.next();
            TicketEntry ticket = entry.getValue();
            ticket.remainingTicks--;

            if (ticket.remainingTicks <= 0) {
                it.remove();
                releaseTicket(ticket);
            }
        }
    }

    private void releaseTicket(TicketEntry ticket) {
        ServerLevel level = server.getLevel(ticket.dimension);
        if (level == null) return;

        server.execute(() -> {
            try {
                level.setChunkForced(ticket.chunkPos.x, ticket.chunkPos.z, false);
            } catch (Exception ignored) {}
        });
    }

    /** Returns whether a chunk currently has an active preload ticket. */
    public boolean isChunkTicketed(ServerLevel level, ChunkPos pos) {
        return activeTickets.containsKey(packKey(level, pos));
    }

    /** Total number of active preload tickets. */
    public int getActiveTicketCount() {
        return activeTickets.size();
    }

    public void shutdown() {
        // Release all tickets gracefully
        for (TicketEntry ticket : activeTickets.values()) {
            releaseTicket(ticket);
        }
        activeTickets.clear();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static int ticketLevelForZone(ConeZone zone) {
        return switch (zone) {
            case FRONT -> ShipFixConfig.FRONT_CONE_TICKET_LEVEL.get();
            case SIDE  -> ShipFixConfig.SIDE_TICKET_LEVEL.get();
            case REAR  -> ShipFixConfig.REAR_TICKET_LEVEL.get();
        };
    }

    /** Packs a dimension + chunk position into a single long key. */
    private static long packKey(ServerLevel level, ChunkPos pos) {
        int dimOrdinal = level.dimension().location().hashCode();
        // XOR with chunk long to create a reasonably unique key
        return ((long) dimOrdinal << 32) ^ ChunkPos.asLong(pos.x, pos.z);
    }

    // -----------------------------------------------------------------------
    // Inner classes
    // -----------------------------------------------------------------------

    private static final class TicketEntry {
        final ResourceKey<Level> dimension;
        final ChunkPos chunkPos;
        int ticketLevel;
        int remainingTicks;

        TicketEntry(ResourceKey<Level> dimension, ChunkPos chunkPos, int ticketLevel, int lifetime) {
            this.dimension     = dimension;
            this.chunkPos      = chunkPos;
            this.ticketLevel   = ticketLevel;
            this.remainingTicks = lifetime;
        }

        void refresh(int newLevel, int lifetime) {
            // Upgrade to higher priority (lower level number)
            if (newLevel < this.ticketLevel) this.ticketLevel = newLevel;
            this.remainingTicks = Math.max(this.remainingTicks, lifetime);
        }
    }
}
