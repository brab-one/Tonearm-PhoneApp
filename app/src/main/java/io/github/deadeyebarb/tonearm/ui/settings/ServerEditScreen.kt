package io.github.deadeyebarb.tonearm.ui.settings

import android.net.Uri
import android.security.KeyChain
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.deadeyebarb.tonearm.ui.theme.Hud
import io.github.deadeyebarb.tonearm.ui.theme.HudButton
import io.github.deadeyebarb.tonearm.ui.theme.HudTopBar
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.data.AuthMethod
import io.github.deadeyebarb.tonearm.data.ClientCert
import io.github.deadeyebarb.tonearm.net.CertInfo
import io.github.deadeyebarb.tonearm.ui.common.LocalActions
import kotlinx.coroutines.launch
import java.text.DateFormat

@Composable
fun ServerEditScreen(serverId: String?, welcome: Boolean, onSaved: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val actions = LocalActions.current
    val activity = LocalActivity.current
    val vm = viewModel(key = "server-edit|$serverId") { ServerEditViewModel(c, serverId) }
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }

    fun readUri(uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull()

    val pickPkcs12 = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::readUri)?.let { vm.pendingPkcs12 = it }
    }
    val pickCa = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val bytes = uri?.let(::readUri) ?: return@rememberLauncherForActivityResult
        vm.importCa(bytes)?.let(actions::message)
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            HudTopBar(
                title = if (welcome) "Tonearm" else if (vm.isNew) "Add server" else "Edit server",
                subtitle = if (welcome) "UPLINK SETUP // CONNECT YOUR MUSIC SERVER" else "UPLINK CONFIGURATION",
                navigationIcon = {
                    if (!welcome) IconButton(onClick = { actions.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                },
                actions = {
                    if (!vm.isNew) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, "Delete server") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (welcome) {
                Text(
                    "Connect to your Navidrome, Gonic, Airsonic, LMS or any other Subsonic-compatible server.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            OutlinedTextField(
                vm.url, { vm.url = it },
                label = { Text("Server address") },
                placeholder = { Text("https://music.example.com") },
                singleLine = true,
                isError = vm.urlError != null,
                supportingText = {
                    when {
                        vm.urlError != null -> Text(vm.urlError!!)
                        vm.isCleartext -> Text("Plain http: your password and music travel unencrypted. Fine on a home network, risky elsewhere.")
                        else -> Text("Include any path prefix, e.g. https://example.com/music")
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                vm.name, { vm.name = it }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            Heading("Sign in")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                AuthMethod.entries.forEachIndexed { i, method ->
                    SegmentedButton(
                        selected = vm.auth == method,
                        onClick = { vm.auth = method },
                        shape = SegmentedButtonDefaults.itemShape(i, AuthMethod.entries.size),
                    ) { Text(method.label, maxLines = 1) }
                }
            }
            if (vm.auth != AuthMethod.API_KEY) {
                OutlinedTextField(
                    vm.username, { vm.username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            SecretField(vm.secret, { vm.secret = it }, if (vm.auth == AuthMethod.API_KEY) "API key" else "Password")
            if (vm.auth == AuthMethod.PLAIN) {
                Hint("Sends the password hex-encoded in every request. Only use this if the server can't do token login (e.g. LDAP accounts), and only over https.")
            }

            Heading("Client certificate (mTLS)")
            Hint("Only needed if your server or its reverse proxy requires TLS client authentication. The certificate is used for every request: browsing, streaming, cover art and downloads.")
            val cert = vm.clientCert
            if (cert != null) {
                CertCard(
                    icon = Icons.Rounded.Key,
                    title = vm.clientCertInfo?.subject ?: cert.label,
                    info = vm.clientCertInfo,
                    source = when (cert) {
                        is ClientCert.SystemKeyChain -> "From Android credential storage"
                        is ClientCert.Pkcs12File -> "Imported .p12, stored privately in the app"
                    },
                    onRemove = vm::removeClientCert,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickPkcs12.launch(arrayOf("application/x-pkcs12", "application/x-pkcs12-certificate", "application/octet-stream", "*/*")) }, modifier = Modifier.weight(1f)) {
                    Text("Import .p12 file", maxLines = 1)
                }
                OutlinedButton(
                    onClick = {
                        val host = vm.normalizedUrl.takeIf { vm.urlError == null && vm.url.isNotBlank() }?.let(Uri::parse)
                        activity?.let { act ->
                            KeyChain.choosePrivateKeyAlias(
                                act,
                                { alias -> if (alias != null) ContextCompat.getMainExecutor(context).execute { vm.useKeyChainAlias(alias) } },
                                arrayOf("RSA", "EC"), null, host, (cert as? ClientCert.SystemKeyChain)?.alias,
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Use installed", maxLines = 1) }
            }

            Heading("Server certificate")
            Hint("For a private CA or a self-signed certificate. Servers with public certificates (e.g. Let's Encrypt) need nothing here.")
            val ca = vm.trustedCa
            if (ca != null) {
                CertCard(Icons.Rounded.Shield, vm.trustedCaInfo?.subject ?: ca.label, vm.trustedCaInfo, "Trusted for this server only", vm::removeCa)
            }
            OutlinedButton(onClick = { pickCa.launch(arrayOf("application/x-x509-ca-cert", "application/x-pem-file", "application/pkix-cert", "*/*")) }) {
                Text("Import CA certificate")
            }

            when (val test = vm.test) {
                TestState.Idle -> Unit
                TestState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Connecting…")
                }
                is TestState.Passed -> StatusCard(Icons.Rounded.CheckCircle, test.summary, MaterialTheme.colorScheme.primaryContainer)
                is TestState.Failed -> StatusCard(Icons.Rounded.ErrorOutline, test.message, MaterialTheme.colorScheme.errorContainer)
            }
            vm.untrustedChain?.let { chain -> UntrustedCard(chain.map(CertInfo::of), vm::trustPresentedCertificate, vm::dismissUntrusted) }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                HudButton(
                    "Test", vm::runTest, Modifier.weight(1f), filled = false,
                    enabled = vm.canSave && vm.test != TestState.Running,
                )
                HudButton(
                    if (welcome) "Connect" else "Save", { scope.launch { if (vm.save()) onSaved() } }, Modifier.weight(1f),
                    enabled = vm.canSave,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (vm.pendingPkcs12 != null) {
        Pkcs12PasswordDialog(error = vm.pkcs12Error, onConfirm = vm::importPkcs12, onDismiss = vm::cancelPkcs12)
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove this server?") },
            text = { Text("Its settings and certificates are deleted from this device. Your downloads stay.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        vm.delete()
                        actions.back()
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Heading(text: String) {
    Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("// ", style = MaterialTheme.typography.labelMedium, color = Hud.colors.accent)
        Text(text.uppercase(), style = MaterialTheme.typography.titleLarge.copy(fontSize = 14.sp))
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, label: String) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value, onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (visible) "Hide" else "Show")
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CertCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, info: CertInfo?, source: String, onRemove: () -> Unit) {
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
            headlineContent = { Text(title) },
            supportingContent = {
                Column {
                    Text(source)
                    if (info != null) {
                        Text("Issued by ${info.issuer}")
                        Text(
                            if (info.expired) "Expired ${date.format(info.notAfter)}" else "Valid until ${date.format(info.notAfter)}",
                            color = if (info.expired) MaterialTheme.colorScheme.error else Color.Unspecified,
                        )
                    }
                }
            },
            trailingContent = { IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, "Remove") } },
        )
    }
}

@Composable
private fun StatusCard(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color) {
    Card(colors = CardDefaults.cardColors(containerColor = color)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun UntrustedCard(chain: List<CertInfo>, onTrust: () -> Unit, onDismiss: () -> Unit) {
    val anchor = chain.last()
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Warning, null)
                Spacer(Modifier.width(8.dp))
                Text("Trust this certificate?", style = MaterialTheme.typography.titleSmall)
            }
            val leaf = chain.first()
            Text(
                when {
                    chain.size > 1 -> "The server presented ${leaf.subject}, issued by ${anchor.subject}."
                    leaf.subject == leaf.issuer -> "The server presented ${leaf.subject} (self-signed)."
                    else -> "The server presented ${leaf.subject}, issued by ${leaf.issuer}. It didn't send that CA, so this certificate itself will be trusted."
                },
            )
            Text("Valid until ${date.format(anchor.notAfter)}")
            Text("SHA-256 fingerprint:", style = MaterialTheme.typography.labelMedium)
            Text(anchor.sha256, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text(
                "Only trust it if this fingerprint matches the one on your server (for example `openssl x509 -noout -fingerprint -sha256 -in cert.pem`).",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onTrust) { Text("Trust") }
                TextButton(onClick = onDismiss) { Text("Not now") }
            }
        }
    }
}

@Composable
private fun Pkcs12PasswordDialog(error: String?, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Certificate password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter the password the .p12 file was exported with. Leave empty if it has none.")
                OutlinedTextField(
                    password, { password = it },
                    label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(password) }) { Text("Import") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
