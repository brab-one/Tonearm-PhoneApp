package io.github.deadeyebarb.tonearm.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.deadeyebarb.tonearm.subsonic.Album
import io.github.deadeyebarb.tonearm.subsonic.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Endless album list for the server's paged getAlbumList2 endpoint. */
class AlbumPagingViewModel(private val load: suspend (offset: Int) -> List<Album>) : ViewModel() {
    val albums = mutableStateListOf<Album>()
    var loading by mutableStateOf(false)
        private set
    var endReached by mutableStateOf(false)
        private set
    var error by mutableStateOf<Throwable?>(null)
        private set
    private val seen = HashSet<String>()

    init {
        loadMore()
    }

    fun loadMore() {
        if (loading || endReached) return
        loading = true
        viewModelScope.launch {
            try {
                val page = load(albums.size)
                albums += page.filter { seen.add(it.id) }
                if (page.size < PAGE_SIZE) endReached = true
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
            } finally {
                loading = false
            }
        }
    }

    companion object {
        const val PAGE_SIZE = 60
    }
}

@Composable
fun AlbumGrid(vm: AlbumPagingViewModel, modifier: Modifier = Modifier, header: LazyGridScope.() -> Unit = {}) {
    val actions = LocalActions.current
    val gridState = rememberLazyGridState()
    val nearEnd by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 12
        }
    }
    LaunchedEffect(nearEnd, vm.albums.size) { if (nearEnd) vm.loadMore() }
    val error = vm.error
    when {
        vm.albums.isEmpty() && error != null -> ErrorState(error.userMessage(), onRetry = vm::loadMore, modifier = modifier)
        vm.albums.isEmpty() && vm.endReached -> EmptyState(Icons.Rounded.Album, "No albums here", modifier = modifier)
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(152.dp),
            state = gridState,
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(10.dp),
        ) {
            header()
            items(vm.albums, key = { it.id }) { album -> AlbumCard(album) { actions.openAlbum(album.id) } }
            if (vm.loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
        }
    }
}
