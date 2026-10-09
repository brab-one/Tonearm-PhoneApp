package io.github.deadeyebarb.tonearm.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
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
import io.github.deadeyebarb.tonearm.ui.common.formatBytes
import io.github.deadeyebarb.tonearm.ui.detail.DetailScaffold
import io.github.deadeyebarb.tonearm.ui.theme.Hud
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
        connect()
    }

    fun connect() {
        test = TestState.Running
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

    /** Saves the choices as they are now; each one is saved as soon as it's made, so Back never drops one. */
    fun save() {
        val root = rootFolder
        val quality = qualityProfile
        val metadata = metadataProfile
        val monitorWhat = monitor
        val search = searchOnAdd
        c.scope.launch {
            c.integrations.repository.update {
                it.copy(
                    lidarr = (it.lidarr ?: LidarrConfig(url = "")).copy(
                        rootFolderPath = root, qualityProfileId = quality, metadataProfileId = metadata,
                        monitor = monitorWhat, searchOnAdd = search,
                    ),
                )
            }
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
    val vm = viewModel { LidarrSettingsViewModel(c) }
    var dialog by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }

    DetailScaffold(title = "Lidarr") {
        // Rows bring their own side padding; text and cards get the same.
        val inset = Modifier.padding(horizontal = 16.dp)
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Lidarr finds and downloads music: request artists and albums that aren't in your library, from search or " +
                    "your recommendations. It comes through the Tonearm server on your music server, which holds its key, so " +
                    "there's nothing to connect here. Once Lidarr imports what you asked for and your server rescans, it shows up here.",
                style = MaterialTheme.typography.bodyMedium, color = Hud.colors.dim, modifier = inset,
            )
            val integrations by c.integrations.state.collectAsStateWithLifecycle()
            val server by c.tonearmServer.server.collectAsStateWithLifecycle()
            if (integrations.lidarr?.limited == true) {
                Text(
                    if (server?.picksFolder != null) "You can request music, see downloads and get weekly picks of your own; removing music is for the server's admins."
                    else "You can request music and see downloads; weekly picks and removing music are for the server's admins.",
                    style = MaterialTheme.typography.bodyMedium, color = Hud.colors.accent, modifier = inset,
                )
            }
            Box(inset) { TestResult(vm.test, onRetry = vm::connect) }
            if (vm.roots.isNotEmpty()) {
                HudSectionHeader("Request defaults")
                ChoiceRow("Root folder", vm.rootFolder ?: "${vm.roots.first().path} (first)") {
                    dialog = {
                        ChoiceDialog(
                            "Root folder", listOf<LidarrRootFolder?>(null) + vm.roots, vm.roots.firstOrNull { it.path == vm.rootFolder },
                            { it?.let { r -> r.path + (r.freeSpace?.let { f -> " · ${formatBytes(f)} free" } ?: "") } ?: "First root folder" },
                            { vm.rootFolder = it?.path; vm.save() },
                        ) { dialog = null }
                    }
                }
                ChoiceRow("Quality profile", vm.qualities.firstOrNull { it.id == vm.qualityProfile }?.name ?: "Root folder default") {
                    dialog = {
                        ChoiceDialog(
                            "Quality profile", listOf<LidarrProfile?>(null) + vm.qualities, vm.qualities.firstOrNull { it.id == vm.qualityProfile },
                            { it?.name ?: "Root folder default" }, { vm.qualityProfile = it?.id; vm.save() },
                        ) { dialog = null }
                    }
                }
                ChoiceRow("Metadata profile", vm.metadatas.firstOrNull { it.id == vm.metadataProfile }?.name ?: "Root folder default") {
                    dialog = {
                        ChoiceDialog(
                            "Metadata profile", listOf<LidarrProfile?>(null) + vm.metadatas, vm.metadatas.firstOrNull { it.id == vm.metadataProfile },
                            { it?.name ?: "Root folder default" }, { vm.metadataProfile = it?.id; vm.save() },
                        ) { dialog = null }
                    }
                }
                ChoiceRow("When requesting an artist, monitor", MONITOR_OPTIONS.firstOrNull { it.first == vm.monitor }?.second ?: vm.monitor) {
                    dialog = {
                        ChoiceDialog(
                            "Monitor", MONITOR_OPTIONS, MONITOR_OPTIONS.firstOrNull { it.first == vm.monitor } ?: MONITOR_OPTIONS.first(),
                            { it.second }, { vm.monitor = it.first; vm.save() },
                        ) { dialog = null }
                    }
                }
                ToggleRow("Start searching right away", "Lidarr looks for downloads as soon as you request something.", vm.searchOnAdd) {
                    vm.searchOnAdd = it
                    vm.save()
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    dialog?.invoke()
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    // The whole row is the switch, so TalkBack reads its name and state together.
    ListItem(
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
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
private fun TestResult(test: TestState, onRetry: () -> Unit) {
    when (test) {
        TestState.Idle -> Unit
        TestState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text("Connecting…")
        }
        is TestState.Passed -> ResultCard(Icons.Rounded.CheckCircle, test.summary, MaterialTheme.colorScheme.primaryContainer)
        is TestState.Failed -> ResultCard(Icons.Rounded.ErrorOutline, test.message, MaterialTheme.colorScheme.errorContainer, onRetry)
    }
}

@Composable
private fun ResultCard(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color, onRetry: (() -> Unit)? = null) {
    Card(colors = CardDefaults.cardColors(containerColor = color)) {
        Row(Modifier.padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = if (onRetry != null) 8.dp else 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
            if (onRetry != null) TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}
