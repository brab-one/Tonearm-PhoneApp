package io.github.deadeyebarb.tonearm.subsonic

import io.github.deadeyebarb.tonearm.data.SecretBox
import io.github.deadeyebarb.tonearm.data.ServerConfig
import io.github.deadeyebarb.tonearm.data.ServerRepository
import io.github.deadeyebarb.tonearm.net.TlsFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap


/** Caches one [ServerSession] per server, rebuilding it whenever the server's settings change. */
class SessionManager(
    private val servers: ServerRepository,
    private val tls: TlsFactory,
    private val secrets: SecretBox,
    private val baseClient: OkHttpClient,
    scope: CoroutineScope,
) : ActiveServer {
    private val cache = ConcurrentHashMap<String, ServerSession>()

    val active: StateFlow<ServerSession?> = servers.state
        .map { list -> list?.active?.let(::sessionFor) }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun sessionFor(config: ServerConfig): ServerSession =
        cache[config.id]?.takeIf { it.config == config } ?: build(config).also { cache[config.id] = it }

    /** Builds a session without caching it, e.g. to test settings before saving them. */
    fun build(config: ServerConfig): ServerSession {
        val secret = secrets.decrypt(config.secretEnc).orEmpty()
        val client = try {
            baseClient.newBuilder().also { tls.configure(it, config) }.build()
        } catch (e: Exception) {
            // Keep the session usable for display, but make every request explain the TLS problem.
            val message = "Client certificate problem: ${e.message}"
            baseClient.newBuilder().addInterceptor { throw IOException(message, e) }.build()
        }
        return ServerSession(config, secret, client)
    }

    override suspend fun awaitActive(): ServerSession = servers.load().active?.let(::sessionFor) ?: throw NoServerException()

    /** The active server's HTTP client once settings are loaded, or null when no server is set up. */
    suspend fun awaitActiveClient(): OkHttpClient? = servers.load().active?.let { sessionFor(it).client }

    suspend fun session(serverId: String): ServerSession? =
        servers.load().servers.firstOrNull { it.id == serverId }?.let(::sessionFor)

    /** Non-blocking lookup for already-loaded servers (UI). */
    fun cached(serverId: String): ServerSession? = servers.find(serverId)?.let(::sessionFor)

    /** For callers on background threads that can't suspend (data sources, content providers). */
    fun sessionBlocking(serverId: String): ServerSession? =
        servers.find(serverId)?.let(::sessionFor) ?: runBlocking { session(serverId) }

    /** Routes each request through the client of the server it targets, so cover art gets mTLS too. */
    val callFactory: Call.Factory = Call.Factory { request -> clientFor(request.url).newCall(request) }

    private fun clientFor(url: HttpUrl): OkHttpClient =
        active.value?.takeIf { it.matches(url) }?.client
            ?: cache.values.firstOrNull { it.matches(url) }?.client
            ?: baseClient
}
