package io.github.deadeyebarb.tonearm.integrations

import io.github.deadeyebarb.tonearm.net.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** A MusicBrainz release group (what Lidarr calls an album); [secondary]: compilation, live, soundtrack… */
data class ReleaseGroup(val id: String, val title: String, val type: String?, val secondary: Boolean = false)

/**
 * Finds which album a song is on, so a like requests that album in Lidarr instead of the artist's
 * whole discography. Lidarr's albums are MusicBrainz release groups, so the ids line up.
 */
class MusicBrainz(
    private val client: OkHttpClient,
    private val json: Json,
    private val baseUrl: String = "https://musicbrainz.org/",
) {
    private val gate = Mutex()
    private var last = 0L

    @Serializable
    private class RecordingPage(val recordings: List<Recording> = emptyList())

    @Serializable
    private class Recording(
        val id: String = "",
        val title: String = "",
        val score: Int = 0,
        val length: Long? = null,
        @SerialName("artist-credit") val artistCredit: List<Credit> = emptyList(),
        val releases: List<Release> = emptyList(),
    )

    @Serializable
    private class Credit(val name: String = "")

    @Serializable
    private class Release(
        val status: String? = null,
        val date: String? = null,
        @SerialName("release-group") val group: Group? = null,
    )

    @Serializable
    private class Group(
        val id: String = "",
        val title: String = "",
        @SerialName("primary-type") val primaryType: String? = null,
        @SerialName("secondary-types") val secondaryTypes: List<String> = emptyList(),
    )

    /**
     * The album [ref] first came out on: a studio album if there is one, else an EP or single. With the
     * artist's MusicBrainz id ([artistId], Lidarr's foreignArtistId) credits like "A feat. B" match too.
     */
    suspend fun albumOf(ref: TrackRef, artistId: String? = null): ReleaseGroup? {
        val title = SongMatch.cleanTitle(ref.title)
        val artist = SongMatch.primaryArtist(SongMatch.cleanArtist(ref.artist))
        val who = if (artistId != null) "arid:$artistId" else "artist:\"${escape(artist)}\""
        val base = "recording:\"${escape(title)}\" AND $who AND status:official"
        val first = recordings(base, title, artist, artistId)
        val best = pick(first)
        if (best != null && best.type == "Album" && !best.secondary) return best
        // Popular songs have hundreds of releases; ask again for albums only before settling for a single.
        val albums = pick(first + recordings("$base AND primarytype:album", title, artist, artistId))
        return albums ?: best
    }

    private suspend fun recordings(query: String, title: String, artist: String, artistId: String?): List<Release> {
        val url = (baseUrl.trimEnd('/') + "/ws/2/recording").toHttpUrl().newBuilder()
            .addQueryParameter("query", query).addQueryParameter("fmt", "json").addQueryParameter("limit", "100").build()
        val body = politely {
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept", "application/json").build()
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) throw IOException("MusicBrainz: HTTP ${response.code}")
                response.body.string()
            }
        }
        return json.decodeFromString(RecordingPage.serializer(), body).recordings.filter { recording ->
            recording.score >= 80 &&
                Names.normalize(SongMatch.cleanTitle(recording.title)) == Names.normalize(title) &&
                (artistId != null || recording.artistCredit.any { Names.normalize(it.name) == Names.normalize(artist) })
        }.flatMap { it.releases }
    }

    private fun pick(releases: List<Release>): ReleaseGroup? {
        val official = releases.filter { it.group != null && (it.status == null || it.status == "Official") }
        fun rank(release: Release): Int {
            val group = release.group!!
            return when {
                group.secondaryTypes.isNotEmpty() -> 3 // Compilation, Live, Soundtrack, Remix…
                group.primaryType == "Album" -> 0
                group.primaryType == "EP" -> 1
                else -> 2
            }
        }
        val best = official.minWithOrNull(compareBy<Release> { rank(it) }.thenBy { it.date?.takeIf { d -> d.isNotBlank() } ?: "9999" })
            ?: return null
        return best.group!!.let { ReleaseGroup(it.id, it.title, it.primaryType, it.secondaryTypes.isNotEmpty()) }
    }

    /** MusicBrainz allows one request per second per client. */
    private suspend fun <T> politely(block: suspend () -> T): T = gate.withLock {
        val wait = last + 1_100 - System.currentTimeMillis()
        if (wait > 0) kotlinx.coroutines.delay(wait)
        try {
            withContext(Dispatchers.IO) { block() }
        } finally {
            last = System.currentTimeMillis()
        }
    }

    private fun escape(text: String) = text.replace(Regex("""([+\-!(){}\[\]^"~*?:\\/]|&&|\|\|)"""), "\\\\$1")

    companion object {
        const val USER_AGENT = "Tonearm/1.1 ( https://github.com/brab-one/Tonearm )"
    }
}
