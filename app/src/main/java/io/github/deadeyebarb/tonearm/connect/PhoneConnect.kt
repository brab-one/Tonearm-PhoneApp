package io.github.deadeyebarb.tonearm.connect

import io.github.deadeyebarb.tonearm.local.LocalMusic
import android.content.Context
import android.os.Build
import io.github.deadeyebarb.tonearm.integrations.IntegrationNotConfiguredException
import io.github.deadeyebarb.tonearm.integrations.IntegrationsService
import io.github.deadeyebarb.tonearm.media.QueueSong
import io.github.deadeyebarb.tonearm.subsonic.NoServerException
import io.github.deadeyebarb.tonearm.subsonic.SessionManager
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic
import java.util.UUID

/**
 * The phone's side of Tonearm Connect: finds the other Tonearm apps (the desktop player) through the
 * Tonearm server next to the music server, and remote-controls them.
 */
class PhoneConnect(
    context: Context,
    private val sessions: SessionManager,
    private val integrations: IntegrationsService,
    private val client: ConnectClient,
    private val router: ConnectRouter,
) {
    private val prefs = context.getSharedPreferences("connect", Context.MODE_PRIVATE)

    /** Stable per install, so the desktop sees commands come from the same device. */
    val deviceId: String = prefs.getString(KEY_ID, null) ?: ("phone-" + UUID.randomUUID()).also { prefs.edit().putString(KEY_ID, it).apply() }
    val deviceName: String = listOf(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL).distinct().joinToString(" ")

    /** Other devices, online ones first. */
    suspend fun devices(): List<ConnectDevice> =
        client.devices(route()).filter { it.state.id != deviceId }.sortedByDescending { it.online }

    suspend fun send(target: String, command: ConnectCommand) {
        client.send(route(), deviceId, target, command)
    }

    /** Album suggestions from the Tonearm server's AI; [refresh] asks for new ones. */
    suspend fun aiPicks(refresh: Boolean = false, seed: String? = null): AiPicks {
        val session = sessions.active.value ?: sessions.awaitActive()
        return client.aiPicks(session, refresh, seed)
    }

    /** Artists you don't have that yours point to, from the Tonearm server; [refresh] makes new ones. */
    suspend fun discover(refresh: Boolean = false): DiscoveryPicks = client.discover(sessions.active.value ?: sessions.awaitActive(), refresh)

    suspend fun webSearch(query: String): WebSearch = client.webSearch(sessions.active.value ?: sessions.awaitActive(), query)

    suspend fun aiSearch(query: String): AiSearch = client.aiSearch(sessions.active.value ?: sessions.awaitActive(), query)

    /** Sends what was played to the Tonearm server's history; nothing happens without a server that keeps one. */
    suspend fun played(plays: List<Played>) {
        val session = sessions.active.value ?: sessions.awaitActive()
        if (router.refresh(session)?.history == true) client.played(session, plays)
    }

    /** The listening history on the Tonearm server: the last [days] (all of it for null). */
    suspend fun listening(days: Int?, artists: Int = 50, songs: Int = 0, recent: Int = 0): Listening {
        val session = sessions.active.value ?: sessions.awaitActive()
        if (router.refresh(session)?.history != true) {
            throw IntegrationNotConfiguredException("Recommendations come from the listening history on the Tonearm server (1.5 or later), and it isn't on your music server yet")
        }
        return client.listening(session, days, artists, songs, recent)
    }

    /**
     * The listening history on the Tonearm server from [from] up to [to] (ms; now for null), with top [albums] and the
     * [recent] plays [before] a time for the next page.
     */
    suspend fun listeningBetween(
        from: Long,
        to: Long?,
        artists: Int = 10,
        albums: Int = 10,
        songs: Int = 0,
        recent: Int = 100,
        before: Long? = null,
    ): Listening {
        val session = sessions.active.value ?: sessions.awaitActive()
        if (router.refresh(session)?.stats != true) {
            throw IntegrationNotConfiguredException("Listening stats come from the Tonearm server (1.7 or later), and it isn't on your music server yet")
        }
        return client.listeningBetween(session, from, to, artists, albums, songs, recent, before)
    }

    /** What another device (the desktop) played last, to continue here; null when there's none or the server doesn't keep it. */
    suspend fun resume(): Handoff? {
        val session = sessions.active.value ?: sessions.awaitActive()
        if (router.refresh(session)?.resume != true) return null
        return client.resume(session, deviceId)
    }

    /** "Not for me": the Tonearm server leaves [artist] (or just this [album]) out of the picks from now on. */
    suspend fun dismiss(artist: String, album: String? = null) = client.dismiss(sessions.active.value ?: sessions.awaitActive(), artist, album)

    /** Has the Tonearm server get the wanted album [albumId] (Lidarr's id) from YouTube Music now. */
    suspend fun youtubeNow(albumId: Int): YouTubeNow = client.youtubeNow(sessions.active.value ?: sessions.awaitActive(), albumId)

    /** Artists like [artist] (Deezer's related artists, through the Tonearm server), marked when you have them. */
    suspend fun similarArtists(artist: String): List<SimilarArtist> = client.similarArtists(sessions.active.value ?: sessions.awaitActive(), artist)

    /** The Tonearm server at the music server's address. */
    suspend fun route(): ConnectRoute {
        val session = sessions.active.value ?: try {
            sessions.awaitActive()
        } catch (_: NoServerException) {
            null
        }
        return router.route(session)
    }

    private companion object {
        const val KEY_ID = "device_id"
    }
}

fun QueueSong.toConnect(): ConnectSong = ConnectSong(
    id = song.id,
    source = when {
        YouTubeMusic.isYouTube(serverId) -> ConnectSong.YOUTUBE
        LocalMusic.isLocal(serverId) -> ConnectSong.LOCAL
        else -> ConnectSong.SERVER
    },
    title = song.title,
    artist = song.artistLabel.ifEmpty { null },
    album = song.album,
    albumId = song.albumId,
    artistId = song.artistId,
    coverArt = song.coverArt,
    duration = song.duration,
    suffix = song.suffix,
    bitRate = song.bitRate,
    bitDepth = song.bitDepth,
    samplingRate = song.samplingRate,
)

/** Back into a queue entry: server songs belong to [serverId] (the phone's server, the same as the sender's). */
fun ConnectSong.toQueueSong(serverId: String): QueueSong = QueueSong(
    if (source == ConnectSong.YOUTUBE) YouTubeMusic.SOURCE_ID else serverId,
    Song(
        id = id, title = title, artist = artist, album = album, albumId = albumId, artistId = artistId, coverArt = coverArt,
        duration = duration, suffix = suffix, bitRate = bitRate, bitDepth = bitDepth, samplingRate = samplingRate,
    ),
)

/** The cover's server for a Connect song: YouTube covers are URLs, the rest come from the music server. */
fun ConnectSong.coverServer(serverId: String?): String? = when (source) {
    ConnectSong.YOUTUBE -> YouTubeMusic.SOURCE_ID
    // A file on the computer: its cover is a path there.
    ConnectSong.LOCAL -> null
    else -> serverId
}
