package io.github.deadeyebarb.tonearm.ui.request

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import io.github.deadeyebarb.tonearm.AppContainer
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.integrations.LidarrCandidate
import io.github.deadeyebarb.tonearm.integrations.LidarrQueueItem
import io.github.deadeyebarb.tonearm.integrations.Recommender
import io.github.deadeyebarb.tonearm.subsonic.userMessage
import io.github.deadeyebarb.tonearm.ui.LidarrSettingsRoute
import io.github.deadeyebarb.tonearm.ui.common.EmptyState
import io.github.deadeyebarb.tonearm.ui.common.ErrorState
import io.github.deadeyebarb.tonearm.ui.common.Load
import io.github.deadeyebarb.tonearm.ui.common.LocalActions
import io.github.deadeyebarb.tonearm.ui.theme.Hud
import io.github.deadeyebarb.tonearm.ui.theme.HudButton
import io.github.deadeyebarb.tonearm.ui.theme.HudLoader
import io.github.deadeyebarb.tonearm.ui.theme.HudSectionHeader
import io.github.deadeyebarb.tonearm.ui.theme.HudTag
import io.github.deadeyebarb.tonearm.ui.theme.HudTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RequestViewModel(private val c: AppContainer) : androidx.lifecycle.ViewModel() {
    var results by mutableStateOf<Load<List<LidarrCandidate>>?>(null)
        private set
    /** Foreign IDs requested during this visit, so their buttons flip right away. */
    var requested by mutableStateOf(setOf<String>())
        private set
    private var job: Job? = null
    private var lastQuery = ""

    fun search(query: String, debounce: Boolean = true) {
        val q = query.trim()
        if (q == lastQuery && results !is Load.Failed) return
        lastQuery = q
        job?.cancel()
        if (q.length < 2) {
            results = null
            return
        }
        job = viewModelScope.launch {
            if (debounce) delay(500)
            results = Load.Loading
            results = try {
                val (config, key) = c.integrations.requireLidarr()
                Load.Ready(c.integrations.lidarr.search(config, key, q))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(e)
            }
        }
    }

    fun request(candidate: LidarrCandidate) {
        viewModelScope.launch {
            try {
                c.integrations.request(candidate)
                requested = requested + candidate.foreignId
                c.messages.show("Requested “${candidate.title}”. Lidarr is on it.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                c.messages.show(e.userMessage())
            }
        }
    }
}

@Composable
fun RequestScreen(initialQuery: String) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val integrations by c.integrations.state.collectAsStateWithLifecycle()
    val vm = viewModel { RequestViewModel(c) }
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(query) { vm.search(query) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            HudTopBar(
                title = "Request music",
                subtitle = "LIDARR // ADD ARTISTS AND ALBUMS TO YOUR LIBRARY",
                navigationIcon = { IconButton(onClick = { actions.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { actions.navigate(LidarrSettingsRoute) }) { Icon(Icons.Rounded.Settings, "Lidarr settings") } },
            )
        },
    ) { padding ->
        if (integrations.lidarr == null) {
            Column(Modifier.padding(padding).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyState(
                    Icons.Rounded.CloudDownload, "Lidarr not connected",
                    "Connect your Lidarr to request artists and albums that aren't in your library yet.",
                    Modifier.weight(1f),
                )
                HudButton("Connect Lidarr", { actions.navigate(LidarrSettingsRoute) }, Modifier.padding(24.dp))
            }
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("SEARCH MUSICBRAINZ VIA LIDARR_", style = MaterialTheme.typography.labelMedium) },
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Hud.colors.accent) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Hud.colors.accent, unfocusedBorderColor = Hud.colors.line,
                    focusedContainerColor = Hud.colors.panel.copy(alpha = 0.8f), unfocusedContainerColor = Hud.colors.panel.copy(alpha = 0.6f),
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    vm.search(query, debounce = false)
                }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            when (val state = vm.results) {
                null -> QueueSection(c)
                Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { HudLoader(label = "QUERYING LIDARR") }
                is Load.Failed -> ErrorState(state.error.userMessage(), onRetry = { vm.search(query, debounce = false) })
                is Load.Ready -> if (state.value.isEmpty()) {
                    EmptyState(Icons.Rounded.Search, "Nothing found", "Lidarr found no artist or album for “${query.trim()}”.")
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(state.value, key = { (if (it.isAlbum) "al:" else "ar:") + it.foreignId }) { candidate ->
                            CandidateRow(candidate, requested = candidate.foreignId in vm.requested, onRequest = { vm.request(candidate) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CandidateRow(candidate: LidarrCandidate, requested: Boolean, stacked: Boolean = false, onRequest: () -> Unit) {
    val hud = Hud.colors
    var details by remember { mutableStateOf(false) }
    val status = @Composable {
        when {
            candidate.inLidarr -> HudTag("IN LIDARR", color = hud.ok)
            requested -> HudTag("REQUESTED", color = hud.ok, filled = true)
            else -> HudButton("Request", onRequest, filled = false)
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = if (stacked) 0.dp else 16.dp, vertical = 8.dp),
        verticalAlignment = if (stacked) Alignment.Top else Alignment.CenterVertically,
    ) {
        RemoteCover(candidate.imageUrl, if (candidate.isAlbum) Icons.Rounded.Album else Icons.Rounded.Person, Modifier.size(64.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(candidate.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(candidate.subtitle.uppercase(), style = MaterialTheme.typography.labelSmall, color = hud.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            candidate.overview?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it, style = MaterialTheme.typography.bodySmall, color = hud.dim, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    "MORE ›", style = MaterialTheme.typography.labelSmall, color = hud.accent,
                    modifier = Modifier.clickable { details = true }.padding(vertical = 6.dp),
                )
            }
            if (stacked) {
                Spacer(Modifier.height(6.dp))
                status()
            }
        }
        if (!stacked) {
            Spacer(Modifier.width(8.dp))
            status()
        }
    }
    if (details) {
        AlertDialog(
            onDismissRequest = { details = false },
            title = { Text(candidate.title) },
            text = { Text(candidate.overview.orEmpty(), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = { details = false }) { Text("CLOSE") } },
        )
    }
}

/** Artwork from MusicBrainz / fanart.tv (public URLs), with an icon until it loads. */
@Composable
fun RemoteCover(
    url: String?,
    placeholder: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.small,
) {
    val hud = Hud.colors
    Box(
        modifier.clip(shape).border(1.dp, hud.line, shape).padding(0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(placeholder, null, tint = hud.dim.copy(alpha = 0.5f))
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun QueueSection(c: AppContainer) {
    val hud = Hud.colors
    // Poll Lidarr's queue while this screen is open.
    val queue by produceState<Load<List<LidarrQueueItem>>>(Load.Loading) {
        while (true) {
            value = try {
                val (config, key) = c.integrations.requireLidarr()
                Load.Ready(c.integrations.lidarr.queue(config, key))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(e)
            }
            delay(5_000)
        }
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item { HudSectionHeader("Downloading in Lidarr") }
        when (val q = queue) {
            Load.Loading -> item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { HudLoader(label = "CHECKING QUEUE") } }
            is Load.Failed -> item {
                Text(q.error.userMessage(), color = hud.danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
            }
            is Load.Ready -> if (q.value.isEmpty()) {
                item {
                    Text(
                        "Nothing downloading right now. Search above to request an artist or album; " +
                            "when Lidarr finishes and your server rescans, it shows up in your library.",
                        style = MaterialTheme.typography.bodyMedium, color = hud.dim, modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            } else {
                items(q.value, key = { it.id }) { item ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            listOfNotNull(item.album?.title, item.artist?.artistName).joinToString(" — ").ifEmpty { item.title.orEmpty() },
                            style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HudTag(item.trackedDownloadState ?: item.status ?: "queued", color = if (item.errorMessage != null) hud.danger else hud.accent)
                            Text(
                                listOfNotNull("${(item.progress * 100).toInt()}%", item.timeleft?.let { "ETA $it" }).joinToString(" // "),
                                style = MaterialTheme.typography.labelSmall, color = hud.dim,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
                        item.errorMessage?.let { Text(it, color = hud.danger, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}

/** Request a recommended artist by name: looks it up in Lidarr and lets you pick the right match. */
@Composable
fun LidarrRequestDialog(name: String, onDismiss: () -> Unit) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val hud = Hud.colors
    val scope = rememberCoroutineScope()
    var requested by remember { mutableStateOf(setOf<String>()) }
    val matches by produceState<Load<List<LidarrCandidate>>>(Load.Loading, name) {
        value = try {
            val (config, key) = c.integrations.requireLidarr()
            val found = c.integrations.lidarr.lookupArtist(config, key, name)
            // Exact name matches first, then whatever else MusicBrainz suggests.
            Load.Ready(found.sortedByDescending { Recommender.normalize(it.title) == Recommender.normalize(name) }.take(5))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Failed(e)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Request “$name”") },
        text = {
            when (val m = matches) {
                Load.Loading -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { HudLoader(label = "LOOKING UP") }
                is Load.Failed -> Text(m.error.userMessage(), color = hud.danger)
                is Load.Ready -> if (m.value.isEmpty()) {
                    Text("Lidarr couldn't find this artist on MusicBrainz.")
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(m.value, key = { it.foreignId }) { candidate ->
                            CandidateRow(candidate, requested = candidate.foreignId in requested, stacked = true) {
                                scope.launch {
                                    try {
                                        c.integrations.request(candidate)
                                        requested = requested + candidate.foreignId
                                        actions.requestedArtists += Recommender.normalize(name)
                                        c.daily.markRequested(listOf(Recommender.normalize(name)))
                                        c.messages.show("Requested “${candidate.title}”. Lidarr is on it.")
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        c.messages.show(e.userMessage())
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("DONE") } },
    )
}
