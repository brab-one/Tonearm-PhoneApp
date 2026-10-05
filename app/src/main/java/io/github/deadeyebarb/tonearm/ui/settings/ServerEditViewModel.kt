package io.github.deadeyebarb.tonearm.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.deadeyebarb.tonearm.AppContainer
import io.github.deadeyebarb.tonearm.data.AuthMethod
import io.github.deadeyebarb.tonearm.data.ClientCert
import io.github.deadeyebarb.tonearm.data.ServerConfig
import io.github.deadeyebarb.tonearm.data.normalizeServerUrl
import io.github.deadeyebarb.tonearm.net.CertInfo
import io.github.deadeyebarb.tonearm.subsonic.isUntrustedCertificate
import io.github.deadeyebarb.tonearm.subsonic.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.cert.X509Certificate
import java.util.UUID

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Passed(val summary: String) : TestState
    data class Failed(val message: String) : TestState
}

class ServerEditViewModel(private val c: AppContainer, serverId: String?) : ViewModel() {
    private val existing: ServerConfig? = serverId?.let(c.servers::find)
    val isNew = existing == null
    private val id = existing?.id ?: UUID.randomUUID().toString()

    var url by mutableStateOf(existing?.baseUrl.orEmpty())
    var name by mutableStateOf(existing?.name.orEmpty())
    var username by mutableStateOf(existing?.username.orEmpty())
    var secret by mutableStateOf(existing?.let { c.secrets.decrypt(it.secretEnc) }.orEmpty())
    var auth by mutableStateOf(existing?.auth ?: AuthMethod.TOKEN)

    var clientCert by mutableStateOf(existing?.clientCert)
        private set
    var clientCertInfo by mutableStateOf<CertInfo?>(null)
        private set
    var trustedCa by mutableStateOf(existing?.trustedCa)
        private set
    var trustedCaInfo by mutableStateOf<CertInfo?>(null)
        private set

    var test by mutableStateOf<TestState>(TestState.Idle)
        private set
    /** The chain the server presented when it wasn't trusted, offered for pinning. */
    var untrustedChain by mutableStateOf<List<X509Certificate>?>(null)
        private set

    /** A .p12 file waiting for its password. */
    var pendingPkcs12 by mutableStateOf<ByteArray?>(null)
    var pkcs12Error by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val certInfo = existing?.clientCert?.let(c.tls::describe)
            val caInfo = existing?.trustedCa?.let(c.tls::describe)
            withContext(Dispatchers.Main) {
                clientCertInfo = certInfo
                trustedCaInfo = caInfo
            }
        }
    }

    val normalizedUrl: String get() = normalizeServerUrl(url)

    val urlError: String?
        get() = when {
            url.isBlank() -> null
            normalizedUrl.toHttpUrlOrNull() == null -> "Enter an address like https://music.example.com"
            else -> null
        }

    val isCleartext: Boolean get() = normalizedUrl.startsWith("http://")

    val canSave: Boolean
        get() = url.isNotBlank() && urlError == null && secret.isNotEmpty() && (auth == AuthMethod.API_KEY || username.isNotBlank())

    private fun config() = ServerConfig(
        id = id,
        name = name.trim().ifEmpty { normalizedUrl.toHttpUrlOrNull()?.host ?: normalizedUrl },
        baseUrl = normalizedUrl,
        username = username.trim(),
        secretEnc = c.secrets.encrypt(secret),
        auth = auth,
        clientCert = clientCert,
        trustedCa = trustedCa,
    )

    fun runTest() {
        if (!canSave) return
        test = TestState.Running
        untrustedChain = null
        viewModelScope.launch {
            test = try {
                val session = withContext(Dispatchers.IO) { c.sessions.build(config()) }
                val response = c.api.ping(session)
                val server = listOfNotNull(response.type?.replaceFirstChar { it.uppercase() } ?: "Subsonic", response.serverVersion).joinToString(" ")
                TestState.Passed(
                    "Connected to $server · API ${response.version}" +
                        (if (response.openSubsonic) " · OpenSubsonic" else "") +
                        (if (clientCert != null) " · client certificate accepted" else ""),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.isUntrustedCertificate()) {
                    untrustedChain = runCatching { c.tls.fetchServerChain(normalizedUrl, clientCert) }.getOrNull()
                }
                TestState.Failed(e.userMessage())
            }
        }
    }

    /** Pins the root-most certificate the server sent (the self-signed cert, or its private CA). */
    fun trustPresentedCertificate() {
        val chain = untrustedChain ?: return
        val (ca, info) = c.tls.trust(chain.last())
        trustedCa = ca
        trustedCaInfo = info
        untrustedChain = null
        runTest()
    }

    fun dismissUntrusted() {
        untrustedChain = null
    }

    fun importPkcs12(password: String) {
        val bytes = pendingPkcs12 ?: return
        viewModelScope.launch {
            try {
                val (cert, info) = withContext(Dispatchers.IO) { c.tls.importPkcs12(bytes, password) }
                clientCert = cert
                clientCertInfo = info
                pendingPkcs12 = null
                pkcs12Error = null
                test = TestState.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pkcs12Error = e.userMessage()
            }
        }
    }

    fun cancelPkcs12() {
        pendingPkcs12 = null
        pkcs12Error = null
    }

    fun useKeyChainAlias(alias: String) {
        clientCert = ClientCert.SystemKeyChain(alias)
        clientCertInfo = null
        test = TestState.Idle
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { c.tls.describe(ClientCert.SystemKeyChain(alias)) }
            clientCertInfo = info
            if (info != null) clientCert = ClientCert.SystemKeyChain(alias, info.subject)
        }
    }

    fun removeClientCert() {
        clientCert = null
        clientCertInfo = null
        test = TestState.Idle
    }

    fun importCa(bytes: ByteArray): String? = try {
        val (ca, info) = c.tls.importCa(bytes)
        trustedCa = ca
        trustedCaInfo = info
        test = TestState.Idle
        null
    } catch (e: Exception) {
        e.userMessage()
    }

    fun removeCa() {
        trustedCa = null
        trustedCaInfo = null
        test = TestState.Idle
    }

    suspend fun save(): Boolean {
        if (!canSave) return false
        c.servers.upsert(config(), makeActive = isNew)
        withContext(Dispatchers.IO) { c.tls.deleteUnused(c.servers.load().servers) }
        return true
    }

    suspend fun delete() {
        c.servers.remove(id)
        withContext(Dispatchers.IO) { c.tls.deleteUnused(c.servers.load().servers) }
    }
}
