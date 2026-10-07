package io.github.deadeyebarb.tonearm.ui

import kotlinx.serialization.Serializable

@Serializable data object HomeRoute
@Serializable data object DiscoverRoute
@Serializable data object DailyRoute
@Serializable data object ConnectRoute
@Serializable data class RequestRoute(val query: String = "")
@Serializable data object LidarrSettingsRoute
@Serializable data object LibraryRoute
@Serializable data object SearchRoute
@Serializable data object DownloadsRoute
@Serializable data object NowPlayingRoute
@Serializable data object SettingsRoute
@Serializable data object ServersRoute
@Serializable data object EqualizerRoute
@Serializable data class ServerEditRoute(val serverId: String? = null, val welcome: Boolean = false)
@Serializable data class AlbumRoute(val id: String)
@Serializable data class ArtistRoute(val id: String)
@Serializable data class PlaylistRoute(val id: String)
@Serializable data class GenreRoute(val name: String)
@Serializable data class AlbumListRoute(val type: String)
@Serializable data class YtArtistRoute(val url: String, val name: String, val imageUrl: String? = null, val subscribers: Long? = null)
@Serializable data class LocalAlbumRoute(val id: String)
/** "More like this" for a song, album, artist or playlist: [label] to show, [artist] for similar artists, the song (if any) for similar songs. */
@Serializable data class MoreLikeRoute(
    val label: String,
    val artist: String? = null,
    val artistId: String? = null,
    val aiSeed: String,
    val songServerId: String? = null,
    val songId: String? = null,
    val songTitle: String? = null,
    val songArtist: String? = null,
    val songDuration: Int? = null,
    /** A cover id on the music server, or an image URL. */
    val cover: String? = null,
)
@Serializable data class YtAlbumRoute(val url: String, val title: String, val artist: String? = null, val imageUrl: String? = null)
