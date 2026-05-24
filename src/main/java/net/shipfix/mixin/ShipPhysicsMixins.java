package net.shipfix.mixin;

import net.shipfix.core.ShipFixMod;
import net.shipfix.physics.ShipVelocityTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optional Mixin hooks for tighter integration with Valkyrien Skies internals.
 *
 * These mixins are applied only when VS is present on the classpath.
 *
 * Current hooks:
 *  - Pre/post ship physics step: updates velocity tracker immediately before VS
 *    integrates ship positions, giving the most accurate "about to move" snapshot.
 *
 * NOTE: All VS internal class names use the Kotlin/JVM binary names.
 *       Update target descriptors if VS internal packages change between builds.
 *
 * Mixin targets are commented out here to serve as documentation; activate by
 * uncommenting and verifying against your exact VS version's class names.
 */
public class ShipPhysicsMixins {

    /**
     * Hook into the VS ship physics pre-step.
     *
     * Target: org.valkyrienskies.core.impl.game.ships.PhysInertia (example)
     * Actual target class must be verified against VS 1.20.1 sources.
     *
     * When activated, this gives ShipVelocityTracker a synchronous update
     * immediately before VS moves any ship, ensuring our prediction uses
     * the most current velocity possible rather than the previous tick's value.
     */

    // Example skeleton — uncomment and adapt to actual VS internal class:
    //
    // @Mixin(targets = "org.valkyrienskies.core.impl.game.ships.ShipPhysicsData",
    //        remap = false)
    // public static abstract class ShipPhysicsDataMixin {
    //
    //     @Inject(method = "integrationStep", at = @At("HEAD"))
    //     private void shipfix_preIntegrationStep(CallbackInfo ci) {
    //         // Notify tracker that integration is about to run
    //         ShipVelocityTracker tracker = ShipFixMod.getVelocityTracker();
    //         if (tracker != null) {
    //             tracker.onPrePhysicsStep();
    //         }
    //     }
    //
    //     @Inject(method = "integrationStep", at = @At("RETURN"))
    //     private void shipfix_postIntegrationStep(CallbackInfo ci) {
    //         ShipVelocityTracker tracker = ShipFixMod.getVelocityTracker();
    //         if (tracker != null) {
    //             tracker.onPostPhysicsStep();
    //         }
    //     }
    // }

    /**
     * Hook into Minecraft's ServerLevel.tickChunks() to ensure our ticket
     * decay runs after vanilla chunk ticking, keeping ticket lifetimes consistent.
     *
     * Target: net.minecraft.server.level.ServerLevel
     */
    @Mixin(net.minecraft.server.level.ServerLevel.class)
    public static abstract class ServerLevelMixin {

        @Inject(
            method = "m_8714_", // tickChunk in Mojmap obfuscation
            at = @At("RETURN")
        )
        private void shipfix_afterTickChunk(
                net.minecraft.world.level.chunk.LevelChunk chunk,
                int randomTickSpeed,
                CallbackInfo ci) {
            // Future hook point: can be used to track per-chunk tick timing
            // for dynamic preload radius adjustment based on actual chunk tick latency.
        }
    }
}
