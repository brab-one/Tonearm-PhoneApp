package io.github.deadeyebarb.tonearm.integrations

import io.github.deadeyebarb.tonearm.integrations.ForYouKind.LIKED
import io.github.deadeyebarb.tonearm.integrations.ForYouKind.PICK
import io.github.deadeyebarb.tonearm.integrations.ForYouKind.REDISCOVER
import io.github.deadeyebarb.tonearm.subsonic.Album
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.subsonic.Starred
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForYouTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 9)
    private val now = Instant.parse("2026-10-09T12:00:00Z").toEpochMilli()
    private val day = 24 * 3_600_000L

    private fun iso(daysAgo: Long) = Instant.ofEpochMilli(now - daysAgo * day).toString()
    private fun album(id: String, artist: String = "Artist $id", name: String = "Album $id", plays: Long = 0, played: String? = null, starred: String? = null) =
        Album(id, name, artist, playCount = plays, played = played, starred = starred)

    private fun mix(picks: List<Pair<Album, Long>> = emptyList(), starred: Starred? = null, frequent: List<Album> = emptyList(), on: LocalDate = today) =
        ForYou.mix(picks, starred, frequent, on, now, zone)

    @Test
    fun `picks, likes and forgotten albums take turns, 4, 6 and 6 of them, 15 in all`() {
        val picks = (1..6).map { album("p$it") to now + 3 * day }
        val liked = Starred(album = (1..8).map { album("l$it", starred = iso(it.toLong())) })
        val forgotten = (1..8).map { album("r$it", plays = 10L + it, played = iso(120)) }
        val items = mix(picks, liked, forgotten)
        assertEquals(15, items.size)
        assertEquals(listOf(PICK, LIKED, REDISCOVER, PICK, LIKED, REDISCOVER), items.take(6).map { it.kind })
        assertEquals(4, items.count { it.kind == PICK })
        assertEquals(6, items.count { it.kind == LIKED })
        assertEquals(5, items.count { it.kind == REDISCOVER })
        // Newest like first.
        assertEquals(listOf("l1", "l2"), items.filter { it.kind == LIKED }.take(2).map { it.album.id })
    }

    @Test
    fun `an album shows once, also as another copy with another id`() {
        val pick = album("al1", "The Oscillators", "Hi-Res Tones")
        val starred = Starred(
            song = listOf(
                Song("al1-2", title = "Shimmer", album = "Hi-Res Tones", artist = "The Oscillators", albumId = "al1", starred = iso(1)),
                Song("al2-1", title = "Pulse", album = "CD Quality", artist = "Signal Garden", albumId = "al2", starred = iso(2)),
                Song("al2-2", title = "Drift", album = "CD Quality", artist = "Signal Garden", albumId = "al2", starred = iso(3)),
            ),
            album = listOf(album("pick-copy", "the oscillators", "Hi-Res Tones (Deluxe Edition)", starred = iso(1))),
        )
        val items = mix(listOf(pick to now + day), starred, listOf(album("al2", "Signal Garden", "CD Quality", plays = 9, played = iso(200))))
        assertEquals(listOf("al1" to PICK, "al2" to LIKED), items.map { it.album.id to it.kind })
        assertEquals("CD Quality", items[1].album.name)
    }

    @Test
    fun `at most two albums by one artist`() {
        val picks = (1..3).map { album("p$it", "Midnight Quartet") to now + day }
        val liked = Starred(album = listOf(album("l1", "Midnight Quartet feat. Someone", starred = iso(0)), album("l2", "Vector Choir", starred = iso(0))))
        val items = mix(picks, liked)
        assertEquals(listOf("p1", "l1", "l2"), items.map { it.album.id })
    }

    @Test
    fun `a discography liked in one go leaves room for the other likes`() {
        val discography = (1..6).map { album("d$it", "Midnight Quartet", starred = iso(1)) }
        val others = (1..5).map { album("l$it", starred = iso(10L + it)) }
        val items = mix(starred = Starred(album = discography + others))
        assertEquals(listOf("d1", "d2", "l1", "l2", "l3", "l4"), items.map { it.album.id })
    }

    @Test
    fun `forgotten albums of a favourite artist leave room for others`() {
        val favourite = (1..8).map { album("f$it", "Signal Garden", plays = 50L - it, played = iso(200)) }
        val others = (1..4).map { album("r$it", plays = 10L + it, played = iso(200)) }
        val items = mix(frequent = favourite + others)
        assertEquals(6, items.size)
        assertEquals(2, items.count { it.album.artist == "Signal Garden" })
    }

    @Test
    fun `old likes and liked songs without an album are left out`() {
        val starred = Starred(
            song = listOf(
                Song("s1", title = "No Album", artist = "A", starred = iso(1)),
                Song("s2", title = "Old", album = "Old Album", artist = "B", albumId = "old", starred = iso(61)),
                Song("s3", title = "Undated", album = "Undated", artist = "C", albumId = "undated"),
            ),
            album = listOf(album("x", starred = iso(59)), album("y", starred = "not a date")),
        )
        assertEquals(listOf("x"), mix(starred = starred).map { it.album.id })
    }

    @Test
    fun `forgotten albums were played at least 3 times, and not in 90 days`() {
        val frequent = listOf(
            album("twice", plays = 2, played = iso(200)),
            album("lately", plays = 30, played = iso(89)),
            album("never-dated", plays = 30),
            album("garbled", plays = 30, played = "yesterday"),
            album("forgotten", plays = 3, played = iso(91)),
        )
        assertEquals(listOf("forgotten"), mix(frequent = frequent).map { it.album.id })
    }

    @Test
    fun `forgotten albums stay the same all day and change the next`() {
        val frequent = (1..30).map { album("r$it", plays = 100L - it, played = iso(150)) }
        val morning = mix(frequent = frequent).map { it.album.id }
        assertEquals(6, morning.size)
        assertEquals(morning, mix(frequent = frequent).map { it.album.id })
        assertNotEquals(morning, mix(frequent = frequent, on = today.plusDays(1)).map { it.album.id })
    }

    @Test
    fun `captions say why`() {
        assertEquals("7 DAYS LEFT", ForYou.pickCaption(now + 7 * day, now))
        assertEquals("3 DAYS LEFT", ForYou.pickCaption(now + 2 * day + 1, now))
        assertEquals("LAST DAY", ForYou.pickCaption(now + 3_600_000, now))
        assertEquals("LAST DAY", ForYou.pickCaption(now - day, now))
        assertEquals("LIKED TODAY", ForYou.likedCaption(now, today, zone))
        assertEquals("LIKED YESTERDAY", ForYou.likedCaption(now - day, today, zone))
        assertEquals("LIKED 4 DAYS AGO", ForYou.likedCaption(now - 4 * day, today, zone))
        val march = Instant.parse("2026-03-14T20:00:00Z").toEpochMilli()
        assertEquals("14 PLAYS // LAST IN MAR", ForYou.rediscoverCaption(14, march, today, zone))
        assertEquals("14 PLAYS // LAST IN MAR 2025", ForYou.rediscoverCaption(14, march - 365 * day, today, zone))
        val items = mix(frequent = listOf(album("r", plays = 14, played = "2026-03-14T20:00:00.123+01:00")))
        assertEquals("14 PLAYS // LAST IN MAR", items.single().caption)
    }

    @Test
    fun `nothing to show gives an empty row`() {
        assertTrue(mix().isEmpty())
        assertTrue(mix(starred = Starred()).isEmpty())
    }
}
