package io.github.deadeyebarb.tonearm.widget

import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.KeyEvent
import android.view.View
import android.widget.RemoteViews
import androidx.core.graphics.scale
import io.github.deadeyebarb.tonearm.AppContainer
import io.github.deadeyebarb.tonearm.MainActivity
import io.github.deadeyebarb.tonearm.R
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.data.AccentColor
import io.github.deadeyebarb.tonearm.media.ArtworkCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The home screen player: the song playing or paused, with previous, play/pause and next. */
class PlayerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val c = context.container
        val pending = goAsync()
        c.scope.launch {
            try {
                PlayerWidgets.render(context, c.nowPlaying.value, c.settings.state.value.accent)
            } finally {
                pending.finish()
            }
        }
    }
}

object PlayerWidgets {
    /** The cover shown last, so a play or a pause doesn't load it again. */
    @Volatile private var cover: Pair<String, Bitmap?>? = null

    /** Keeps the widgets on the song the app plays; a quick run of skips draws once. */
    @OptIn(FlowPreview::class)
    fun start(app: Application, c: AppContainer) {
        c.scope.launch {
            combine(c.nowPlaying, c.settings.state.map { it.accent }.distinctUntilChanged(), ::Pair)
                .debounce(250)
                .collectLatest { (card, accent) -> render(app, card, accent) }
        }
    }

    suspend fun render(context: Context, card: NowPlayingCard?, accent: AccentColor) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, PlayerWidget::class.java))
        if (ids.isEmpty()) return
        val art = card?.let { coverOf(context, it) }
        manager.updateAppWidget(ids, views(context, card, accent, art))
    }

    private fun views(context: Context, card: NowPlayingCard?, accent: AccentColor, art: Bitmap?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_player)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, card != null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.widget_root, open)
        if (art != null) views.setImageViewBitmap(R.id.widget_cover, art) else views.setImageViewResource(R.id.widget_cover, R.mipmap.ic_launcher)
        val accent2 = accent.secondary.toInt()
        views.setTextColor(R.id.widget_artist, accent2)
        for (id in listOf(R.id.widget_prev, R.id.widget_play, R.id.widget_next)) views.setInt(id, "setColorFilter", accent.primary.toInt())

        if (card == null) {
            views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_empty_title))
            views.setTextViewText(R.id.widget_artist, context.getString(R.string.widget_empty))
            for (id in listOf(R.id.widget_prev, R.id.widget_play, R.id.widget_next)) views.setViewVisibility(id, View.GONE)
            return views
        }
        views.setTextViewText(R.id.widget_title, card.title.uppercase())
        views.setTextViewText(R.id.widget_artist, card.artist)
        val playing = card.live && card.playing
        views.setViewVisibility(R.id.widget_play, View.VISIBLE)
        views.setImageViewResource(R.id.widget_play, if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
        views.setContentDescription(R.id.widget_play, context.getString(if (playing) R.string.widget_pause else R.string.widget_play))
        // Play plays what it shows: the song again once the queue ended (the session's play/pause would pause first, as
        // play is still wanted then), or the saved song when the player is gone. Skipping needs it running.
        views.setOnClickPendingIntent(
            R.id.widget_play,
            MediaButtons.pendingIntent(context, if (playing) KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE else KeyEvent.KEYCODE_MEDIA_PLAY),
        )
        for ((id, key) in listOf(R.id.widget_prev to KeyEvent.KEYCODE_MEDIA_PREVIOUS, R.id.widget_next to KeyEvent.KEYCODE_MEDIA_NEXT)) {
            views.setViewVisibility(id, if (card.live) View.VISIBLE else View.GONE)
            views.setOnClickPendingIntent(id, MediaButtons.pendingIntent(context, key))
        }
        return views
    }

    /** The song's cover, at most 256 px (a widget's bitmaps travel to the launcher); null when there's none. */
    private suspend fun coverOf(context: Context, card: NowPlayingCard): Bitmap? {
        val serverId = card.serverId ?: return null
        val coverId = card.coverArt ?: return null
        val key = "$serverId/$coverId"
        cover?.takeIf { it.first == key }?.let { return it.second }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val file = ArtworkCache.fetch(context, serverId, coverId)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_COVER) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.let {
                    val scale = MAX_COVER.toFloat() / maxOf(it.width, it.height)
                    if (scale < 1) it.scale((it.width * scale).toInt(), (it.height * scale).toInt()) else it
                }
            }.getOrNull()
        }
        // One that couldn't be had (offline, say) is tried again next time.
        if (bitmap != null) cover = key to bitmap
        return bitmap
    }

    private const val MAX_COVER = 256
}
