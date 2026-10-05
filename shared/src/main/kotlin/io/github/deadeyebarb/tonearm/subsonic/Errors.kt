package io.github.deadeyebarb.tonearm.subsonic

import io.github.deadeyebarb.tonearm.integrations.IntegrationHttpException
import io.github.deadeyebarb.tonearm.net.CertificateImportException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathBuilderException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.util.Collections
import java.util.IdentityHashMap
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * True when TLS failed because the server's certificate isn't trusted (offer to pin it).
 * Android's Conscrypt reports a CertPathValidatorException ("Trust anchor for certification path
 * not found"); the JDK and some vendors report PKIX path building failures instead.
 */
fun Throwable.isUntrustedCertificate(): Boolean = causes().any {
    val message = it.message.orEmpty().lowercase()
    it is CertPathValidatorException || it is CertPathBuilderException ||
        (it is CertificateException && ("trust anchor" in message || "certification path" in message))
}

/** This throwable, its causes and suppressed exceptions (OkHttp attaches failures of other routes there). */
private fun Throwable.causes(): Sequence<Throwable> = sequence {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val queue = ArrayDeque<Throwable>().apply { add(this@causes) }
    while (queue.isNotEmpty()) {
        val t = queue.removeFirst()
        if (!seen.add(t)) continue
        yield(t)
        t.cause?.let(queue::add)
        queue.addAll(t.suppressed)
    }
}

private fun Throwable.mentionsClientCertRejection(): Boolean = causes().any { t ->
    val m = t.message.orEmpty().uppercase()
    "CERTIFICATE_REQUIRED" in m || "ALERT NUMBER 116" in m || "BAD_CERTIFICATE" in m || "ALERT NUMBER 42" in m ||
        "UNKNOWN_CA" in m || "ALERT NUMBER 48" in m || "CERTIFICATE_UNKNOWN" in m || "ALERT NUMBER 46" in m ||
        "HANDSHAKE_FAILURE" in m && "ALERT" in m
}

/** A sentence that tells the user what went wrong and, where possible, what to change. */
fun Throwable.userMessage(): String {
    if (this is IntegrationHttpException) return message ?: "Request failed"
    // When one of the server's addresses was reachable, its TLS failure explains more than "can't connect".
    if (this !is SSLException) causes().firstOrNull { it is SSLException }?.let { return it.userMessage() }
    return describe()
}

private fun Throwable.describe(): String = when (this) {
    is SubsonicApiException -> when (code) {
        10 -> "The server says a required parameter is missing ($message)"
        20, 30 -> "This server needs a newer/older client protocol: $message"
        40 -> "Wrong username or password"
        41 -> "This server doesn't accept token login. Switch the login method to “Password (legacy)”."
        42 -> "This server doesn't support this login method. Try another one."
        43 -> "Use either an API key or a password, not both"
        44 -> "The API key isn't valid"
        50 -> "Your account isn't allowed to do that"
        70 -> "Not found on the server"
        else -> message ?: "Server error $code"
    }
    is SubsonicHttpException -> when {
        "SSL certificate" in body || "client certificate" in body.lowercase() ->
            "The server wants a client certificate (mTLS) and didn't get a valid one"
        code == 401 || code == 403 -> "Access denied (HTTP $code). A proxy in front of the server may need a client certificate."
        code == 404 -> "No Subsonic API at this address (HTTP 404). Check the URL and any path prefix."
        code >= 500 -> "The server had an internal error (HTTP $code)"
        else -> "Unexpected HTTP $code from the server"
    }
    is SubsonicProtocolException -> message ?: "Unexpected response"
    is NoServerException -> "Add a server in Settings first"
    is CertificateImportException -> message ?: "Couldn't import the certificate"
    is UnknownHostException -> "Can't find the server ${message.orEmpty()}. Check the address and your connection."
    is ConnectException -> "Can't connect to the server. Is it running and reachable from this network?"
    is SocketTimeoutException -> "The server took too long to answer"
    is SSLPeerUnverifiedException -> "The server's certificate doesn't match its address: $message"
    is SSLHandshakeException, is SSLException -> when {
        isUntrustedCertificate() -> "The server's certificate isn't trusted. Import its CA or trust it in the server settings."
        mentionsClientCertRejection() -> "The server rejected the TLS connection. It probably needs a client certificate (mTLS), or doesn't accept the one you chose."
        else -> "Secure connection failed: $message"
    }
    else -> cause?.takeIf { it !== this && (message.isNullOrBlank() || it is SSLException) }?.userMessage()
        ?: message
        ?: javaClass.simpleName
}
