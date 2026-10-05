package io.github.deadeyebarb.tonearm

import android.app.Application
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import io.github.deadeyebarb.tonearm.connect.ConnectClient
import io.github.deadeyebarb.tonearm.connect.PhoneConnect
import io.github.deadeyebarb.tonearm.data.IntegrationsRepository
import io.github.deadeyebarb.tonearm.data.Messages
import io.github.deadeyebarb.tonearm.data.SecretBox
import io.github.deadeyebarb.tonearm.data.ServerRepository
import io.github.deadeyebarb.tonearm.data.SettingsRepository
import io.github.deadeyebarb.tonearm.data.StarredStore
import io.github.deadeyebarb.tonearm.download.DownloadRepository
import io.github.deadeyebarb.tonearm.integrations.AutoRequest
import io.github.deadeyebarb.tonearm.integrations.BrainarrService
import io.github.deadeyebarb.tonearm.integrations.DailyDiscovery
import io.github.deadeyebarb.tonearm.integrations.IntegrationHttp
import io.github.deadeyebarb.tonearm.integrations.IntegrationImageCalls
import io.github.deadeyebarb.tonearm.integrations.IntegrationsService
import io.github.deadeyebarb.tonearm.integrations.LidarrClient
import io.github.deadeyebarb.tonearm.integrations.MalojaClient
import io.github.deadeyebarb.tonearm.integrations.Recommender
import io.github.deadeyebarb.tonearm.media.MediaEngine
import io.github.deadeyebarb.tonearm.media.MediaItemFactory
import io.github.deadeyebarb.tonearm.net.NetworkMonitor
import io.github.deadeyebarb.tonearm.net.TlsFactory
import io.github.deadeyebarb.tonearm.net.UserAgentInterceptor
import io.github.deadeyebarb.tonearm.playback.AudioEffects
import io.github.deadeyebarb.tonearm.playback.PlayerConnection
import io.github.deadeyebarb.tonearm.playback.QueueStore
import io.github.deadeyebarb.tonearm.playback.SleepTimer
import io.github.deadeyebarb.tonearm.playback.SpectrumAnalyzer
import io.github.deadeyebarb.tonearm.playback.VolumeMixer
import io.github.deadeyebarb.tonearm.subsonic.SessionManager
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.util.concurrent.TimeUnit

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
    val integrations = IntegrationsService(
        IntegrationsRepository(app, json, scope),
        MalojaClient(integrationHttp, json),
        LidarrClient(integrationHttp, json),
        secrets,
    )
    val recommender = Recommender(api, sessions, integrations)
    val brainarr = BrainarrService(app, json, scope, api, sessions, integrations)
    val autoRequest = AutoRequest(app, json, scope, integrations, settings, messages)
    val connect = PhoneConnect(app, integrations, ConnectClient(integrationHttp, json))
    val daily = DailyDiscovery(app, json, scope, api, sessions, integrations, recommender, brainarr)
    val mediaItems = MediaItemFactory(app)
    val queueStore = QueueStore(app, json, mediaItems)

    /** YouTube checks the User-Agent its URLs were issued for; the shared client would stamp Tonearm's on everything. */
    private val youtubeHttpClient = baseHttpClient.newBuilder().apply { interceptors().remove(UserAgentInterceptor) }.build()
    val youtube = YouTubeMusic(youtubeHttpClient)
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
