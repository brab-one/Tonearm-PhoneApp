package io.github.deadeyebarb.tonearm.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import io.github.deadeyebarb.tonearm.ui.theme.Hud
import io.github.deadeyebarb.tonearm.ui.theme.HudButton
import io.github.deadeyebarb.tonearm.ui.theme.HudLoader
import io.github.deadeyebarb.tonearm.ui.theme.glow
import io.github.deadeyebarb.tonearm.ui.theme.glowBorder
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApiException
import io.github.deadeyebarb.tonearm.subsonic.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val error: Throwable) : Load<Nothing>
}

/** Loads one value for a screen and keeps it across configuration changes; again, quietly, each time [reloadOn] moves on. */
class LoaderViewModel<T>(private val loader: suspend () -> T, reloadOn: Flow<*>? = null) : ViewModel() {
    var state: Load<T> by mutableStateOf(Load.Loading)
        private set
    var refreshing by mutableStateOf(false)
        private set

    private var loading: Job? = null

    init {
        reload()
        if (reloadOn != null) viewModelScope.launch { reloadOn.drop(1).collect { reload(silent = true) } }
    }

    /** With [silent], keeps showing the current value while reloading (pull to refresh). A newer reload replaces an older one. */
    fun reload(silent: Boolean = false) {
        loading?.cancel()
        loading = viewModelScope.launch {
            if (silent) refreshing = true else if (state !is Load.Ready) state = Load.Loading
            state = try {
                Load.Ready(loader())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (silent && state is Load.Ready) state else Load.Failed(e)
            }
            refreshing = false
        }
    }

    fun mutate(transform: (T) -> T) {
        val current = state
        if (current is Load.Ready) state = Load.Ready(transform(current.value))
    }

    val value: T? get() = (state as? Load.Ready)?.value
}

@Composable
fun <T> rememberLoader(vararg keys: Any?, reloadOn: Flow<*>? = null, loader: suspend () -> T): LoaderViewModel<T> =
    viewModel(key = keys.joinToString("|")) { LoaderViewModel(loader, reloadOn) }

@Composable
fun <T> LoadContent(
    vm: LoaderViewModel<T>,
    modifier: Modifier = Modifier,
    errorExtra: @Composable () -> Unit = {},
    content: @Composable (T) -> Unit,
) {
    when (val state = vm.state) {
        Load.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { HudLoader() }
        is Load.Failed -> ErrorState(state.error, onRetry = { vm.reload() }, modifier = modifier, extra = errorExtra)
        is Load.Ready -> PullToRefreshBox(
            isRefreshing = vm.refreshing,
            onRefresh = { vm.reload(silent = true) },
            modifier = modifier.fillMaxSize(),
        ) { content(state.value) }
    }
}

/** What went wrong with [error]. Something that's gone or not allowed says so, without a Retry that can't help. */
@Composable
fun ErrorState(error: Throwable, onRetry: () -> Unit, modifier: Modifier = Modifier, extra: @Composable () -> Unit = {}) {
    when ((error as? SubsonicApiException)?.code) {
        70 -> ErrorState(error.userMessage(), null, modifier, extra, title = "GONE", icon = Icons.Rounded.SearchOff)
        50 -> ErrorState(error.userMessage(), null, modifier, extra, title = "NOT ALLOWED", icon = Icons.Rounded.Block)
        else -> ErrorState(error.userMessage(), onRetry, modifier, extra)
    }
}

@Composable
fun ErrorState(
    message: String,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
    extra: @Composable () -> Unit = {},
    title: String = "SIGNAL LOST",
    icon: ImageVector = Icons.Rounded.CloudOff,
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(48.dp), tint = Hud.colors.danger)
        Text(title, style = MaterialTheme.typography.titleLarge.glow(Hud.colors.danger, 14f), color = Hud.colors.danger)
        Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = Hud.colors.text)
        if (onRetry != null) HudButton("Retry", onRetry)
        extra()
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(84.dp).glowBorder(Hud.colors.accent.copy(alpha = 0.6f), CircleShape, glow = 10.dp),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(40.dp), tint = Hud.colors.accent) }
        Text(title.uppercase(), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (subtitle != null) {
            Text(
                subtitle, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
