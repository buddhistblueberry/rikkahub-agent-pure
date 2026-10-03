package me.rerere.rikkahub.data.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageTurnViewTest {

    private fun row(
        id: String,
        atMs: Long,
        purpose: UsagePurpose = UsagePurpose.MAIN,
        input: Int = 0,
        output: Int = 0,
        cached: Int = 0,
        cachedReported: Boolean = false,
        providerCostUsd: Double? = null,
        costMicros: Long? = null,
        latencyMs: Long? = null,
        streaming: Boolean = false,
    ) = UsageRecordEntity(
        id = id,
        createdAtMs = atMs,
        purpose = purpose.name,
        inputTokens = input,
        outputTokens = output,
        totalTokens = input + output,
        cachedTokens = cached,
        cachedTokensReported = cachedReported,
        providerCostUsd = providerCostUsd,
        costMicros = costMicros,
        latencyMs = latencyMs,
        streaming = streaming,
    )

    // ---- windows -------------------------------------------------------------------------

    @Test
    fun `windows end where the next node begins and the last is open-ended`() {
        val windows = UsageTurnViewFactory.windowsFor(
            listOf("m1" to 100L, "m2" to 500L, "m3" to 900L)
        )

        assertEquals(3, windows.size)
        assertEquals("m1", windows[0].first)
        assertEquals(UsageTurnWindow(100L, 500L), windows[0].second)
        assertEquals(UsageTurnWindow(500L, 900L), windows[1].second)
        assertEquals(UsageTurnWindow(900L, Long.MAX_VALUE), windows[2].second)
    }

    @Test
    fun `a non-increasing start becomes an empty window, never a reversed one`() {
        val windows = UsageTurnViewFactory.windowsFor(listOf("m1" to 100L, "m2" to 100L))

        // m1 loses its share; m2 still gets everything from 100 on.
        assertEquals(UsageTurnWindow(100L, 100L), windows[0].second)
        assertFalse(windows[0].second.contains(100L))
        assertEquals(UsageTurnWindow(100L, Long.MAX_VALUE), windows[1].second)
        assertTrue(windows[1].second.contains(100L))
    }

    // ---- purpose filter ------------------------------------------------------------------

    @Test
    fun `title and suggestion are not part of a turn`() {
        assertFalse(UsageTurnViewFactory.isTurnCall(UsagePurpose.TITLE.name))
        assertFalse(UsageTurnViewFactory.isTurnCall(UsagePurpose.SUGGESTION.name))
    }

    @Test
    fun `every other purpose stays in the turn`() {
        val kept = listOf(
            UsagePurpose.MAIN,
            UsagePurpose.TOOL_LOOP,
            UsagePurpose.COMPACTION,
            UsagePurpose.MEMORY_EXTRACT,
            UsagePurpose.SUBAGENT,
            UsagePurpose.CRON,
            UsagePurpose.WORKFLOW,
            UsagePurpose.SKILL_TEST,
            UsagePurpose.TRANSLATION,
            UsagePurpose.UNKNOWN,
        )
        for (purpose in kept) {
            assertTrue(purpose.name, UsageTurnViewFactory.isTurnCall(purpose.name))
        }
    }

    // ---- aggregation ---------------------------------------------------------------------

    @Test
    fun `aggregate sums every call and lists them oldest first`() {
        val view = UsageTurnViewFactory.aggregate(
            listOf(
                row("b", 200L, input = 30, output = 5),
                row("a", 100L, input = 10, output = 2),
            )
        )

        assertEquals(2, view.callCount)
        assertEquals(listOf(100L, 200L), view.calls.map { it.atMs })
        assertEquals(40L, view.inputTokens)
        assertEquals(7L, view.outputTokens)
    }

    @Test
    fun `cache rate only counts rows that reported cache fields`() {
        val view = UsageTurnViewFactory.aggregate(
            listOf(
                // Reports nothing about cache: its 1000 input tokens must not enter the rate.
                row("a", 100L, input = 1000, output = 1, cached = 0, cachedReported = false),
                row("b", 200L, input = 100, output = 1, cached = 80, cachedReported = true),
                row("c", 300L, input = 50, output = 1, cached = 30, cachedReported = true),
            )
        )

        assertTrue(view.cacheReported)
        assertEquals(110L, view.cacheHitTokens)
        assertEquals(150L, view.cachePromptTokens)
        // The unreported row still counts toward the raw input total.
        assertEquals(1150L, view.inputTokens)
    }

    @Test
    fun `cache is absent when no row reported it`() {
        val view = UsageTurnViewFactory.aggregate(
            listOf(row("a", 100L, input = 10, output = 1, cached = 0, cachedReported = false))
        )

        assertFalse(view.cacheReported)
        assertEquals(0L, view.cacheHitTokens)
        assertEquals(0L, view.cachePromptTokens)
    }

    @Test
    fun `costs sum only over the rows that carry them`() {
        val view = UsageTurnViewFactory.aggregate(
            listOf(
                row("a", 100L, providerCostUsd = 0.001, costMicros = 1000L),
                row("b", 200L, providerCostUsd = null, costMicros = 250L),
            )
        )

        assertEquals(0.001, view.providerCostUsd!!, 1e-12)
        assertEquals(1250L, view.costMicros)
        assertFalse(view.estimatedCostOnly)
    }

    @Test
    fun `a turn with only a computed price is estimated`() {
        val view = UsageTurnViewFactory.aggregate(
            listOf(row("a", 100L, providerCostUsd = null, costMicros = 700L))
        )

        assertNull(view.providerCostUsd)
        assertEquals(700L, view.costMicros)
        assertTrue(view.estimatedCostOnly)
    }

    @Test
    fun `an unpriced turn stays null, never zero`() {
        val view = UsageTurnViewFactory.aggregate(listOf(row("a", 100L)))

        assertNull(view.providerCostUsd)
        assertNull(view.costMicros)
        assertFalse(view.estimatedCostOnly)
    }

    // ---- window assignment ---------------------------------------------------------------

    @Test
    fun `a row on a boundary belongs to the later window only`() {
        val windows = UsageTurnViewFactory.windowsFor(listOf("m1" to 100L, "m2" to 500L))
        val records = listOf(
            row("early", 100L),
            row("onBoundary", 500L),
            row("late", 900L),
        )

        val byMessage = UsageTurnViewFactory.map(windows, records)

        assertEquals(listOf(100L), byMessage.getValue("m1").calls.map { it.atMs })
        assertEquals(listOf(500L, 900L), byMessage.getValue("m2").calls.map { it.atMs })
        assertEquals(1, byMessage.getValue("m1").callCount)
        assertEquals(2, byMessage.getValue("m2").callCount)
    }

    @Test
    fun `title and suggestion rows in the window are dropped`() {
        val windows = UsageTurnViewFactory.windowsFor(listOf("m1" to 100L, "m2" to 500L))
        val records = listOf(
            row("main", 120L, input = 10, output = 1),
            row("title", 300L, purpose = UsagePurpose.TITLE, input = 999, output = 9),
            row("suggestion", 400L, purpose = UsagePurpose.SUGGESTION, input = 999, output = 9),
        )

        val view = UsageTurnViewFactory.map(windows, records).getValue("m1")

        assertEquals(1, view.callCount)
        assertEquals(10L, view.inputTokens)
    }

    @Test
    fun `a window with no rows is absent from the map`() {
        val windows = UsageTurnViewFactory.windowsFor(listOf("m1" to 100L, "m2" to 500L))

        assertTrue(UsageTurnViewFactory.map(windows, emptyList()).isEmpty())
        assertNull(UsageTurnViewFactory.turnUsageFor(emptyList(), windows[0].second))
    }
}
