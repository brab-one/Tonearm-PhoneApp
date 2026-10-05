package io.github.deadeyebarb.tonearm.net

import io.github.deadeyebarb.tonearm.data.ServerConfig
import io.github.deadeyebarb.tonearm.subsonic.ServerSession
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import io.github.deadeyebarb.tonearm.subsonic.isUntrustedCertificate
import io.github.deadeyebarb.tonearm.subsonic.userMessage
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.X509KeyManager

/** End-to-end TLS client authentication against a server that requires it. */
class MtlsTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val ca = HeldCertificate.Builder().certificateAuthority(0).commonName("Tonearm Test CA").build()
    private val serverCert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").signedBy(ca).build()
    private val clientCert = HeldCertificate.Builder().commonName("listener").signedBy(ca).build()
    private val server = MockWebServer()

    @Before
    fun setUp() {
        val tls = HandshakeCertificates.Builder()
            .heldCertificate(serverCert, ca.certificate)
            .addTrustedCertificate(ca.certificate)
            .build()
        server.useHttps(tls.sslSocketFactory())
        server.requireClientAuth()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun p12(password: String): ByteArray {
        val keyStore = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        keyStore.setKeyEntry("client", clientCert.keyPair.private, password.toCharArray(), arrayOf(clientCert.certificate, ca.certificate))
        return ByteArrayOutputStream().also { keyStore.store(it, password.toCharArray()) }.toByteArray()
    }

    private fun session(keyManager: X509KeyManager?, anchors: List<X509Certificate> = listOf(ca.certificate)): ServerSession {
        val client = OkHttpClient.Builder().applyTls(keyManager, anchors, Certs.systemTrustManager()).build()
        val config = ServerConfig(name = "test", baseUrl = server.url("/").toString().trimEnd('/'), username = "u")
        return ServerSession(config, "pw", client)
    }

    private fun ServerSession.ping() =
        client.newCall(Request.Builder().url(apiUrl("ping")).build()).execute().use { SubsonicApi.parse(json, it.body.string()) }

    @Test
    fun `client certificate from a p12 file is presented and accepted`() {
        server.enqueue(MockResponse.Builder().body("""{"subsonic-response":{"status":"ok","version":"1.16.1","type":"test"}}""").build())
        val identity = Certs.loadPkcs12(p12("s3cret"), "s3cret".toCharArray())

        val response = session(FixedKeyManager(identity)).ping()

        assertEquals("test", response.type)
        val recorded = server.takeRequest()
        assertEquals("/rest/ping.view", recorded.url.encodedPath)
        val presented = recorded.handshake!!.peerCertificates.first() as X509Certificate
        assertEquals("CN=listener", presented.subjectX500Principal.name)
    }

    @Test
    fun `without a client certificate the server refuses the connection`() {
        server.enqueue(MockResponse.Builder().body("{}").build())
        try {
            session(keyManager = null).ping()
            fail("The server should have rejected the connection")
        } catch (e: IOException) {
            assertTrue(e.userMessage(), e.userMessage().isNotBlank())
        }
    }

    @Test
    fun `a private CA that is not trusted is detected so the user can pin it`() {
        val identity = Certs.loadPkcs12(p12("pw"), "pw".toCharArray())
        try {
            session(FixedKeyManager(identity), anchors = emptyList()).ping()
            fail("The private CA should not be trusted by default")
        } catch (e: IOException) {
            val chain = generateSequence<Throwable>(e) { it.cause }.joinToString(" <- ") { "${it.javaClass.name}: ${it.message}" }
            assertTrue(chain, e.isUntrustedCertificate())
            assertTrue(e.userMessage(), e.userMessage().contains("isn't trusted"))
        }
    }

    @Test
    fun `wrong p12 password is explained`() {
        try {
            Certs.loadPkcs12(p12("right"), "wrong".toCharArray())
            fail("expected a failure")
        } catch (e: CertificateImportException) {
            assertTrue(e.message!!, e.message!!.contains("password", ignoreCase = true))
        }
    }

    @Test
    fun `pem and fingerprint helpers round-trip`() {
        val pem = Certs.toPem(listOf(ca.certificate, clientCert.certificate))
        val parsed = Certs.parse(pem.toByteArray())
        assertEquals(2, parsed.size)
        assertEquals(Certs.sha256Fingerprint(ca.certificate), Certs.sha256Fingerprint(parsed[0]))
        assertEquals("Tonearm Test CA", CertInfo.of(ca.certificate).subject)
    }
}
