package io.github.deadeyebarb.tonearm.subsonic

import io.github.deadeyebarb.tonearm.data.AuthMethod
import io.github.deadeyebarb.tonearm.data.ServerConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.SecureRandom

object SubsonicAuth {
    const val API_VERSION = "1.16.1"
    const val CLIENT_NAME = "Tonearm"
    private val random = SecureRandom()

    fun salt(): String = ByteArray(8).also(random::nextBytes).toHex()

    fun token(password: String, salt: String): String =
        MessageDigest.getInstance("MD5").digest((password + salt).encodeToByteArray()).toHex()

    fun hexPassword(password: String): String = "enc:" + password.encodeToByteArray().toHex()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}

/**
 * A configured server with its decrypted secret and its own TLS-configured HTTP client. Requests through
 * that client to the Tonearm server under the same address (`/connect-tonearm/…`) get this login added.
 */
class ServerSession(val config: ServerConfig, private val secret: String, client: OkHttpClient) {
    val id: String get() = config.id
    private val base: HttpUrl = config.baseUrl.toHttpUrl()
    private val tonearmPath = base.encodedPath.trimEnd('/') + "/" + CONNECT_PATH + "/"
    /** One salt for a few minutes, so the Tonearm server can recognise the login without asking Navidrome each time. */
    @Volatile private var tonearmSalt: Pair<String, Long>? = null

    val client: OkHttpClient = client.newBuilder().addInterceptor { chain ->
        val request = chain.request()
        val url = request.url
        if (!matches(url) || !url.encodedPath.startsWith(tonearmPath) || url.queryParameter("u") != null || url.queryParameter("apiKey") != null) {
            chain.proceed(request)
        } else {
            chain.proceed(request.newBuilder().url(signed(url.newBuilder(), emptyList(), tonearmSalt())).build())
        }
    }.build()

    fun matches(url: HttpUrl) = url.host == base.host && url.port == base.port

    fun apiUrl(method: String, params: List<Pair<String, Any?>> = emptyList()): HttpUrl =
        signed(base.newBuilder().addPathSegment("rest").addPathSegment("$method.view"), params)

    /** Tonearm Connect on the Tonearm server, which the proxy serves under the music server's address. [client] signs it. */
    fun connectUrl(op: String, params: List<Pair<String, Any?>> = emptyList()): HttpUrl =
        base.newBuilder().addPathSegment(CONNECT_PATH).addPathSegment("api").addPathSegment(op).apply {
            for ((key, value) in params) if (value != null) addQueryParameter(key, value.toString())
        }.build()

    /** Where the Tonearm server passes [service] ("lidarr") on with its own key. */
    fun tonearmServiceUrl(service: String): String = base.newBuilder().addPathSegment(CONNECT_PATH).addPathSegment(service).build().toString()

    private fun tonearmSalt(): String {
        val now = System.currentTimeMillis()
        tonearmSalt?.takeIf { now - it.second < SALT_REUSE_MS }?.let { return it.first }
        return SubsonicAuth.salt().also { tonearmSalt = it to now }
    }

    private fun signed(builder: HttpUrl.Builder, params: List<Pair<String, Any?>>, salt: String = SubsonicAuth.salt()): HttpUrl {
        when (config.auth) {
            AuthMethod.TOKEN -> {
                builder.addQueryParameter("u", config.username)
                builder.addQueryParameter("t", SubsonicAuth.token(secret, salt))
                builder.addQueryParameter("s", salt)
            }
            AuthMethod.PLAIN -> {
                builder.addQueryParameter("u", config.username)
                builder.addQueryParameter("p", SubsonicAuth.hexPassword(secret))
            }
            AuthMethod.API_KEY -> builder.addQueryParameter("apiKey", secret)
        }
        builder.addQueryParameter("v", SubsonicAuth.API_VERSION)
        builder.addQueryParameter("c", SubsonicAuth.CLIENT_NAME)
        builder.addQueryParameter("f", "json")
        for ((key, value) in params) {
            if (value != null) builder.addQueryParameter(key, value.toString())
        }
        return builder.build()
    }

    /** [format] "raw" asks for the original file; otherwise the server transcodes. */
    fun streamUrl(songId: String, format: String, maxBitRate: Int): HttpUrl =
        apiUrl("stream", listOf("id" to songId, "format" to format, "maxBitRate" to maxBitRate.takeIf { it > 0 }))

    fun coverUrl(coverId: String, size: Int?): HttpUrl =
        apiUrl("getCoverArt", listOf("id" to coverId, "size" to size))

    companion object {
        const val CONNECT_PATH = "connect-tonearm"
        private const val SALT_REUSE_MS = 5 * 60_000L
    }
}
