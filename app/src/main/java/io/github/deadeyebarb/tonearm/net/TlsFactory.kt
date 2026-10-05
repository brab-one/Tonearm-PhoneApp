package io.github.deadeyebarb.tonearm.net

import android.content.Context
import android.security.KeyChain
import io.github.deadeyebarb.tonearm.data.ClientCert
import io.github.deadeyebarb.tonearm.data.SecretBox
import io.github.deadeyebarb.tonearm.data.ServerConfig
import io.github.deadeyebarb.tonearm.data.TrustedCa
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.GeneralSecurityException
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

/** Builds per-server TLS settings and manages the certificate files stored for them. */
class TlsFactory(context: Context, private val secrets: SecretBox) {
    private val appContext = context.applicationContext
    private val dir = File(context.filesDir, "tls").apply { mkdirs() }

    val systemTrust: X509TrustManager by lazy { Certs.systemTrustManager() }

    fun configure(builder: OkHttpClient.Builder, config: ServerConfig) {
        val keyManager = config.clientCert?.let(::keyManagerFor)
        val anchors = config.trustedCa?.let { Certs.parse(File(dir, it.fileName).readBytes()) }.orEmpty()
        builder.applyTls(keyManager, anchors, systemTrust)
    }

    fun keyManagerFor(cert: ClientCert): X509KeyManager = when (cert) {
        is ClientCert.SystemKeyChain -> KeyChainKeyManager(appContext, cert.alias)
        is ClientCert.Pkcs12File -> {
            val password = secrets.decrypt(cert.passwordEnc)
                ?: throw GeneralSecurityException("The certificate password can't be decrypted. Import the certificate again.")
            FixedKeyManager(Certs.loadPkcs12(File(dir, cert.fileName).readBytes(), password.toCharArray()))
        }
    }

    /** Validates the PKCS#12 file and stores a private copy of it. */
    fun importPkcs12(bytes: ByteArray, password: String): Pair<ClientCert.Pkcs12File, CertInfo> {
        val identity = Certs.loadPkcs12(bytes, password.toCharArray())
        val info = CertInfo.of(identity.chain.first())
        val name = "client-${UUID.randomUUID()}.p12"
        File(dir, name).writeBytes(bytes)
        return ClientCert.Pkcs12File(name, secrets.encrypt(password), info.subject) to info
    }

    fun importCa(bytes: ByteArray): Pair<TrustedCa, CertInfo> = storeAnchors(Certs.parse(bytes))

    fun trust(cert: X509Certificate): Pair<TrustedCa, CertInfo> = storeAnchors(listOf(cert))

    private fun storeAnchors(certs: List<X509Certificate>): Pair<TrustedCa, CertInfo> {
        val name = "ca-${UUID.randomUUID()}.pem"
        File(dir, name).writeText(Certs.toPem(certs))
        val info = CertInfo.of(certs.first())
        return TrustedCa(name, info.subject) to info
    }

    /** Must be called off the main thread (KeyChain blocks). */
    fun describe(cert: ClientCert): CertInfo? = runCatching {
        when (cert) {
            is ClientCert.SystemKeyChain -> KeyChain.getCertificateChain(appContext, cert.alias)?.firstOrNull()
            is ClientCert.Pkcs12File -> keyManagerFor(cert).getCertificateChain(null)?.firstOrNull()
        }?.let(CertInfo::of)
    }.getOrNull()

    fun describe(ca: TrustedCa): CertInfo? = runCatching {
        CertInfo.of(Certs.parse(File(dir, ca.fileName).readBytes()).first())
    }.getOrNull()

    /** Deletes certificate files no server refers to any more. */
    fun deleteUnused(servers: List<ServerConfig>) {
        val used = servers.flatMap {
            listOfNotNull((it.clientCert as? ClientCert.Pkcs12File)?.fileName, it.trustedCa?.fileName)
        }.toSet()
        dir.listFiles()?.filter { it.name !in used }?.forEach { it.delete() }
    }

    /**
     * Connects with a trust-everything manager purely to read the certificate chain the server
     * presents, so the user can inspect and pin it. Nothing is sent over this connection.
     */
    suspend fun fetchServerChain(baseUrl: String, clientCert: ClientCert?): List<X509Certificate> =
        withContext(Dispatchers.IO) {
            val url = baseUrl.toHttpUrl()
            val captured = AtomicReference<Array<X509Certificate>>()
            val capturing = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                    captured.set(arrayOf(*chain))
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val keyManager = clientCert?.let { runCatching { keyManagerFor(it) }.getOrNull() }
            val factory = Certs.sslContext(keyManager, capturing).socketFactory
            val plain = Socket().apply {
                connect(InetSocketAddress(url.host, url.port), 10_000)
                soTimeout = 10_000
            }
            try {
                (factory.createSocket(plain, url.host, url.port, true) as SSLSocket).use { it.startHandshake() }
            } catch (e: Exception) {
                if (captured.get() == null) throw e
            } finally {
                plain.close()
            }
            captured.get()?.toList() ?: throw GeneralSecurityException("The server sent no certificate")
        }
}

/** Uses a certificate from Android's credential storage; the key may never leave secure hardware. */
private class KeyChainKeyManager(context: Context, alias: String) :
    FixedKeyManager(KeyChainIdentity(context, alias)::load)

private class KeyChainIdentity(private val context: Context, private val alias: String) {
    @Volatile private var cached: ClientIdentity? = null

    // KeyChain calls block, which is fine: they run on OkHttp's connection threads.
    fun load(): ClientIdentity? = cached ?: runCatching {
        val key = KeyChain.getPrivateKey(context, alias)
        val chain = KeyChain.getCertificateChain(context, alias)
        if (key != null && !chain.isNullOrEmpty()) ClientIdentity(key, chain) else null
    }.getOrNull()?.also { cached = it }
}
