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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.deadeyebarb.tonearm.AppContainer
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.data.LidarrConfig
import io.github.deadeyebarb.tonearm.integrations.IntegrationNotConfiguredException
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

/** Lidarr comes through the Tonearm server; what's kept here is how requests are made. */
class LidarrSettingsViewModel(private val c: AppContainer) : ViewModel() {
    private val existing = c.integrations.repository.state.value.lidarr
    var rootFolder by mutableStateOf(existing?.rootFolderPath)
    var qualityProfile by mutableStateOf(existing?.qualityProfileId)
    var metadataProfile by mutableStateOf(existing?.metadataProfileId)
    var monitor by mutableStateOf(existing?.monitor ?: "all")
    var searchOnAdd by mutableStateOf(existing?.searchOnAdd ?: true)

    var test by mutableStateOf<TestState>(TestState.Running)
        private set
    var roots by mutableStateOf<List<LidarrRootFolder>>(emptyList())
        private set
    var qualities by mutableStateOf<List<LidarrProfile>>(emptyList())
        private set
    var metadatas by mutableStateOf<List<LidarrProfile>>(emptyList())
        private set

    init {
        viewModelScope.launch {
            test = try {
                val config = c.integrations.current().lidarr
                    ?: throw IntegrationNotConfiguredException("The Tonearm server doesn't offer Lidarr: set LIDARR_URL and LIDARR_API_KEY there")
                val lidarr = c.integrations.lidarr
                val status = lidarr.status(config, "")
                val r = async { lidarr.rootFolders(config, "") }
                val q = async { lidarr.qualityProfiles(config, "") }
                val m = async { lidarr.metadataProfiles(config, "") }
                // Users' own picks folders aren't where requests go.
                roots = r.await().filterNot { it.isPicks }
                qualities = q.await()
                metadatas = m.await()
                TestState.Passed(
                    "Lidarr ${status.version.orEmpty()} through the Tonearm server".replace("  ", " ") +
                        if (roots.isEmpty()) " · no root folder yet, add one in Lidarr" else "",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TestState.Failed(e.userMessage())
            }
        }
    }

    suspend fun save() = c.integrations.repository.update {
        it.copy(
            lidarr = (it.lidarr ?: LidarrConfig(url = "")).copy(
                rootFolderPath = rootFolder, qualityProfileId = qualityProfile, metadataProfileId = metadataProfile,
                monitor = monitor, searchOnAdd = searchOnAdd,
            ),
        )
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
                "Lidarr finds and downloads music: request artists and albums that aren't in your library, from search or " +
                    "your recommendations. It comes through the Tonearm server on your music server, which holds its key, so " +
                    "there's nothing to connect here. Once Lidarr imports what you asked for and your server rescans, it shows up here.",
                style = MaterialTheme.typography.bodyMedium, color = Hud.colors.dim,
            )
            val integrations by c.integrations.state.collectAsStateWithLifecycle()
            val server by c.tonearmServer.server.collectAsStateWithLifecycle()
            if (integrations.lidarr?.limited == true) {
                Text(
                    if (server?.picksFolder != null) "You can request music, see downloads and get weekly picks of your own; removing music is for the server's admins."
                    else "You can request music and see downloads; weekly picks and removing music are for the server's admins.",
                    style = MaterialTheme.typography.bodyMedium, color = Hud.colors.accent,
                )
            }
            TestResult(vm.test)
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
                HudButton("Save", { scope.launch { vm.save(); actions.back() } }, Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    dialog?.invoke()
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
