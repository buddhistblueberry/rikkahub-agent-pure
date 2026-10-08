package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure maths behind the floating ball's "thinking" orbit — the speed wobble, the
 * after-image spacing and the fade curve. The drawing itself lives in `HaloBallView` and needs a
 * real canvas, so this is the part of the animation CI can actually gate.
 */
class HaloBallMotionTest {

    @Test
    fun dot_is_at_a_standstill_when_the_turn_begins() {
        assertEquals(0f, HaloBallMotion.omega(0f), 0.0001f)
        assertEquals(0f, HaloBallMotion.omega(-5f), 0.0001f)
    }

    @Test
    fun orbit_never_reverses_or_stalls_once_ramped_up() {
        var t = HaloBallMotion.RAMP_SECONDS
        while (t < 30f) {
            val omega = HaloBallMotion.omega(t)
            assertTrue("omega must stay positive at t=$t, was $omega", omega > 0f)
            // The wobble peaks at 1 + 0.5 + 0.18 of the baseline and must never exceed it.
            val ceiling = HaloBallMotion.BASE_OMEGA * 1.68f + 0.001f
            assertTrue("omega must stay bounded at t=$t, was $omega", omega <= ceiling)
            t += 0.05f
        }
    }

    @Test
    fun speed_actually_varies_so_the_motion_does_not_read_as_mechanical() {
        val samples = (0..400).map { HaloBallMotion.omega(HaloBallMotion.RAMP_SECONDS + it * 0.05f) }
        val span = samples.max() - samples.min()
        assertTrue("speed should swing noticeably, saw span $span", span > HaloBallMotion.BASE_OMEGA * 0.4f)
    }

    @Test
    fun after_images_trail_behind_the_dot_and_never_ahead_of_it() {
        assertEquals(0f, HaloBallMotion.trailOffset(0), 0.0001f)
        // Drawn at (dotAngle - offset), so a bigger offset sits further behind the dot.
        for (i in 1..HaloBallMotion.TRAIL_GHOSTS) {
            assertTrue(HaloBallMotion.trailOffset(i) > HaloBallMotion.trailOffset(i - 1))
        }
        assertEquals(0f, HaloBallMotion.trailOffset(-3), 0.0001f)
        val tail = HaloBallMotion.trailOffset(HaloBallMotion.TRAIL_GHOSTS)
        assertTrue("the tail should sweep a visible arc, was $tail rad", tail > 0.5f)
        assertTrue("but never wrap round towards the dot again, was $tail rad", tail < 1.5f)
    }

    @Test
    fun fade_starts_bright_ends_invisible_and_never_rises() {
        assertEquals(1f, HaloBallMotion.fade(0f), 0.0001f)
        assertEquals(0f, HaloBallMotion.fade(1f), 0.0001f)
        assertEquals(1f, HaloBallMotion.fade(-1f), 0.0001f) // clamped below
        assertEquals(0f, HaloBallMotion.fade(2f), 0.0001f)  // clamped above
        var previous = 1.1f
        var f = 0f
        while (f <= 1f) {
            val value = HaloBallMotion.fade(f)
            assertTrue("fade must not rise again at f=$f", value <= previous + 0.0001f)
            previous = value
            f += 0.05f
        }
    }
}
