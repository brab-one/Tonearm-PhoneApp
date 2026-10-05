package io.github.deadeyebarb.tonearm.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.deadeyebarb.tonearm.AppContainer
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.data.LidarrConfig
import io.github.deadeyebarb.tonearm.data.MalojaConfig
import io.github.deadeyebarb.tonearm.data.normalizeServerUrl
import io.github.deadeyebarb.tonearm.integrations.BrainarrList
import io.github.deadeyebarb.tonearm.integrations.LidarrProfile
import io.github.deadeyebarb.tonearm.integrations.LidarrRootFolder
import io.github.deadeyebarb.tonearm.subsonic.userMessage
import io.github.deadeyebarb.tonearm.ui.common.LocalActions
import io.github.deadeyebarb.tonearm.ui.common.formatBytes
import io.github.deadeyebarb.tonearm.ui.detail.DetailScaffold
import io.github.deadeyebarb.tonearm.ui.theme.Hud
import io.github.deadeyebarb.tonearm.ui.theme.HudButton
import io.github.deadeyebarb.tonearm.ui.theme.HudSectionHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class MalojaSettingsViewModel(private val c: AppContainer) : ViewModel() {
    private val existing = c.integrations.state.value.maloja
    val isNew = existing == null
    var url by mutableStateOf(existing?.url.orEmpty())
    var key by mutableStateOf(existing?.let { c.integrations.decrypt(it.keyEnc) }.orEmpty())
    var scrobble by mutableStateOf(existing?.scrobble ?: false)
    var useServerTls by mutableStateOf(existing?.useServerTls ?: true)
    var test by mutableStateOf<TestState>(TestState.Idle)
        private set

    val valid get() = normalizeServerUrl(url).toHttpUrlOrNull() != null

    private fun config() = MalojaConfig(normalizeServerUrl(url), c.integrations.encrypt(key.trim()), scrobble, useServerTls)

    fun runTest() {
        if (!valid) return
        test = TestState.Running
        viewModelScope.launch {
            test = try {
                val name = c.integrations.maloja.test(config(), key.trim())
                val count = runCatching { c.integrations.maloja.scrobbleCount(config(), null) }.getOrNull()
                TestState.Passed("Connected to $name" + (count?.let { " · $it scrobbles" } ?: "") + if (key.isNotBlank()) " · API key accepted" else "")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TestState.Failed(e.userMessage())
            }
        }
    }

    suspend fun save() = c.integrations.repository.update { it.copy(maloja = config()) }
    suspend fun remove() = c.integrations.repository.update { it.copy(maloja = null) }
}

class LidarrSettingsViewModel(private val c: AppContainer) : ViewModel() {
    private val existing = c.integrations.state.value.lidarr
    val isNew = existing == null
    var url by mutableStateOf(existing?.url.orEmpty())
    var key by mutableStateOf(existing?.let { c.integrations.decrypt(it.keyEnc) }.orEmpty())
    var useServerTls by mutableStateOf(existing?.useServerTls ?: true)
    var rootFolder by mutableStateOf(existing?.rootFolderPath)
    var qualityProfile by mutableStateOf(existing?.qualityProfileId)
    var metadataProfile by mutableStateOf(existing?.metadataProfileId)
    var monitor by mutableStateOf(existing?.monitor ?: "all")
    var searchOnAdd by mutableStateOf(existing?.searchOnAdd ?: true)

    var test by mutableStateOf<TestState>(TestState.Idle)
        private set
    var roots by mutableStateOf<List<LidarrRootFolder>>(emptyList())
        private set
    var qualities by mutableStateOf<List<LidarrProfile>>(emptyList())
        private set
    var metadatas by mutableStateOf<List<LidarrProfile>>(emptyList())
        private set
    /** Brainarr import lists found by the last test; null before a test. */
    var brainarr by mutableStateOf<List<BrainarrList>?>(null)
        private set

    val valid get() = normalizeServerUrl(url).toHttpUrlOrNull() != null && key.isNotBlank()

    private fun config() = LidarrConfig(
        url = normalizeServerUrl(url), keyEnc = c.integrations.encrypt(key.trim()), rootFolderPath = rootFolder,
        qualityProfileId = qualityProfile, metadataProfileId = metadataProfile, monitor = monitor,
        searchOnAdd = searchOnAdd, useServerTls = useServerTls,
    )

    init {
        if (!isNew) runTest()
    }

    fun runTest() {
        if (!valid) return
        test = TestState.Running
        viewModelScope.launch {
            test = try {
                val config = config()
                val k = key.trim()
                val lidarr = c.integrations.lidarr
                val status = lidarr.status(config, k)
                val r = async { lidarr.rootFolders(config, k) }
                val q = async { lidarr.qualityProfiles(config, k) }
                val m = async { lidarr.metadataProfiles(config, k) }
                val b = async { runCatching { c.brainarr.lists(config, k) }.getOrNull() }
                roots = r.await()
                qualities = q.await()
                metadatas = m.await()
                brainarr = b.await()
                TestState.Passed(
                    "Connected to ${status.instanceName ?: status.appName ?: "Lidarr"} ${status.version.orEmpty()}".trim() +
                        if (roots.isEmpty()) " · no root folder yet, add one in Lidarr" else "",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TestState.Failed(e.userMessage())
            }
        }
    }

    suspend fun save() = c.integrations.repository.update { it.copy(lidarr = config()) }
    suspend fun remove() = c.integrations.repository.update { it.copy(lidarr = null) }
}

@Composable
fun MalojaSettingsScreen() {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val vm = viewModel { MalojaSettingsViewModel(c) }
    val scope = rememberCoroutineScope()
    DetailScaffold(title = "Maloja") {
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Maloja is a self-hosted scrobble server. Tonearm reads your listening history from it to recommend " +
                    "music: what you play most, favourites you've drifted away from, and similar artists.",
                style = MaterialTheme.typography.bodyMedium, color = Hud.colors.dim,
            )
            OutlinedTextField(
                vm.url, { vm.url = it }, label = { Text("Maloja address") }, placeholder = { Text("https://maloja.example.com") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
            )
            SecretInput(vm.key, { vm.key = it }, "API key (needed for scrobbling)")
            ToggleRow(
                "Send plays to Maloja",
                "Only if your music server doesn't already forward scrobbles to Maloja (Navidrome can, through its ListenBrainz support), or you'd count every play twice.",
                vm.scrobble,
            ) { vm.scrobble = it }
            ToggleRow(
                "Use the music server's client certificate",
                "For a Maloja behind the same mTLS reverse proxy as your music server.",
                vm.useServerTls,
            ) { vm.useServerTls = it }
            TestResult(vm.test)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                HudButton("Test", vm::runTest, Modifier.weight(1f), filled = false, enabled = vm.valid && vm.test != TestState.Running)
                HudButton("Save", { scope.launch { vm.save(); actions.back() } }, Modifier.weight(1f), enabled = vm.valid)
            }
            if (!vm.isNew) {
                HudButton("Disconnect Maloja", { scope.launch { vm.remove(); actions.back() } }, Modifier.fillMaxWidth(), filled = false)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private val MONITOR_OPTIONS = listOf(
    "all" to "All albums",
    "future" to "Future albums only",
    "missing" to "Missing albums",
    "latest" to "Latest album",
    "first" to "First album",
    "none" to "None (just add the artist)",
)

@Composable
fun LidarrSettingsScreen() {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val vm = viewModel { LidarrSettingsViewModel(c) }
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }

    DetailScaffold(title = "Lidarr") {
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Lidarr finds and downloads music. Connect it to request artists and albums that aren't in your library, " +
                    "from search or from your Maloja recommendations. Once Lidarr imports them and your server rescans, they show up here.",
                style = MaterialTheme.typography.bodyMedium, color = Hud.colors.dim,
            )
            OutlinedTextField(
                vm.url, { vm.url = it }, label = { Text("Lidarr address") }, placeholder = { Text("https://lidarr.example.com") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
            )
            SecretInput(vm.key, { vm.key = it }, "API key (Lidarr → Settings → General)")
            ToggleRow(
                "Use the music server's client certificate",
                "For a Lidarr behind the same mTLS reverse proxy as your music server.",
                vm.useServerTls,
            ) { vm.useServerTls = it }
            TestResult(vm.test)
            HudButton("Test & load profiles", vm::runTest, Modifier.fillMaxWidth(), filled = false, enabled = vm.valid && vm.test != TestState.Running)

            vm.brainarr?.let { lists ->
                HudSectionHeader("Brainarr")
                Text(
                    if (lists.isEmpty()) {
                        "No Brainarr list in this Lidarr. Brainarr is a plugin that asks an AI model for music like yours; " +
                            "once it's set up as an import list, its picks show up in Discover."
                    } else {
                        lists.joinToString("\n") { list -> "✓ ${list.name}" + (list.summary.takeIf { it.isNotEmpty() }?.let { " — $it" } ?: "") } +
                            "\nIts picks show up in Discover."
                    },
                    style = MaterialTheme.typography.bodyMedium, color = if (lists.isEmpty()) Hud.colors.dim else Hud.colors.text,
                )
            }
            if (vm.roots.isNotEmpty()) {
                HudSectionHeader("Request defaults")
                ChoiceRow("Root folder", vm.rootFolder ?: "${vm.roots.first().path} (first)") {
                    dialog = {
                        ChoiceDialog(
                            "Root folder", listOf<LidarrRootFolder?>(null) + vm.roots, vm.roots.firstOrNull { it.path == vm.rootFolder },
                            { it?.let { r -> r.path + (r.freeSpace?.let { f -> " · ${formatBytes(f)} free" } ?: "") } ?: "First root folder" },
                            { vm.rootFolder = it?.path },
                        ) { dialog = null }
                    }
                }
                ChoiceRow("Quality profile", vm.qualities.firstOrNull { it.id == vm.qualityProfile }?.name ?: "Root folder default") {
                    dialog = {
                        ChoiceDialog(
                            "Quality profile", listOf<LidarrProfile?>(null) + vm.qualities, vm.qualities.firstOrNull { it.id == vm.qualityProfile },
                            { it?.name ?: "Root folder default" }, { vm.qualityProfile = it?.id },
                        ) { dialog = null }
                    }
                }
                ChoiceRow("Metadata profile", vm.metadatas.firstOrNull { it.id == vm.metadataProfile }?.name ?: "Root folder default") {
                    dialog = {
                        ChoiceDialog(
                            "Metadata profile", listOf<LidarrProfile?>(null) + vm.metadatas, vm.metadatas.firstOrNull { it.id == vm.metadataProfile },
                            { it?.name ?: "Root folder default" }, { vm.metadataProfile = it?.id },
                        ) { dialog = null }
                    }
                }
                ChoiceRow("When requesting an artist, monitor", MONITOR_OPTIONS.firstOrNull { it.first == vm.monitor }?.second ?: vm.monitor) {
                    dialog = {
                        ChoiceDialog(
                            "Monitor", MONITOR_OPTIONS, MONITOR_OPTIONS.firstOrNull { it.first == vm.monitor } ?: MONITOR_OPTIONS.first(),
                            { it.second }, { vm.monitor = it.first },
                        ) { dialog = null }
                    }
                }
                ToggleRow("Start searching right away", "Lidarr looks for downloads as soon as you request something.", vm.searchOnAdd) {
                    vm.searchOnAdd = it
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                HudButton("Save", { scope.launch { vm.save(); actions.back() } }, Modifier.weight(1f), enabled = vm.valid)
            }
            if (!vm.isNew) {
                HudButton("Disconnect Lidarr", { scope.launch { vm.remove(); actions.back() } }, Modifier.fillMaxWidth(), filled = false)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    dialog?.invoke()
}

@Composable
private fun SecretInput(value: String, onChange: (String) -> Unit, label: String) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true,
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
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onChange(!checked) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

@Composable
private fun ChoiceRow(title: String, value: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        supportingContent = { Text(value.uppercase(), style = MaterialTheme.typography.labelSmall, color = Hud.colors.accent) },
    )
}

@Composable
private fun TestResult(test: TestState) {
    when (test) {
        TestState.Idle -> Unit
        TestState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text("Connecting…")
        }
        is TestState.Passed -> ResultCard(Icons.Rounded.CheckCircle, test.summary, MaterialTheme.colorScheme.primaryContainer)
        is TestState.Failed -> ResultCard(Icons.Rounded.ErrorOutline, test.message, MaterialTheme.colorScheme.errorContainer)
    }
}

@Composable
private fun ResultCard(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color) {
    Card(colors = CardDefaults.cardColors(containerColor = color)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
