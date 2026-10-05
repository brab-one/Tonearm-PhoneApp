package io.github.deadeyebarb.tonearm.media

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.github.deadeyebarb.tonearm.subsonic.Album
import io.github.deadeyebarb.tonearm.subsonic.Artist
import io.github.deadeyebarb.tonearm.subsonic.Playlist
import io.github.deadeyebarb.tonearm.subsonic.ReplayGainInfo
import io.github.deadeyebarb.tonearm.subsonic.Song

/** Keys for the song details we carry in [MediaMetadata.extras]. */
object SongExtras {
    const val SERVER = "tonearm.server"
    const val ID = "tonearm.id"
    const val ALBUM_ID = "tonearm.albumId"
    const val ARTIST_ID = "tonearm.artistId"
    const val COVER = "tonearm.cover"
    const val SUFFIX = "tonearm.suffix"
    const val CONTENT_TYPE = "tonearm.contentType"
    const val BIT_RATE = "tonearm.bitRate"
    const val BIT_DEPTH = "tonearm.bitDepth"
    const val SAMPLE_RATE = "tonearm.sampleRate"
    const val CHANNELS = "tonearm.channels"
    const val SIZE = "tonearm.size"
    const val STARRED = "tonearm.starred"
    const val TRACK_GAIN = "tonearm.trackGain"
    const val ALBUM_GAIN = "tonearm.albumGain"
    const val TRACK_PEAK = "tonearm.trackPeak"
    const val ALBUM_PEAK = "tonearm.albumPeak"
    const val FALLBACK_GAIN = "tonearm.fallbackGain"
}

/** A song plus the server it lives on — the unit the queue is made of. */
data class QueueSong(val serverId: String, val song: Song)

val MediaItem.serverId: String? get() = mediaMetadata.extras?.getString(SongExtras.SERVER)
val MediaItem.songId: String? get() = mediaMetadata.extras?.getString(SongExtras.ID)

/** Rebuilds the [Song] a media item was made from. */
fun MediaItem.toQueueSong(): QueueSong? {
    val extras = mediaMetadata.extras ?: return null
    val server = extras.getString(SongExtras.SERVER) ?: return null
    val id = extras.getString(SongExtras.ID) ?: return null
    val m = mediaMetadata
    fun int(key: String) = extras.getInt(key, -1).takeIf { it >= 0 }
    fun float(key: String) = extras.getFloat(key, Float.NaN).takeUnless { it.isNaN() }
    val gain = ReplayGainInfo(
        trackGain = float(SongExtras.TRACK_GAIN), albumGain = float(SongExtras.ALBUM_GAIN),
        trackPeak = float(SongExtras.TRACK_PEAK), albumPeak = float(SongExtras.ALBUM_PEAK),
        fallbackGain = float(SongExtras.FALLBACK_GAIN),
    )
    return QueueSong(
        server,
        Song(
            id = id,
            title = m.title?.toString().orEmpty(),
            artist = m.artist?.toString(),
            album = m.albumTitle?.toString(),
            track = m.trackNumber,
            discNumber = m.discNumber,
            year = m.releaseYear,
            genre = m.genre?.toString(),
            duration = m.durationMs?.let { (it / 1000).toInt() },
            albumId = extras.getString(SongExtras.ALBUM_ID),
            artistId = extras.getString(SongExtras.ARTIST_ID),
            coverArt = extras.getString(SongExtras.COVER),
            suffix = extras.getString(SongExtras.SUFFIX),
            contentType = extras.getString(SongExtras.CONTENT_TYPE),
            bitRate = int(SongExtras.BIT_RATE),
            bitDepth = int(SongExtras.BIT_DEPTH),
            samplingRate = int(SongExtras.SAMPLE_RATE),
            channelCount = int(SongExtras.CHANNELS),
            size = extras.getLong(SongExtras.SIZE, -1).takeIf { it >= 0 },
            starred = extras.getString(SongExtras.STARRED),
            replayGain = gain.takeIf { it != ReplayGainInfo() },
        ),
    )
}

class MediaItemFactory(context: Context) {
    private val artworkAuthority = "${context.packageName}.artwork"

    fun artworkUri(serverId: String, coverId: String?): Uri? = coverId?.let {
        Uri.Builder().scheme("content").authority(artworkAuthority)
            .appendPath("cover").appendPath(serverId).appendPath(it).build()
    }

    fun song(entry: QueueSong, context: String? = null): MediaItem = song(entry.serverId, entry.song, context)

    fun song(serverId: String, song: Song, context: String? = null): MediaItem {
        val extras = Bundle().apply {
            putString(SongExtras.SERVER, serverId)
            putString(SongExtras.ID, song.id)
            song.albumId?.let { putString(SongExtras.ALBUM_ID, it) }
            song.artistId?.let { putString(SongExtras.ARTIST_ID, it) }
            song.coverArt?.let { putString(SongExtras.COVER, it) }
            song.suffix?.let { putString(SongExtras.SUFFIX, it) }
            song.contentType?.let { putString(SongExtras.CONTENT_TYPE, it) }
            song.bitRate?.let { putInt(SongExtras.BIT_RATE, it) }
            song.bitDepth?.let { putInt(SongExtras.BIT_DEPTH, it) }
            song.samplingRate?.let { putInt(SongExtras.SAMPLE_RATE, it) }
            song.channelCount?.let { putInt(SongExtras.CHANNELS, it) }
            song.size?.let { putLong(SongExtras.SIZE, it) }
            song.starred?.let { putString(SongExtras.STARRED, it) }
            song.replayGain?.let { rg ->
                rg.trackGain?.let { putFloat(SongExtras.TRACK_GAIN, it) }
                rg.albumGain?.let { putFloat(SongExtras.ALBUM_GAIN, it) }
                rg.trackPeak?.let { putFloat(SongExtras.TRACK_PEAK, it) }
                rg.albumPeak?.let { putFloat(SongExtras.ALBUM_PEAK, it) }
                rg.fallbackGain?.let { putFloat(SongExtras.FALLBACK_GAIN, it) }
            }
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artistLabel.ifEmpty { null })
            .setAlbumTitle(song.album)
            .setAlbumArtist(song.artist)
            .setTrackNumber(song.track)
            .setDiscNumber(song.discNumber)
            .setReleaseYear(song.year)
            .setGenre(song.genre)
            .setDurationMs(song.duration?.let { it * 1000L })
            .setArtworkUri(artworkUri(serverId, song.coverArt))
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId(MediaIds.song(serverId, song.id, context))
            .setUri(SongUri.build(serverId, song.id))
            .setMediaMetadata(metadata)
            .build()
    }

    fun folder(
        id: String,
        title: String,
        subtitle: String? = null,
        artwork: Uri? = null,
        mediaType: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
        playable: Boolean = false,
        browsable: Boolean = true,
        extras: Bundle? = null,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtworkUri(artwork)
                .setIsBrowsable(browsable)
                .setIsPlayable(playable)
                .setMediaType(mediaType)
                .setExtras(extras)
                .build(),
        )
        .build()

    fun album(serverId: String, album: Album, extras: Bundle? = null) = folder(
        MediaIds.album(serverId, album.id), album.name, album.artistLabel,
        artworkUri(serverId, album.coverArt), MediaMetadata.MEDIA_TYPE_ALBUM, extras = extras,
    )

    fun playlist(serverId: String, playlist: Playlist) = folder(
        MediaIds.playlist(serverId, playlist.id), playlist.name, "${playlist.songCount} songs",
        artworkUri(serverId, playlist.coverArt), MediaMetadata.MEDIA_TYPE_PLAYLIST,
    )

    fun artist(serverId: String, artist: Artist, extras: Bundle? = null) = folder(
        MediaIds.artist(serverId, artist.id), artist.name, if (artist.albumCount == 1) "1 album" else "${artist.albumCount} albums",
        artworkUri(serverId, artist.coverArt), MediaMetadata.MEDIA_TYPE_ARTIST, extras = extras,
    )
}
