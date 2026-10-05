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

/** A configured server with its decrypted secret and its own TLS-configured HTTP client. */
class ServerSession(val config: ServerConfig, private val secret: String, val client: OkHttpClient) {
    val id: String get() = config.id
    private val base: HttpUrl = config.baseUrl.toHttpUrl()

    fun matches(url: HttpUrl) = url.host == base.host && url.port == base.port

    fun apiUrl(method: String, params: List<Pair<String, Any?>> = emptyList()): HttpUrl {
        val builder = base.newBuilder().addPathSegment("rest").addPathSegment("$method.view")
        when (config.auth) {
            AuthMethod.TOKEN -> {
                val salt = SubsonicAuth.salt()
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
}
