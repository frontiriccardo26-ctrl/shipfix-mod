package net.shipfix.compat;

import net.minecraftforge.fml.ModList;
import net.shipfix.core.ShipFixMod;

/**
 * Compatibility layer for known optimisation mods.
 *
 * ShipFix is designed to be fully additive and non-conflicting with:
 *  - C2ME (Concurrent Chunk Management Engine)
 *  - Lithium
 *  - FerriteCore
 *
 * This class detects their presence and adjusts ShipFix behaviour accordingly.
 */
public final class CompatibilityManager {

    public static final boolean C2ME_PRESENT;
    public static final boolean LITHIUM_PRESENT;
    public static final boolean FERRITECORE_PRESENT;

    static {
        ModList mods = ModList.get();
        C2ME_PRESENT        = mods.isLoaded("c2me");
        LITHIUM_PRESENT     = mods.isLoaded("lithium");
        FERRITECORE_PRESENT = mods.isLoaded("ferritecore");
    }

    private CompatibilityManager() {}

    public static void logCompatibilityReport() {
        ShipFixMod.LOGGER.info("[ShipFix Compat] C2ME={} | Lithium={} | FerriteCore={}",
                C2ME_PRESENT, LITHIUM_PRESENT, FERRITECORE_PRESENT);

        if (C2ME_PRESENT) {
            ShipFixMod.LOGGER.info(
                "[ShipFix Compat] C2ME detected — ShipFix will defer to C2ME's async " +
                "chunk I/O pipeline. Our AsyncChunkPreloader will only issue force-load " +
                "tickets; actual async I/O is handled by C2ME. This is the optimal config.");
        }

        if (LITHIUM_PRESENT) {
            ShipFixMod.LOGGER.info(
                "[ShipFix Compat] Lithium detected — chunk tick and entity optimisations " +
                "active. ShipFix priority tickets remain compatible; no conflicts expected.");
        }

        if (FERRITECORE_PRESENT) {
            ShipFixMod.LOGGER.info(
                "[ShipFix Compat] FerriteCore detected — memory layout optimisations active. " +
                "ShipFix's ConcurrentHashMap caches are unaffected.");
        }
    }

    /**
     * When C2ME is present, ShipFix should NOT spin up its own aggressive thread pool
     * for chunk I/O — C2ME already saturates the I/O threads optimally.
     * ShipFix only needs to issue the tickets; C2ME handles the rest.
     *
     * @return true if ShipFix should reduce its own worker thread count.
     */
    public static boolean shouldDeferAsyncIOToC2ME() {
        return C2ME_PRESENT;
    }

    /**
     * When Lithium is present, some chunk-tick hooks may be remapped by Lithium's mixins.
     * ShipFix uses Forge events rather than direct mixins for core logic, so conflicts
     * are unlikely. Return true to enable an extra compatibility check log.
     */
    public static boolean hasLithiumChunkHooks() {
        return LITHIUM_PRESENT;
    }
}
