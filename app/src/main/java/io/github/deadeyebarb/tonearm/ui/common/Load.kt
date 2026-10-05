package io.github.deadeyebarb.tonearm.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
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
import io.github.deadeyebarb.tonearm.subsonic.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val error: Throwable) : Load<Nothing>
}

/** Loads one value for a screen and keeps it across configuration changes. */
class LoaderViewModel<T>(private val loader: suspend () -> T) : ViewModel() {
    var state: Load<T> by mutableStateOf(Load.Loading)
        private set
    var refreshing by mutableStateOf(false)
        private set

    init {
        reload()
    }

    /** With [silent], keeps showing the current value while reloading (pull to refresh). */
    fun reload(silent: Boolean = false) {
        viewModelScope.launch {
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
fun <T> rememberLoader(vararg keys: Any?, loader: suspend () -> T): LoaderViewModel<T> =
    viewModel(key = keys.joinToString("|")) { LoaderViewModel(loader) }

@Composable
fun <T> LoadContent(
    vm: LoaderViewModel<T>,
    modifier: Modifier = Modifier,
    errorExtra: @Composable () -> Unit = {},
    content: @Composable (T) -> Unit,
) {
    when (val state = vm.state) {
        Load.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { HudLoader() }
        is Load.Failed -> ErrorState(state.error.userMessage(), onRetry = { vm.reload() }, modifier = modifier, extra = errorExtra)
        is Load.Ready -> PullToRefreshBox(
            isRefreshing = vm.refreshing,
            onRefresh = { vm.reload(silent = true) },
            modifier = modifier.fillMaxSize(),
        ) { content(state.value) }
    }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier, extra: @Composable () -> Unit = {}) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.CloudOff, null, Modifier.size(48.dp), tint = Hud.colors.danger)
        Text("SIGNAL LOST", style = MaterialTheme.typography.titleLarge.glow(Hud.colors.danger, 14f), color = Hud.colors.danger)
        Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = Hud.colors.text)
        HudButton("Retry", onRetry)
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
