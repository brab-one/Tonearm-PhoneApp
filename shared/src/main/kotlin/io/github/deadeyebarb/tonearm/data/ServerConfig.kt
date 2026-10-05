package io.github.deadeyebarb.tonearm.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class AuthMethod(val label: String) {
    /** Salted MD5 token (Subsonic API 1.13+). The default for every modern server. */
    TOKEN("Password"),
    /** Hex-encoded password in the query string, for servers backed by LDAP. */
    PLAIN("Legacy"),
    /** OpenSubsonic API keys. */
    API_KEY("API key"),
}

/** Where the TLS client certificate for mTLS comes from. */
@Serializable
sealed interface ClientCert {
    val label: String

    /** A certificate installed in Android's credential storage, used through KeyChain. */
    @Serializable
    @SerialName("keychain")
    data class SystemKeyChain(val alias: String, override val label: String = alias) : ClientCert

    /** A PKCS#12 file copied into app-private storage; its password is Keystore-encrypted. */
    @Serializable
    @SerialName("pkcs12")
    data class Pkcs12File(
        val fileName: String,
        val passwordEnc: String,
        override val label: String,
    ) : ClientCert
}

/** An extra CA (or a pinned self-signed certificate) trusted for this server only. */
@Serializable
data class TrustedCa(val fileName: String, val label: String)

@Serializable
data class ServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String,
    val username: String = "",
    /** Keystore-encrypted password or API key. */
    val secretEnc: String = "",
    val auth: AuthMethod = AuthMethod.TOKEN,
    val clientCert: ClientCert? = null,
    val trustedCa: TrustedCa? = null,
)

@Serializable
data class ServerList(
    val servers: List<ServerConfig> = emptyList(),
    val activeId: String? = null,
) {
    val active: ServerConfig? get() = servers.firstOrNull { it.id == activeId } ?: servers.firstOrNull()
}

/** Trims whitespace and trailing slashes, and assumes https when no scheme is given. */
fun normalizeServerUrl(raw: String): String {
    var url = raw.trim()
    if (url.isEmpty()) return url
    if (!url.contains("://")) url = "https://$url"
    return url.trimEnd('/')
}
