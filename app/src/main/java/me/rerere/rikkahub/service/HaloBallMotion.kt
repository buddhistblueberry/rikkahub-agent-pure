package me.rerere.rikkahub.service

import kotlin.math.pow
import kotlin.math.sin

/**
 * Pure motion maths for the floating ball's "thinking" orbit.
 *
 * Deliberately free of Android imports so a plain JVM unit test can pin it — the drawing code
 * around it lives in [HaloBallView] and needs a real canvas, which is not unit-testable here.
 */
object HaloBallMotion {

    /** Baseline angular speed of the light dot, in radians per second. */
    const val BASE_OMEGA: Float = 4.3f

    /** Seconds over which the orbit spins up from a standstill when a turn begins. */
    const val RAMP_SECONDS: Float = 0.35f

    /** Angular gap between two neighbouring after-images, in radians. */
    const val TRAIL_STEP_RAD: Float = 0.058f

    /** How many after-images make up the comet tail (1 = nearest the dot). */
    const val TRAIL_GHOSTS: Int = 15

    /**
     * Angular speed of the dot, [elapsed] seconds into a turn, in radians per second.
     *
     * Two slow sine terms make the dot speed up and slow down as it goes round, so the motion
     * reads as alive rather than mechanical; the short ramp stops it snapping to full speed the
     * instant a turn begins. Never negative, and 0 at [elapsed] <= 0.
     */
    fun omega(elapsed: Float): Float {
        if (elapsed <= 0f) return 0f
        val wobble = 1f + 0.5f * sin(elapsed * 0.9f) + 0.18f * sin(elapsed * 2.3f)
        val ramp = (elapsed / RAMP_SECONDS).coerceIn(0f, 1f)
        return BASE_OMEGA * wobble * ramp
    }

    /** Angle (radians *behind* the dot) of after-image [index]; index 0 is the dot itself. */
    fun trailOffset(index: Int): Float = index.coerceAtLeast(0) * TRAIL_STEP_RAD

    /**
     * Opacity multiplier for an after-image / particle at [lifeFraction] through its life
     * (0 = just born and brightest, 1 = spent). Tapers faster the older it gets, so the tail
     * thins out instead of ending in a hard edge.
     */
    fun fade(lifeFraction: Float): Float {
        val f = lifeFraction.coerceIn(0f, 1f)
        return (1f - f).pow(1.6f)
    }
}
