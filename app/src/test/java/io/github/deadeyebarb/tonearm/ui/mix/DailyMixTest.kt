package io.github.deadeyebarb.tonearm.ui.mix

import io.github.deadeyebarb.tonearm.connect.DailyMixes
import io.github.deadeyebarb.tonearm.connect.Mix
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DailyMixTest {
    private val today = DailyMixes("2026-10-09", mixes = (1..3).map { Mix("daily-2026-10-09-$it", title = "Daily Mix $it") })

    @Test
    fun `the day changes at 4 in the morning, as on the server`() {
        assertEquals("2026-10-08", dailyMixDay(LocalDateTime.parse("2026-10-09T01:00")))
        assertEquals("2026-10-08", dailyMixDay(LocalDateTime.parse("2026-10-09T03:59")))
        assertEquals("2026-10-09", dailyMixDay(LocalDateTime.parse("2026-10-09T04:00")))
        assertEquals("2026-10-09", dailyMixDay(LocalDateTime.parse("2026-10-09T23:30")))
    }

    @Test
    fun `today's mix opens as it is`() {
        assertEquals("Daily Mix 2", dailyMix(today, "daily-2026-10-09-2")?.title)
    }

    @Test
    fun `an earlier day's mix opens today's at its place`() {
        assertEquals("daily-2026-10-09-2", dailyMix(today, "daily-2026-10-08-2")?.id)
        assertEquals("daily-2026-10-09-1", dailyMix(today, "daily-2026-10-01-1")?.id)
        // Today has fewer.
        assertNull(dailyMix(today, "daily-2026-10-08-5"))
    }

    @Test
    fun `a mix of today's that isn't there, or an id that isn't a daily mix's, opens none`() {
        assertNull(dailyMix(today, "daily-2026-10-09-4"))
        assertNull(dailyMix(today, "mix-2"))
        assertNull(dailyMix(DailyMixes("2026-10-09"), "daily-2026-10-08-1"))
    }
}
