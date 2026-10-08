package io.github.deadeyebarb.tonearm.integrations

import io.github.deadeyebarb.tonearm.data.LidarrConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class LidarrImage(val coverType: String? = null, val url: String? = null, val remoteUrl: String? = null)

@Serializable
data class LidarrStatistics(
    val albumCount: Int = 0,
    val trackFileCount: Int = 0,
    /** Tracks of monitored albums. */
    val trackCount: Int = 0,
    val totalTrackCount: Int = 0,
    val percentOfTracks: Double = 0.0,
)

@Serializable
data class LidarrArtist(
    val id: Int = 0,
    val artistName: String = "",
    val foreignArtistId: String? = null,
    val disambiguation: String? = null,
    val overview: String? = null,
    val artistType: String? = null,
    val genres: List<String> = emptyList(),
    val images: List<LidarrImage> = emptyList(),
    val remotePoster: String? = null,
    val statistics: LidarrStatistics? = null,
    val monitored: Boolean = false,
    /** ISO-8601 time the artist was added to Lidarr. */
    val added: String? = null,
    val tags: List<Int> = emptyList(),
    /** Where its files are (Lidarr's root folder and the artist's folder). */
    val path: String? = null,
)

@Serializable
data class LidarrAlbum(
    val id: Int = 0,
    val title: String = "",
    val artistId: Int = 0,
    val monitored: Boolean = false,
    val foreignAlbumId: String? = null,
    val albumType: String? = null,
    /** Compilation, Live, Soundtrack… (Lidarr sends names or objects with a name). */
    val secondaryTypes: List<JsonElement> = emptyList(),
    val releaseDate: String? = null,
    val overview: String? = null,
    val images: List<LidarrImage> = emptyList(),
    val remoteCover: String? = null,
    val artist: LidarrArtist? = null,
)

@Serializable
data class LidarrTrack(
    val id: Int = 0,
    val title: String = "",
    val albumId: Int = 0,
    val artistId: Int = 0,
    /** The number on its disc ("1", sometimes "A1" for vinyl). */
    val trackNumber: String = "",
    val mediumNumber: Int = 0,
    val hasFile: Boolean = false,
    val trackFileId: Int = 0,
)

@Serializable
data class LidarrRootFolder(
    val id: Int = 0,
    val path: String = "",
    val name: String? = null,
    val defaultQualityProfileId: Int? = null,
    val defaultMetadataProfileId: Int? = null,
    val freeSpace: Long? = null,
) {
    /** A user's own folder for their weekly picks, which the Tonearm server names so. */
    val isPicks: Boolean get() = name?.startsWith(PICKS_NAME) == true

    companion object {
        /** How the Tonearm server names the root folders it makes for users' picks ("Tonearm picks: alice"). */
        const val PICKS_NAME = "Tonearm picks:"
    }
}

@Serializable
data class LidarrProfile(val id: Int = 0, val name: String = "")

@Serializable
data class LidarrStatus(val appName: String? = null, val instanceName: String? = null, val version: String? = null)

@Serializable
data class LidarrQueueItem(
    val id: Int = 0,
    val title: String? = null,
    val status: String? = null,
    val trackedDownloadState: String? = null,
    val trackedDownloadStatus: String? = null,
    val size: Double? = null,
    val sizeleft: Double? = null,
    val timeleft: String? = null,
    val errorMessage: String? = null,
    val artistId: Int? = null,
    val artist: LidarrArtist? = null,
    val album: LidarrAlbum? = null,
) {
    val progress: Float
        get() = if (size != null && size > 0 && sizeleft != null) (1 - sizeleft / size).toFloat().coerceIn(0f, 1f) else 0f
}

/** A setting of an import list (or other provider): `fields` in Lidarr's JSON. */
@Serializable
data class LidarrField(val name: String = "", val value: JsonElement? = null)

@Serializable
data class LidarrImportList(
    val id: Int = 0,
    val name: String = "",
    val implementation: String = "",
    val enableAutomaticAdd: Boolean = false,
    val shouldMonitor: String? = null,
    val tags: List<Int> = emptyList(),
    val fields: List<LidarrField> = emptyList(),
) {
    fun field(name: String): String? = (fields.firstOrNull { it.name == name }?.value as? JsonPrimitive)?.contentOrNull
}

@Serializable
private class LidarrWantedPage(val records: List<LidarrAlbum> = emptyList())

@Serializable
private class LidarrQueuePage(val records: List<LidarrQueueItem> = emptyList(), val totalRecords: Int = 0)

/** One search hit: an artist or an album, with Lidarr's own JSON kept for adding it. */
data class LidarrCandidate(
    val isAlbum: Boolean,
    val title: String,
    val subtitle: String,
    val imageUrl: String?,
    val foreignId: String,
    /** Already in Lidarr (monitored or not). */
    val inLidarr: Boolean,
    val overview: String?,
    val raw: JsonObject,
)

/** The add-time settings resolved against what the Lidarr instance actually has. */
data class LidarrAddDefaults(
    val rootFolderPath: String,
    val qualityProfileId: Int,
    val metadataProfileId: Int,
    val monitor: String,
    val search: Boolean,
)

/** Lidarr API v1 client. Auth is the `X-Api-Key` header. */
class LidarrClient(private val http: IntegrationHttp, private val json: Json) {
    private fun url(config: LidarrConfig, endpoint: String, params: List<Pair<String, Any?>> = emptyList()) =
        IntegrationHttp.url(config.url, "api/v1/$endpoint", params)

    private fun headers(key: String) = mapOf("X-Api-Key" to key, "Accept" to "application/json")

    private suspend fun get(config: LidarrConfig, key: String, endpoint: String, params: List<Pair<String, Any?>> = emptyList()) =
        http.get(url(config, endpoint, params), config.useServerTls, headers(key), service = "Lidarr")

    suspend fun status(config: LidarrConfig, key: String): LidarrStatus =
        json.decodeFromString(LidarrStatus.serializer(), get(config, key, "system/status"))

    suspend fun rootFolders(config: LidarrConfig, key: String): List<LidarrRootFolder> =
        json.decodeFromString(ListSerializer(LidarrRootFolder.serializer()), get(config, key, "rootfolder"))

    suspend fun qualityProfiles(config: LidarrConfig, key: String): List<LidarrProfile> =
        json.decodeFromString(ListSerializer(LidarrProfile.serializer()), get(config, key, "qualityprofile"))

    suspend fun metadataProfiles(config: LidarrConfig, key: String): List<LidarrProfile> =
        json.decodeFromString(ListSerializer(LidarrProfile.serializer()), get(config, key, "metadataprofile"))

    suspend fun artists(config: LidarrConfig, key: String): List<LidarrArtist> =
        json.decodeFromString(ListSerializer(LidarrArtist.serializer()), get(config, key, "artist"))

    /** Import lists with their raw JSON, which an update has to send back whole. */
    suspend fun importLists(config: LidarrConfig, key: String): List<Pair<LidarrImportList, JsonObject>> =
        json.decodeFromString(ListSerializer(JsonObject.serializer()), get(config, key, "importlist"))
            .map { json.decodeFromJsonElement<LidarrImportList>(it) to it }

    /** Saves an import list without Lidarr's connection test (which, for an AI list, would ask the model). */
    /** Artists and albums matching [term], best matches first. */
    suspend fun search(config: LidarrConfig, key: String, term: String): List<LidarrCandidate> {
        val existing = runCatching { artists(config, key).mapNotNull { it.foreignArtistId }.toSet() }.getOrDefault(emptySet())
        val results = json.decodeFromString(ListSerializer(JsonObject.serializer()), get(config, key, "search", listOf("term" to term)))
        return results.mapNotNull { result ->
            (result["artist"] as? JsonObject)?.let { artistCandidate(it, existing) }
                ?: (result["album"] as? JsonObject)?.let { albumCandidate(it, existing) }
        }
    }

    /** An album from Lidarr's album lookup ("artist album", or "lidarr:<release group id>"). */
    class AlbumHit(
        val candidate: LidarrCandidate,
        val artistName: String?,
        val lidarrId: Int,
        val monitored: Boolean,
        val artistId: Int = 0,
        /** The artist's MusicBrainz id, to find them in Lidarr when only they are there yet. */
        val artistForeignId: String? = null,
        /** Where Lidarr keeps the artist, when it has them. */
        val artistPath: String? = null,
    ) {
        val title get() = candidate.title
        val foreignId get() = candidate.foreignId
        val inLidarr get() = candidate.inLidarr
    }

    suspend fun lookupAlbum(config: LidarrConfig, key: String, term: String): List<AlbumHit> =
        json.decodeFromString(ListSerializer(JsonObject.serializer()), get(config, key, "album/lookup", listOf("term" to term))).map { raw ->
            val album = json.decodeFromJsonElement<LidarrAlbum>(raw)
            AlbumHit(albumCandidate(raw, emptySet()), album.artist?.artistName, album.id, raw["monitored"]?.jsonPrimitive?.booleanOrNull == true, album.artistId, album.artist?.foreignArtistId, album.artist?.path)
        }

    /** Monitors an album already in Lidarr and optionally searches for it. */
    suspend fun monitorAlbum(config: LidarrConfig, key: String, albumId: Int, search: Boolean) {
        val body = buildJsonObject {
            put("albumIds", JsonArray(listOf(JsonPrimitive(albumId))))
            put("monitored", JsonPrimitive(true))
        }
        http.send("PUT", url(config, "album/monitor"), body.toString(), config.useServerTls, headers(key), service = "Lidarr")
        if (search) {
            val command = buildJsonObject {
                put("name", JsonPrimitive("AlbumSearch"))
                put("albumIds", JsonArray(listOf(JsonPrimitive(albumId))))
            }
            http.postJson(url(config, "command"), command.toString(), config.useServerTls, headers(key), service = "Lidarr")
        }
    }

    /** Every album Lidarr knows (monitored or not), or one artist's. */
    suspend fun albums(config: LidarrConfig, key: String, artistId: Int? = null): List<LidarrAlbum> =
        json.decodeFromString(ListSerializer(LidarrAlbum.serializer()), get(config, key, "album", listOfNotNull(artistId?.let { "artistId" to it })))

    /** The tracks Lidarr knows for an artist's albums (from its metadata, whether or not they're monitored). */
    suspend fun tracks(config: LidarrConfig, key: String, artistId: Int): List<LidarrTrack> =
        json.decodeFromString(ListSerializer(LidarrTrack.serializer()), get(config, key, "track", listOf("artistId" to artistId)))

    /** One album's tracks, with the file each has. */
    suspend fun albumTracks(config: LidarrConfig, key: String, albumId: Int): List<LidarrTrack> =
        json.decodeFromString(ListSerializer(LidarrTrack.serializer()), get(config, key, "track", listOf("albumId" to albumId)))

    /** Deletes one track's file from disk. */
    suspend fun deleteTrackFile(config: LidarrConfig, key: String, trackFileId: Int) {
        http.send("DELETE", url(config, "trackfile/$trackFileId"), "", config.useServerTls, headers(key), service = "Lidarr")
    }

    /** Stops watching an album, so Lidarr doesn't fetch what's missing from it again. */
    suspend fun unmonitorAlbum(config: LidarrConfig, key: String, albumId: Int) {
        val body = buildJsonObject {
            put("albumIds", JsonArray(listOf(JsonPrimitive(albumId))))
            put("monitored", JsonPrimitive(false))
        }
        http.send("PUT", url(config, "album/monitor"), body.toString(), config.useServerTls, headers(key), service = "Lidarr")
    }

    /** Removes an artist (with its files unless told otherwise); an exclusion keeps import lists from adding it again. */
    suspend fun deleteArtist(config: LidarrConfig, key: String, artistId: Int, deleteFiles: Boolean = true, exclude: Boolean = true) {
        val url = url(config, "artist/$artistId", listOf("deleteFiles" to deleteFiles, "addImportListExclusion" to exclude))
        http.send("DELETE", url, "", config.useServerTls, headers(key), service = "Lidarr")
    }

    suspend fun deleteAlbum(config: LidarrConfig, key: String, albumId: Int, deleteFiles: Boolean = true, exclude: Boolean = true) {
        val url = url(config, "album/$albumId", listOf("deleteFiles" to deleteFiles, "addImportListExclusion" to exclude))
        http.send("DELETE", url, "", config.useServerTls, headers(key), service = "Lidarr")
    }

    suspend fun deleteImportList(config: LidarrConfig, key: String, id: Int) {
        http.send("DELETE", url(config, "importlist/$id"), "", config.useServerTls, headers(key), service = "Lidarr")
    }

    /** Artist-only lookup, for requesting a recommended artist by name. */
    suspend fun lookupArtist(config: LidarrConfig, key: String, name: String): List<LidarrCandidate> {
        val existing = runCatching { artists(config, key).mapNotNull { it.foreignArtistId }.toSet() }.getOrDefault(emptySet())
        return json.decodeFromString(ListSerializer(JsonObject.serializer()), get(config, key, "artist/lookup", listOf("term" to name)))
            .map { artistCandidate(it, existing) }
    }

    private fun artistCandidate(raw: JsonObject, existing: Set<String>): LidarrCandidate {
        val artist = json.decodeFromJsonElement<LidarrArtist>(raw)
        val foreignId = artist.foreignArtistId.orEmpty()
        return LidarrCandidate(
            isAlbum = false,
            title = artist.artistName,
            subtitle = listOfNotNull("Artist", artist.artistType, artist.disambiguation?.takeIf { it.isNotBlank() }).joinToString(" · "),
            imageUrl = artist.remotePoster ?: artist.images.bestImage("poster", "fanart"),
            foreignId = foreignId,
            inLidarr = artist.id > 0 || foreignId in existing,
            overview = artist.overview,
            raw = raw,
        )
    }

    private fun albumCandidate(raw: JsonObject, existing: Set<String>): LidarrCandidate {
        val album = json.decodeFromJsonElement<LidarrAlbum>(raw)
        val year = album.releaseDate?.take(4)
        return LidarrCandidate(
            isAlbum = true,
            title = album.title,
            subtitle = listOfNotNull(album.albumType ?: "Album", year, album.artist?.artistName).joinToString(" · "),
            imageUrl = album.remoteCover ?: album.images.bestImage("cover"),
            foreignId = album.foreignAlbumId.orEmpty(),
            inLidarr = album.id > 0,
            overview = album.overview,
            raw = raw,
        )
    }

    private fun List<LidarrImage>.bestImage(vararg types: String): String? =
        types.firstNotNullOfOrNull { type -> firstOrNull { it.coverType.equals(type, true) }?.remoteUrl }
            ?: firstNotNullOfOrNull { it.remoteUrl }

    /** Root folder, profiles and options for a new request: the saved choice, else Lidarr's defaults. */
    suspend fun resolveDefaults(config: LidarrConfig, key: String): LidarrAddDefaults {
        // Someone's own picks folder is never where everyone's requests go.
        val roots = rootFolders(config, key).filterNot { it.isPicks }
        val root = roots.firstOrNull { it.path == config.rootFolderPath } ?: roots.firstOrNull()
            ?: throw IntegrationHttpException(0, "Lidarr has no root folder yet. Add one in Lidarr → Settings → Media Management.")
        val quality = config.qualityProfileId ?: root.defaultQualityProfileId ?: qualityProfiles(config, key).firstOrNull()?.id
            ?: throw IntegrationHttpException(0, "Lidarr has no quality profile")
        val metadata = config.metadataProfileId ?: root.defaultMetadataProfileId ?: metadataProfiles(config, key).firstOrNull()?.id
            ?: throw IntegrationHttpException(0, "Lidarr has no metadata profile")
        return LidarrAddDefaults(root.path, quality, metadata, config.monitor, config.searchOnAdd)
    }

    /** Adds an artist from a lookup result; Lidarr then monitors and (optionally) searches its albums. */
    suspend fun addArtist(config: LidarrConfig, key: String, candidate: LidarrCandidate, defaults: LidarrAddDefaults): LidarrArtist {
        val body = artistBody(candidate.raw, defaults, monitor = defaults.monitor, search = defaults.search)
        val response = http.postJson(url(config, "artist"), body.toString(), config.useServerTls, headers(key), service = "Lidarr")
        return json.decodeFromString(LidarrArtist.serializer(), response)
    }

    /** Adds one album. Its artist is added too if needed, but only this album is monitored. */
    suspend fun addAlbum(config: LidarrConfig, key: String, candidate: LidarrCandidate, defaults: LidarrAddDefaults): LidarrAlbum {
        val artistRaw = candidate.raw["artist"] as? JsonObject ?: JsonObject(emptyMap())
        val body = JsonObject(
            candidate.raw + mapOf(
                "monitored" to JsonPrimitive(true),
                "addOptions" to buildJsonObject {
                    put("searchForNewAlbum", JsonPrimitive(defaults.search))
                },
                "artist" to artistBody(artistRaw, defaults, monitor = "none", search = false, monitorNewItems = "none"),
            ),
        )
        val response = http.postJson(url(config, "album"), body.toString(), config.useServerTls, headers(key), service = "Lidarr")
        return json.decodeFromString(LidarrAlbum.serializer(), response)
    }

    private fun artistBody(
        raw: JsonObject,
        defaults: LidarrAddDefaults,
        monitor: String,
        search: Boolean,
        monitorNewItems: String = if (monitor == "none") "none" else "all",
    ) = JsonObject(
        raw + mapOf(
            "qualityProfileId" to JsonPrimitive(defaults.qualityProfileId),
            "metadataProfileId" to JsonPrimitive(defaults.metadataProfileId),
            "rootFolderPath" to JsonPrimitive(defaults.rootFolderPath),
            "monitored" to JsonPrimitive(true),
            "monitorNewItems" to JsonPrimitive(monitorNewItems),
            "addOptions" to buildJsonObject {
                put("monitor", JsonPrimitive(monitor))
                put("searchForMissingAlbums", JsonPrimitive(search))
            },
        ),
    )

    /** Monitored albums Lidarr doesn't have yet (what it's looking for). */
    suspend fun wanted(config: LidarrConfig, key: String, pageSize: Int = 1000): List<LidarrAlbum> {
        val body = get(config, key, "wanted/missing", listOf("page" to 1, "pageSize" to pageSize, "monitored" to true, "includeArtist" to true))
        return json.decodeFromString(LidarrWantedPage.serializer(), body).records
    }

    suspend fun queue(config: LidarrConfig, key: String): List<LidarrQueueItem> {
        val body = get(config, key, "queue", listOf("page" to 1, "pageSize" to 50, "includeArtist" to true, "includeAlbum" to true))
        return json.decodeFromString(LidarrQueuePage.serializer(), body).records
    }

    companion object {
        /**
         * Lidarr's resized poster for an artist in its library. The API route works with the API key,
         * unlike `/MediaCover/…` (which wants a login cookie when Lidarr has authentication on).
         */
        fun posterUrl(config: LidarrConfig, artist: LidarrArtist): String? {
            val poster = artist.images.firstOrNull { it.coverType.equals("poster", true) } ?: return null
            if (artist.id <= 0) return null
            val lastWrite = poster.url?.substringAfter("lastWrite=", "")?.substringBefore('&')?.takeIf { it.isNotEmpty() }
            return IntegrationHttp.url(config.url, "api/v1/mediacover/artist/${artist.id}/poster-250.jpg", listOf("lastWrite" to lastWrite)).toString()
        }

        /** Whether [url] is Lidarr's cover API under [lidarrUrl], the only place its API key may go with an image request. */
        fun isCoverUrl(lidarrUrl: String, url: okhttp3.HttpUrl): Boolean {
            val covers = runCatching { IntegrationHttp.url(lidarrUrl, "api/v1/mediacover") }.getOrNull() ?: return false
            return url.scheme == covers.scheme && url.host == covers.host && url.port == covers.port &&
                url.encodedPath.startsWith(covers.encodedPath + "/")
        }

        /** Used by tests to read fields of a request body. */
        internal fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull
        internal fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.intOrNull
    }
}
