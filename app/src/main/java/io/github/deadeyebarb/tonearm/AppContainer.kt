package io.github.deadeyebarb.tonearm

import android.app.Application
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import io.github.deadeyebarb.tonearm.connect.ConnectClient
import io.github.deadeyebarb.tonearm.connect.ConnectRouter
import io.github.deadeyebarb.tonearm.connect.PhoneConnect
import io.github.deadeyebarb.tonearm.data.IntegrationsRepository
import io.github.deadeyebarb.tonearm.data.Likes
import io.github.deadeyebarb.tonearm.data.Messages
import io.github.deadeyebarb.tonearm.data.SecretBox
import io.github.deadeyebarb.tonearm.data.ServerRepository
import io.github.deadeyebarb.tonearm.data.SettingsRepository
import io.github.deadeyebarb.tonearm.data.StarredStore
import io.github.deadeyebarb.tonearm.download.DownloadRepository
import io.github.deadeyebarb.tonearm.integrations.DailyDiscovery
import io.github.deadeyebarb.tonearm.integrations.FetchTracker
import io.github.deadeyebarb.tonearm.integrations.IntegrationHttp
import io.github.deadeyebarb.tonearm.integrations.IntegrationImageCalls
import io.github.deadeyebarb.tonearm.integrations.IntegrationsService
import io.github.deadeyebarb.tonearm.integrations.LidarrClient
import io.github.deadeyebarb.tonearm.integrations.Recommender
import io.github.deadeyebarb.tonearm.integrations.SongRequests
import io.github.deadeyebarb.tonearm.likes.Dislikes
import io.github.deadeyebarb.tonearm.likes.LikesSync
import io.github.deadeyebarb.tonearm.local.LocalMusic
import io.github.deadeyebarb.tonearm.media.LibraryVersions
import io.github.deadeyebarb.tonearm.media.MediaEngine
import io.github.deadeyebarb.tonearm.media.MediaItemFactory
import io.github.deadeyebarb.tonearm.net.NetworkMonitor
import io.github.deadeyebarb.tonearm.net.TlsFactory
import io.github.deadeyebarb.tonearm.net.UserAgentInterceptor
import io.github.deadeyebarb.tonearm.playback.AudioEffects
import io.github.deadeyebarb.tonearm.playback.PlayerConnection
import io.github.deadeyebarb.tonearm.playback.QueueContinuation
import io.github.deadeyebarb.tonearm.playback.QueueStore
import io.github.deadeyebarb.tonearm.playback.SleepTimer
import io.github.deadeyebarb.tonearm.playback.SpectrumAnalyzer
import io.github.deadeyebarb.tonearm.playback.VolumeMixer
import io.github.deadeyebarb.tonearm.subsonic.SessionManager
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import io.github.deadeyebarb.tonearm.weekly.WeeklyPicks
import io.github.deadeyebarb.tonearm.youtube.YouTubeCatalog
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath

/** Process-wide singletons, shared by the UI, the playback service and the download service. */
class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    val secrets = SecretBox()
    val settings = SettingsRepository(app, json, scope)
    val servers = ServerRepository(app, json, scope)
    val tls = TlsFactory(app, secrets)
    val network = NetworkMonitor(app)
    val messages = Messages()

    val baseHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(UserAgentInterceptor)
        .build()

    val sessions = SessionManager(servers, tls, secrets, baseHttpClient, scope)
    val api = SubsonicApi(sessions, json)
    val starred = StarredStore(api, sessions)

    private val integrationHttp = IntegrationHttp(baseHttpClient) { sessions.awaitActiveClient() }
    private val connectClient = ConnectClient(integrationHttp, json)
    /** The Tonearm server at the music server's address: Connect, Lidarr with its key, history and picks. */
    val tonearmServer = ConnectRouter(connectClient)
    /** Disliked songs and artists said no to, from the Tonearm server. */
    val dislikes = Dislikes(connectClient, tonearmServer) { sessions.active.value }

    /** A liked song isn't disliked anymore. */
    suspend fun undislike(song: Song) {
        if (dislikes.isDisliked(song.artist, song.title)) dislikes.set(song.artist.orEmpty(), song.title, song.album, false)
    }
    val integrations = IntegrationsService(
        IntegrationsRepository(app, json, scope),
        LidarrClient(integrationHttp, json),
        secrets,
        tonearmServer,
        sessions,
        scope,
    )
    val connect = PhoneConnect(app, sessions, integrations, connectClient, tonearmServer)
    val recommender = Recommender(api, sessions, connect, dislikes)
    val daily = DailyDiscovery(app, json, scope, api, sessions, integrations, recommender, dislikes)

    init {
        // What the desktop disliked shows up here within a few minutes.
        scope.launch {
            sessions.active.collectLatest {
                while (true) {
                    dislikes.refresh()
                    delay(5 * 60_000L)
                }
            }
        }
    }
    val mediaItems = MediaItemFactory(app)
    val queueStore = QueueStore(app, json, mediaItems)

    /** YouTube checks the User-Agent its URLs were issued for; the shared client would stamp Tonearm's on everything. */
    private val youtubeHttpClient = baseHttpClient.newBuilder().apply { interceptors().remove(UserAgentInterceptor) }.build()
    val youtube = YouTubeMusic(youtubeHttpClient)
    val catalog = YouTubeCatalog(youtube)
    val versions = LibraryVersions(api)
    val fetches = FetchTracker(LidarrClient(integrationHttp, json), api)
    val local = LocalMusic(app)
    val songRequests = SongRequests(LidarrClient(integrationHttp, json), ownPicks = { tonearmServer.server.value?.picksFolder })
    val likes = Likes(app, json, scope, api, sessions, starred, integrations, songRequests, LikesSync(connectClient, json), connect, settings, messages)
    val weekly = WeeklyPicks(api, LidarrClient(integrationHttp, json), connectClient, json, connect.deviceId, java.io.File(app.filesDir, "weekly-run.json"))
    val continuation = QueueContinuation(api, sessions, youtube, settings, dislikes)
    val media by lazy { MediaEngine(app, sessions, settings, network, youtube, youtubeHttpClient) }

    /** Touch first on the main thread: DownloadManager binds to the creating thread's looper. */
    val downloads by lazy { DownloadRepository(app, media, json, settings, scope) }

    val spectrum = SpectrumAnalyzer()
    val volume = VolumeMixer()
    val sleepTimer = SleepTimer(volume)
    val effects = AudioEffects(app)
    val player = PlayerConnection(app, mediaItems)

    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(app)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { IntegrationImageCalls(integrations, baseHttpClient, { sessions.active.value?.client }, sessions.callFactory) }))
            }
            .memoryCache { MemoryCache.Builder().maxSizePercent(app, 0.2).build() }
            .diskCache {
                DiskCache.Builder().directory(app.cacheDir.resolve("images").toOkioPath()).maxSizeBytes(300L * 1024 * 1024).build()
            }
            .crossfade(true)
            .build()
    }
}
