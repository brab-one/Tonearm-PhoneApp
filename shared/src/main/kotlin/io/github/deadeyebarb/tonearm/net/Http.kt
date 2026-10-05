package io.github.deadeyebarb.tonearm.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Sent with every request; the desktop app replaces it at startup. */
var USER_AGENT = "Tonearm/1.0 (Android)"

/**
 * For image loading: turns error responses into failures. Lidarr answers a missing cover with a 404
 * that says "cache for a year", and the image cache would keep serving that error body.
 */
val ImageErrorsAsFailures = Interceptor { chain ->
    val response = chain.proceed(chain.request())
    if (response.isSuccessful || response.code == 304) return@Interceptor response
    response.close()
    throw IOException("Image request failed (HTTP ${response.code})")
}

private val imageClients = java.util.WeakHashMap<OkHttpClient, OkHttpClient>()

/** This client with [ImageErrorsAsFailures], made once per client. */
fun OkHttpClient.forImages(): OkHttpClient = synchronized(imageClients) {
    imageClients.getOrPut(this) { newBuilder().addInterceptor(ImageErrorsAsFailures).build() }
}

val UserAgentInterceptor = Interceptor { chain ->
    chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, value, _ -> value.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(e)
        }
    })
}
