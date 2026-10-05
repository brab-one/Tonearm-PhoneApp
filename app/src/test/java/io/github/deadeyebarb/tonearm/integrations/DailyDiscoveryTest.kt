package io.github.deadeyebarb.tonearm.integrations

import io.github.deadeyebarb.tonearm.subsonic.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class DailyDiscoveryTest {
    @Test
    fun `interleave alternates between sources`() {
        val mixed = DailyDiscovery.interleave(listOf(listOf("a1", "a2", "a3"), listOf("b1"), listOf("c1", "c2")))
        assertEquals(listOf("a1", "b1", "c1", "a2", "c2", "a3"), mixed)
    }

    @Test
    fun `artists already requested are not requested again`() {
        val mix = DailyMix(
            date = "2026-10-04", serverId = "s",
            songs = listOf(Song(id = "1", title = "x")),
            missing = listOf(DailyMissing("Neon Cascade", "r"), DailyMissing("Vector Choir", "r")),
            requested = listOf(Recommender.normalize("neon cascade")),
        )
        assertEquals(listOf("Vector Choir"), mix.toRequest.map { it.name })
        assertEquals("s", mix.entries.single().serverId)
    }
}
