package io.github.deadeyebarb.tonearm.connect

import android.content.Context
import android.os.Build
import io.github.deadeyebarb.tonearm.integrations.IntegrationNotConfiguredException
import io.github.deadeyebarb.tonearm.integrations.IntegrationsService
import io.github.deadeyebarb.tonearm.media.QueueSong
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic
import java.util.UUID

/**
 * The phone's side of Tonearm Connect: finds the other Tonearm apps (the desktop player) through
 * the Tonearm Connect plugin in Lidarr, and remote-controls them.
 */
class PhoneConnect(context: Context, private val integrations: IntegrationsService, private val client: ConnectClient) {
    private val prefs = context.getSharedPreferences("connect", Context.MODE_PRIVATE)

    /** Stable per install, so the desktop sees commands come from the same device. */
    val deviceId: String = prefs.getString(KEY_ID, null) ?: ("phone-" + UUID.randomUUID()).also { prefs.edit().putString(KEY_ID, it).apply() }
    val deviceName: String = listOf(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL).distinct().joinToString(" ")

    /** Other devices, online ones first. */
    suspend fun devices(): List<ConnectDevice> {
        val (config, key) = lidarr()
        return client.devices(config, key).filter { it.state.id != deviceId }.sortedByDescending { it.online }
    }

    suspend fun send(target: String, command: ConnectCommand) {
        val (config, key) = lidarr()
        client.send(config, key, deviceId, target, command)
    }

    private suspend fun lidarr() = try {
        integrations.requireLidarr()
    } catch (_: IntegrationNotConfiguredException) {
        throw IntegrationNotConfiguredException("Tonearm Connect runs through Lidarr: connect Lidarr in Settings first")
    }

    private companion object {
        const val KEY_ID = "device_id"
    }
}

fun QueueSong.toConnect(): ConnectSong = ConnectSong(
    id = song.id,
    source = if (YouTubeMusic.isYouTube(serverId)) ConnectSong.YOUTUBE else ConnectSong.SERVER,
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
