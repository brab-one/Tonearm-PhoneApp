package io.github.deadeyebarb.tonearm.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.subsonic.AlbumListType
import io.github.deadeyebarb.tonearm.ui.common.AlbumGrid
import io.github.deadeyebarb.tonearm.ui.common.AlbumPagingViewModel
import io.github.deadeyebarb.tonearm.ui.common.AlbumRow
import io.github.deadeyebarb.tonearm.ui.common.LoadContent
import io.github.deadeyebarb.tonearm.ui.common.LocalActions
import io.github.deadeyebarb.tonearm.ui.common.PlayShuffleButtons
import io.github.deadeyebarb.tonearm.ui.common.SectionHeader
import io.github.deadeyebarb.tonearm.ui.common.SongRow
import io.github.deadeyebarb.tonearm.ui.common.rememberLoader
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

@Composable
fun GenreScreen(name: String) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val vm = rememberLoader("genre", actions.activeServerId, name) {
        coroutineScope {
            val albums = async { runCatching { c.api.albumsByGenre(name, 30) }.getOrDefault(emptyList()) }
            val songs = async { c.api.songsByGenre(name, 500) }
            albums.await() to songs.await()
        }
    }
    DetailScaffold(title = name) {
        LoadContent(vm) { (albums, songs) ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    PlayShuffleButtons(
                        onPlay = { actions.play(songs) }, onShuffle = { actions.play(songs, shuffle = true) },
                        modifier = Modifier.padding(16.dp),
                    )
                }
                if (albums.isNotEmpty()) {
                    item {
                        Column {
                            SectionHeader("Albums")
                            AlbumRow(albums) { actions.openAlbum(it.id) }
                        }
                    }
                }
                item { SectionHeader("Songs") }
                itemsIndexed(songs, key = { i, s -> "$i:${s.id}" }) { i, song -> SongRow(song, onClick = { actions.play(songs, i) }) }
            }
        }
    }
}

@Composable
fun AlbumListScreen(typeName: String) {
    val c = LocalContext.current.container
    val actions = LocalActions.current
    val type = AlbumListType.valueOf(typeName)
    val vm = viewModel(key = "albumList|${actions.activeServerId}|$typeName") {
        AlbumPagingViewModel { offset -> c.api.albumList(type, AlbumPagingViewModel.PAGE_SIZE, offset) }
    }
    DetailScaffold(title = type.title) { AlbumGrid(vm) }
}
