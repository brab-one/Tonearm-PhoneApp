package io.github.deadeyebarb.tonearm.integrations

import io.github.deadeyebarb.tonearm.net.await
import io.github.deadeyebarb.tonearm.subsonic.userMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * An HTTP error from Lidarr or the Tonearm server, with the server's own message when it sent one. [reason] is that
 * message alone, without the service's name; null when the service gave none (no answer, or just a status code).
 */
open class IntegrationHttpException(val code: Int, message: String, cause: Throwable? = null, val reason: String? = null) : IOException(message, cause)

/**
 * HTTP for the integrations. When asked to, requests go through the active music server's
 * client, which carries its mTLS client certificate and pinned CA: Lidarr and the Tonearm server often sit
 * behind the same reverse proxy.
 */
class IntegrationHttp(
    private val base: OkHttpClient,
    /** The music server's client, with its mTLS certificate. Suspends until the server settings are loaded. */
    private val serverClient: suspend () -> OkHttpClient?,
) {
    suspend fun client(useServerTls: Boolean): OkHttpClient =
        if (useServerTls) serverClient() ?: base else base

    suspend fun get(url: HttpUrl, useServerTls: Boolean, headers: Map<String, String> = emptyMap(), service: String = ""): String =
        execute(Request.Builder().url(url).apply { headers.forEach(::header) }.get().build(), useServerTls, service)

    suspend fun postJson(
        url: HttpUrl,
        body: String,
        useServerTls: Boolean,
        headers: Map<String, String> = emptyMap(),
        service: String = "",
        waitSeconds: Long? = null,
    ): String = send("POST", url, body, useServerTls, headers, service, waitSeconds)

    /** Sends [body] with [method]; [waitSeconds] waits that long for the answer, for calls slower than the usual timeout. */
    suspend fun send(
        method: String,
        url: HttpUrl,
        body: String,
        useServerTls: Boolean,
        headers: Map<String, String> = emptyMap(),
        service: String = "",
        waitSeconds: Long? = null,
    ): String = execute(
        Request.Builder().url(url).apply { headers.forEach(::header) }
            .method(method, body.toRequestBody("application/json".toMediaType())).build(),
        client(useServerTls).let { client -> waitSeconds?.let { client.newBuilder().readTimeout(it, TimeUnit.SECONDS).build() } ?: client },
        service,
    )

    /** POSTs [body] with a given [client], e.g. a music server's own with its client certificate. */
    suspend fun post(url: HttpUrl, body: String, client: OkHttpClient, contentType: String = "text/plain; charset=utf-8", service: String = ""): String =
        execute(Request.Builder().url(url).header("Accept", "application/json").post(body.toRequestBody(contentType.toMediaType())).build(), client, service)

    private suspend fun execute(request: Request, useServerTls: Boolean, service: String): String =
        execute(request, client(useServerTls), service)

    private suspend fun execute(request: Request, client: OkHttpClient, service: String): String {
        val prefix = if (service.isEmpty()) "" else "$service: "
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).await().use { response ->
                    val text = response.body.string()
                    if (!response.isSuccessful) {
                        val reason = reasonIn(text)
                        throw IntegrationHttpException(response.code, prefix + (reason ?: describe(response.code)), reason = reason)
                    }
                    text
                }
            } catch (e: IntegrationHttpException) {
                throw e
            } catch (e: IOException) {
                // Say which service failed: TLS and connection errors otherwise read like the music server's.
                throw IntegrationHttpException(0, prefix + e.userMessage(), e)
            }
        }
    }

    /** The service's own words in an error answer, if any. */
    private fun reasonIn(body: String): String? {
        // Lidarr validation errors: [{"propertyName": "...", "errorMessage": "..."}]; the Tonearm server: {"error": "..."}.
        val messages = Regex(""""(?:errorMessage|error|message)"\s*:\s*"([^"]+)"""").findAll(body).map { it.groupValues[1] }.toList()
        return messages.distinct().joinToString("; ").ifEmpty { null }
    }

    /** An error answer without the service's own words. */
    private fun describe(code: Int): String = when (code) {
        401, 403 -> "The API key was rejected (HTTP $code)"
        404 -> "Nothing at this address (HTTP 404). Check the URL."
        // From a reverse proxy: it got no answer from what's behind it.
        502, 504 -> "No answer through the proxy (HTTP $code); try again in a moment"
        else -> "HTTP $code"
    }

    companion object {
        /** Base URL plus path segments; keeps any path prefix the user entered. */
        fun url(base: String, path: String, params: List<Pair<String, Any?>> = emptyList()): HttpUrl {
            val builder = base.trimEnd('/').toHttpUrl().newBuilder()
            path.split('/').filter { it.isNotEmpty() }.forEach(builder::addPathSegment)
            for ((k, v) in params) if (v != null) builder.addQueryParameter(k, v.toString())
            return builder.build()
        }
    }
}
