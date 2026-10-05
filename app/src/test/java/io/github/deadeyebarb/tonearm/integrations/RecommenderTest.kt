package io.github.deadeyebarb.tonearm.integrations

import io.github.deadeyebarb.tonearm.subsonic.Artist
import io.github.deadeyebarb.tonearm.subsonic.ArtistInfo
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommenderTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }

    // Shaped like Navidrome's getArtistInfo2 with includeNotPresent=true: artists you don't have get id "-1".
    private val navidromeInfo = """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","artistInfo2":{
        "similarArtist":[
          {"id":"ar-7","name":"Signal Garden","albumCount":1},
          {"id":"-1","name":"Neon Cascade"},
          {"id":"-1","name":"Kosmische Drift"},
          {"id":"-1","name":"Vector Choir"}
        ]}}}"""

    private val seed = Artist(id = "ar-1", name = "The Oscillators")
    private val garden = Artist(id = "ar-7", name = "Signal Garden")

    @Test
    fun `navidrome's -1 ids are artists you don't have`() {
        val info = SubsonicApi.parse(json, navidromeInfo).artistInfo2!!
        assertEquals(listOf(true, false, false, false), info.similarArtist.map { it.inLibrary })
        assertFalse(Artist(id = "").inLibrary)
    }

    @Test
    fun `similar artists split into library picks and missing ones without repeats`() {
        val info = SubsonicApi.parse(json, navidromeInfo).artistInfo2!!
        // Two seeds with the same similar artists: each artist must still appear once.
        val other = Artist(id = "ar-2", name = "Octave Club")
        val split = Recommender.splitSimilar(
            listOf(seed to info, other to info),
            byName = mapOf("oscillators" to seed, "signal garden" to garden, "octave club" to other),
            playedLately = emptySet(),
            skip = setOf("oscillators", "octave club"),
        )
        assertEquals(listOf("ar-7"), split.owned.map { it.artist.id })
        assertEquals(listOf("Neon Cascade", "Kosmische Drift", "Vector Choir"), split.missing.map { it.name })
        assertEquals("Like The Oscillators & Octave Club", split.missing.first().reason)
        assertTrue(split.any)
    }

    @Test
    fun `library picks are unique by id even when names differ`() {
        // A server that answers with the same library artist under two spellings.
        val info = ArtistInfo(similarArtist = listOf(Artist(id = "ar-7", name = "Signal Garden"), Artist(id = "ar-7", name = "Signal-Garden (band)")))
        val split = Recommender.splitSimilar(listOf(seed to info), byName = emptyMap(), playedLately = emptySet(), skip = emptySet())
        assertEquals(listOf("ar-7"), split.owned.map { it.artist.id })
    }

    @Test
    fun `recently played and skipped names are left out`() {
        val info = SubsonicApi.parse(json, navidromeInfo).artistInfo2!!
        val split = Recommender.splitSimilar(
            listOf(seed to info),
            byName = mapOf("signal garden" to garden),
            playedLately = setOf("signal garden"),
            skip = setOf("vector choir"),
        )
        assertTrue(split.owned.isEmpty())
        assertEquals(listOf("Neon Cascade", "Kosmische Drift"), split.missing.map { it.name })
    }
}
