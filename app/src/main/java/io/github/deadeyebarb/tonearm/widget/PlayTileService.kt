package io.github.deadeyebarb.tonearm.widget

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import io.github.deadeyebarb.tonearm.MainActivity
import io.github.deadeyebarb.tonearm.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The quick settings tile: lit while Tonearm plays, the song underneath; a tap plays or pauses. */
class PlayTileService : TileService() {
    private var watching: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val c = container
        watching?.cancel()
        watching = c.scope.launch(Dispatchers.Main) { c.nowPlaying.collect(::show) }
    }

    override fun onStopListening() {
        watching?.cancel()
        watching = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val card = container.nowPlaying.value ?: return openApp(false)
        val key = if (card.live && card.playing) KeyEvent.KEYCODE_MEDIA_PAUSE else KeyEvent.KEYCODE_MEDIA_PLAY
        try {
            // Pause finds the player running; play may have to start it.
            if (key == KeyEvent.KEYCODE_MEDIA_PAUSE) startService(MediaButtons.intent(this, key))
            else ContextCompat.startForegroundService(this, MediaButtons.intent(this, key))
        } catch (e: IllegalStateException) {
            // Android didn't let it start from here (ForegroundServiceStartNotAllowedException): the app plays it.
            Log.i(TAG, "Couldn't start playback from the tile, opening the app", e)
            openApp(true)
        }
    }

    private fun show(card: NowPlayingCard?) {
        val tile = qsTile ?: return
        val playing = card != null && card.live && card.playing
        tile.state = when {
            card == null -> Tile.STATE_UNAVAILABLE
            playing -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                card == null -> "Nothing to play"
                playing -> card.title
                else -> "Paused · " + card.title
            }
        }
        tile.contentDescription = if (card == null) "Tonearm, nothing to play" else "Tonearm, ${card.title} by ${card.artist}, " + if (playing) "playing" else "paused"
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp(player: Boolean) {
        val intent = Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, player).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private companion object {
        const val TAG = "PlayTileService"
    }
}
