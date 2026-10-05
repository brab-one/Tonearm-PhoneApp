package io.github.deadeyebarb.tonearm.ui

import kotlinx.serialization.Serializable

@Serializable data object HomeRoute
@Serializable data object DiscoverRoute
@Serializable data object DailyRoute
@Serializable data object BrainarrRoute
@Serializable data object ConnectRoute
@Serializable data class RequestRoute(val query: String = "")
@Serializable data object MalojaSettingsRoute
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
@Serializable data class YtAlbumRoute(val url: String, val title: String, val artist: String? = null, val imageUrl: String? = null)
