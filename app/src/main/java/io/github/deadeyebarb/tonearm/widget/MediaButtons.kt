package io.github.deadeyebarb.tonearm.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import io.github.deadeyebarb.tonearm.playback.PlaybackService

/**
 * Media keys straight to [PlaybackService], as the media notification and Bluetooth send them: the session handles
 * them, and Play with nothing loaded brings back the saved queue.
 */
object MediaButtons {
    fun intent(context: Context, keyCode: Int): Intent = Intent(Intent.ACTION_MEDIA_BUTTON)
        .setClass(context, PlaybackService::class.java)
        .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))

    /** Play may have to start the service, so it starts it in the foreground; the other keys find it running. */
    fun pendingIntent(context: Context, keyCode: Int): PendingIntent {
        val intent = intent(context, keyCode)
        return if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            PendingIntent.getForegroundService(context, keyCode, intent, PendingIntent.FLAG_IMMUTABLE)
        } else {
            PendingIntent.getService(context, keyCode, intent, PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
