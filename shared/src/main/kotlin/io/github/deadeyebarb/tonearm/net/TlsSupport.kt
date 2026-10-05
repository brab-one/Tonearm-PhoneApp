package io.github.deadeyebarb.tonearm.net

import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.Socket
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * Presents one fixed client identity regardless of which issuers the server asks for.
 * The JDK's default key manager drops the certificate when the server's CA list doesn't name
 * the client cert's direct issuer, which breaks common nginx/Caddy setups with intermediates.
 */
open class FixedKeyManager(
    private val identity: () -> ClientIdentity?,
) : X509ExtendedKeyManager() {
    constructor(identity: ClientIdentity) : this({ identity })

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) =
        ALIAS.takeIf { identity() != null }

    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) =
        ALIAS.takeIf { identity() != null }

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
    override fun getCertificateChain(alias: String?): Array<X509Certificate>? = identity()?.chain
    override fun getPrivateKey(alias: String?): PrivateKey? = identity()?.key
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null

    private companion object {
        const val ALIAS = "tonearm-client"
    }
}

class ClientIdentity(val key: PrivateKey, val chain: Array<X509Certificate>)

/** Trusts a chain if any of the delegates does (server-specific CA first, then the system store). */
class CompositeTrustManager(private val delegates: List<X509TrustManager>) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
        throw CertificateException("Client certificates are not accepted here")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        var last: CertificateException? = null
        for (delegate in delegates) {
            try {
                delegate.checkServerTrusted(chain, authType)
                return
            } catch (e: CertificateException) {
                last = e
            }
        }
        throw last ?: CertificateException("No trust managers configured")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegates.flatMap { it.acceptedIssuers.asList() }.toTypedArray()
}

class CertificateImportException(message: String, cause: Throwable? = null) : GeneralSecurityException(message, cause)

object Certs {
    fun systemTrustManager(): X509TrustManager = trustManagerFor(null)

    /** A trust manager for the given anchors, or the platform default store when null. */
    fun trustManagerFor(anchors: List<X509Certificate>?): X509TrustManager {
        val keyStore = anchors?.let { certs ->
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                certs.forEachIndexed { i, cert -> setCertificateEntry("ca$i", cert) }
            }
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** Parses PEM (one or many certificates) or DER. */
    fun parse(bytes: ByteArray): List<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")
        val certs = try {
            factory.generateCertificates(ByteArrayInputStream(bytes)).filterIsInstance<X509Certificate>()
        } catch (e: CertificateException) {
            throw CertificateImportException("Not a certificate file (expected PEM or DER)", e)
        }
        if (certs.isEmpty()) throw CertificateImportException("No certificates found in the file")
        return certs
    }

    fun toPem(certs: List<X509Certificate>): String = buildString {
        val encoder = Base64.getMimeEncoder(64, "\n".toByteArray())
        for (cert in certs) {
            append("-----BEGIN CERTIFICATE-----\n")
            append(encoder.encodeToString(cert.encoded))
            append("\n-----END CERTIFICATE-----\n")
        }
    }

    fun sha256Fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }

    /** Loads the first private key entry from a PKCS#12 (.p12 / .pfx) file. */
    fun loadPkcs12(bytes: ByteArray, password: CharArray): ClientIdentity {
        val keyStore = KeyStore.getInstance("PKCS12")
        try {
            keyStore.load(ByteArrayInputStream(bytes), password)
        } catch (e: IOException) {
            val msg = e.message.orEmpty().lowercase()
            val wrongPassword = "password" in msg || "mac" in msg || e.cause is java.security.UnrecoverableKeyException
            throw CertificateImportException(
                if (wrongPassword) {
                    "Wrong password, or the file is damaged"
                } else {
                    "Couldn't read the PKCS#12 file (${e.message}). If it was made with OpenSSL 3 " +
                        "and this is an older Android version, re-export it with `openssl pkcs12 -export -legacy`."
                },
                e,
            )
        } catch (e: GeneralSecurityException) {
            throw CertificateImportException("Couldn't read the PKCS#12 file: ${e.message}", e)
        }
        val alias = keyStore.aliases().toList().firstOrNull { keyStore.isKeyEntry(it) }
            ?: throw CertificateImportException("The file has no private key, only certificates")
        val key = try {
            keyStore.getKey(alias, password) as? PrivateKey
        } catch (e: GeneralSecurityException) {
            throw CertificateImportException("Couldn't unlock the private key: ${e.message}", e)
        } ?: throw CertificateImportException("The key in the file isn't a private key")
        val chain = keyStore.getCertificateChain(alias)?.filterIsInstance<X509Certificate>()
        if (chain.isNullOrEmpty()) throw CertificateImportException("The file has a key but no certificate")
        return ClientIdentity(key, chain.toTypedArray())
    }

    fun sslContext(keyManager: X509KeyManager?, trustManager: X509TrustManager): SSLContext =
        SSLContext.getInstance("TLS").apply {
            init(keyManager?.let { arrayOf<KeyManager>(it) }, arrayOf<TrustManager>(trustManager), null)
        }
}

/** Applies a client identity and extra trust anchors to an OkHttp client. No-op when neither is set. */
fun OkHttpClient.Builder.applyTls(
    keyManager: X509KeyManager?,
    extraAnchors: List<X509Certificate>,
    systemTrust: X509TrustManager,
): OkHttpClient.Builder {
    if (keyManager == null && extraAnchors.isEmpty()) return this
    val trust = if (extraAnchors.isEmpty()) {
        systemTrust
    } else {
        CompositeTrustManager(listOf(Certs.trustManagerFor(extraAnchors), systemTrust))
    }
    return sslSocketFactory(Certs.sslContext(keyManager, trust).socketFactory, trust)
}

data class CertInfo(
    val subject: String,
    val issuer: String,
    val notAfter: Date,
    val sha256: String,
) {
    val expired: Boolean get() = notAfter.before(Date())

    companion object {
        fun of(cert: X509Certificate) = CertInfo(
            subject = commonName(cert.subjectX500Principal),
            issuer = commonName(cert.issuerX500Principal),
            notAfter = cert.notAfter,
            sha256 = Certs.sha256Fingerprint(cert),
        )

        private fun commonName(principal: X500Principal): String {
            val dn = principal.getName(X500Principal.RFC2253)
            return Regex("""(?:^|,)CN=((?:\\,|[^,])+)""").find(dn)?.groupValues?.get(1)?.replace("\\,", ",") ?: dn
        }
    }
}
