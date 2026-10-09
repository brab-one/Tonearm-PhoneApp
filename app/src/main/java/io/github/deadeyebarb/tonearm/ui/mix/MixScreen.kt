package io.github.deadeyebarb.tonearm.ui.mix

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.deadeyebarb.tonearm.AppContainer
import io.github.deadeyebarb.tonearm.connect.DailyMixes
import io.github.deadeyebarb.tonearm.connect.Mix
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.media.QueueSong
import io.github.deadeyebarb.tonearm.ui.common.AppActions
import io.github.deadeyebarb.tonearm.ui.common.EmptyState
import io.github.deadeyebarb.tonearm.ui.common.ErrorState
import io.github.deadeyebarb.tonearm.ui.common.LocalActions
import io.github.deadeyebarb.tonearm.ui.common.SongRow
import io.github.deadeyebarb.tonearm.ui.common.TextInputDialog
import io.github.deadeyebarb.tonearm.ui.common.formatMs
import io.github.deadeyebarb.tonearm.ui.common.formatTotal
import io.github.deadeyebarb.tonearm.ui.common.plural
import io.github.deadeyebarb.tonearm.ui.detail.DetailScaffold
import io.github.deadeyebarb.tonearm.ui.discover.MixCollage
import io.github.deadeyebarb.tonearm.ui.theme.Hud
import io.github.deadeyebarb.tonearm.ui.theme.HudButton
import io.github.deadeyebarb.tonearm.ui.theme.HudLoader
import io.github.deadeyebarb.tonearm.ui.theme.HudTag
import io.github.deadeyebarb.tonearm.ui.theme.Orbitron
import io.github.deadeyebarb.tonearm.ui.theme.glow
import java.io.IOException
import java.time.LocalDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface MixState {
    /** Asking; [ai] once the server's AI is known to be working on it, since [since] (elapsed realtime). */
    data class Waiting(val since: Long, val ai: Boolean = false) : MixState
    /** [note] says why it isn't quite the one asked for. */
    data class Ready(val mix: Mix, val serverId: String, val note: String? = null) : MixState
    data class Failed(val error: Throwable) : MixState
}

/**
 * A mix from a sentence ([prompt]) or one of today's daily mixes ([dailyId]). Kept across rotations; leaving and asking
 * the same sentence again picks up the server's run where it is.
 */
class MixViewModel(private val c: AppContainer, private val prompt: String?, private val dailyId: String?) : ViewModel() {
    var state: MixState by mutableStateOf(MixState.Waiting(SystemClock.elapsedRealtime()))
        private set
    private var job: Job? = null

    init {
        load(refresh = false)
    }

    /** [refresh] has the AI make the mix again rather than give the one it made earlier today. */
    fun load(refresh: Boolean) {
        job?.cancel()
        state = MixState.Waiting(SystemClock.elapsedRealtime())
        job = viewModelScope.launch {
            state = try {
                val session = c.sessions.awaitActive()
                if (prompt != null) {
                    val mix = c.mixer.prompt(session, prompt, refresh) {
                        // The server's AI is on it: say so, and count from now.
                        if ((state as? MixState.Waiting)?.ai == false) state = MixState.Waiting(SystemClock.elapsedRealtime(), ai = true)
                    }
                    MixState.Ready(mix, session.id)
                } else {
                    val daily = c.mixer.daily(session) ?: throw IOException("Daily mixes come from the Tonearm server (1.7 or later)")
                    if (daily.running) throw IOException("Today's daily mixes are still being made; try again in a minute")
                    val mix = dailyMix(daily, dailyId.orEmpty()) ?: throw IOException("This mix was replaced by today's")
                    MixState.Ready(mix, session.id, note = "The mix you opened was replaced by today's".takeIf { mix.id != dailyId })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MixState.Failed(e)
            }
        }
    }
}

/**
 * The daily mix [id] ("daily-2026-10-09-2") among [daily]'s. One of an earlier day's, from a row shown before 4:00, gives
 * today's at its place; null when there's none.
 */
fun dailyMix(daily: DailyMixes, id: String): Mix? {
    daily.mixes.firstOrNull { it.id == id }?.let { return it }
    val day = id.removePrefix("daily-").substringBeforeLast('-')
    val n = id.substringAfterLast('-')
    if (!id.startsWith("daily-") || day == daily.day) return null
    return daily.mixes.firstOrNull { it.id == "daily-${daily.day}-$n" }
}

/** The day the daily mixes at [now] are for: the server makes new ones at 4:00, so at 1:00 they're still yesterday's. */
fun dailyMixDay(now: LocalDateTime = LocalDateTime.now()): String = now.minusHours(4).toLocalDate().toString()

/** A mix made from a sentence by the server's AI (or matched in the library without it), or a daily mix. */
@Composable
fun MixScreen(prompt: String?, dailyId: String?) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val session by c.sessions.active.collectAsStateWithLifecycle()
    val vm = viewModel(key = "mix|${session?.id}|$prompt|$dailyId") { MixViewModel(c, prompt, dailyId) }
    val state = vm.state
    DetailScaffold(
        title = if (prompt != null) "Mix" else "Daily mix",
        actions = {
            if (prompt != null && state !is MixState.Waiting) IconButton(onClick = { vm.load(refresh = true) }) { Icon(Icons.Rounded.Refresh, "Ask again") }
        },
    ) {
        when (state) {
            is MixState.Waiting -> Waiting(state, prompt)
            is MixState.Failed -> ErrorState(state.error, onRetry = { vm.load(refresh = false) })
            is MixState.Ready -> if (state.mix.songs.isEmpty()) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState(Icons.Rounded.AutoAwesome, "Nothing in your library fits", "Try other words, a genre or an artist.", Modifier.weight(1f))
                    if (prompt != null) HudButton("Try other words", { actions.back() }, Modifier.padding(24.dp), filled = false)
                }
            } else {
                MixList(state.mix, state.serverId, daily = prompt == null, state.note, actions)
            }
        }
    }
}

@Composable
private fun Waiting(state: MixState.Waiting, prompt: String?) {
    val hud = Hud.colors
    val elapsed by produceState(0L, state.since) {
        while (true) {
            value = SystemClock.elapsedRealtime() - state.since
            delay(1000)
        }
    }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HudLoader(label = if (state.ai) "THE AI IS LISTENING…" else "MIXING…")
        if (prompt != null) Text("“$prompt”", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, color = hud.text)
        if (state.ai) {
            Text(formatMs(elapsed), style = MaterialTheme.typography.labelLarge, color = hud.accent)
            Text(
                "Small models take a minute or two. You can leave; it keeps going.",
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = hud.dim,
            )
        }
    }
}

@Composable
private fun MixList(mix: Mix, serverId: String, daily: Boolean, note: String?, actions: AppActions) {
    val c = LocalContext.current.container
    val hud = Hud.colors
    val entries = remember(mix, serverId) { mix.songs.map { QueueSong(serverId, it) } }
    var saving by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MixCollage(mix.songs.mapNotNull { it.coverArt }.distinct().take(4), serverId, Modifier.size(120.dp))
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            mix.title.ifBlank { mix.prompt.orEmpty() }.uppercase(), maxLines = 3, overflow = TextOverflow.Ellipsis,
                            style = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 20.sp).glow(hud.accent.copy(alpha = 0.6f), 18f),
                        )
                        mix.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = hud.dim, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        Text(
                            "${plural(mix.songs.size, "track")} // ${formatTotal(mix.songs.sumOf { it.duration ?: 0 })}".uppercase(),
                            style = MaterialTheme.typography.labelSmall, color = hud.dim,
                        )
                        Spacer(Modifier.height(4.dp))
                        when {
                            daily -> HudTag("From your history", color = hud.accent2)
                            mix.ai != null -> HudTag("AI · ${mix.ai}", color = hud.accent2)
                        }
                    }
                }
                // Made here: says so, and why the AI didn't (when it was asked).
                if (!daily && mix.ai == null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Matched in your library without the AI" + (mix.problem?.let { ": $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = hud.dim,
                    )
                }
                note?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = hud.dim)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HudButton("Play", { actions.playEntries(entries) }, Modifier.weight(1f), icon = Icons.Rounded.PlayArrow)
                    HudButton("Shuffle", { actions.playEntries(entries, shuffle = true) }, Modifier.weight(1f), icon = Icons.Rounded.Shuffle, filled = false)
                }
                Spacer(Modifier.height(10.dp))
                HudButton("Save as playlist", { saving = true }, Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Rounded.PlaylistAdd, filled = false)
            }
        }
        itemsIndexed(mix.songs, key = { i, song -> "$i:${song.id}" }) { i, song ->
            SongRow(song, serverId = serverId, onClick = { actions.playEntries(entries, i) })
        }
    }
    if (saving) {
        TextInputDialog(
            title = "Save as playlist", label = "Name", initial = mix.title.ifBlank { mix.prompt.orEmpty() }, confirmLabel = "Save",
            onConfirm = { name ->
                saving = false
                actions.launch {
                    c.api.createPlaylist(name, mix.songs.map { it.id }, c.sessions.session(serverId))
                    actions.playlistsChanged()
                    actions.message("Saved “$name” with ${plural(mix.songs.size, "song")}")
                }
            },
            onDismiss = { saving = false },
        )
    }
}

/** Home's box for a mix described in a sentence; [onGo] gets the sentence. Under it, what makes the mix. */
@Composable
fun MixPromptField(onGo: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalContext.current.container
    val hud = Hud.colors
    val server by c.tonearmServer.server.collectAsStateWithLifecycle()
    val ai = server?.ai?.takeIf { server?.mix == true }
    var text by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val go = {
        if (text.isNotBlank()) {
            keyboard?.hide()
            onGo(text.trim())
        }
    }
    Column(modifier) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(MAX_PROMPT) },
            placeholder = { Text("DESCRIBE A MIX_", style = MaterialTheme.typography.labelMedium) },
            leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null, tint = hud.accent2) },
            trailingIcon = {
                IconButton(onClick = go, enabled = text.isNotBlank()) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Make the mix") }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = hud.accent,
                unfocusedBorderColor = hud.line,
                focusedContainerColor = hud.panel.copy(alpha = 0.8f),
                unfocusedContainerColor = hud.panel.copy(alpha = 0.6f),
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { go() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            if (ai != null) "✦ " + ai.uppercase() else "No AI on your server: Tonearm matches genres and words in your library",
            style = MaterialTheme.typography.labelSmall, color = hud.dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )
    }
}

/** The server takes up to 300 characters; a mix is described in a lot fewer. */
private const val MAX_PROMPT = 200
