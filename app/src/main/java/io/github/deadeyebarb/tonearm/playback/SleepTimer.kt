package io.github.deadeyebarb.tonearm.playback

import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SleepTimer(private val volume: VolumeMixer) {
    sealed interface State {
        data object Off : State
        data class At(val endsAtElapsedMs: Long) : State
        data object EndOfTrack : State
    }

    private val scope = MainScope()
    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state
    private var player: ExoPlayer? = null
    private var job: Job? = null

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && _state.value == State.EndOfTrack) {
                player?.pauseAtEndOfMediaItems = false
                _state.value = State.Off
            }
        }
    }

    fun attach(player: ExoPlayer) {
        this.player = player
        player.addListener(listener)
        if (_state.value == State.EndOfTrack) player.pauseAtEndOfMediaItems = true
    }

    fun detach() {
        player?.removeListener(listener)
        player = null
    }

    /** Pauses after [minutes], fading out over the last few seconds. */
    fun start(minutes: Int) {
        cancel()
        val end = SystemClock.elapsedRealtime() + minutes * 60_000L
        _state.value = State.At(end)
        job = scope.launch {
            delay((end - FADE_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            while (true) {
                val left = end - SystemClock.elapsedRealtime()
                if (left <= 0) break
                volume.fade = left.toFloat() / FADE_MS
                delay(100)
            }
            player?.pause()
            volume.fade = 1f
            _state.value = State.Off
        }
    }

    fun stopAfterCurrentTrack() {
        cancel()
        player?.pauseAtEndOfMediaItems = true
        _state.value = State.EndOfTrack
    }

    fun cancel() {
        job?.cancel()
        job = null
        volume.fade = 1f
        if (_state.value == State.EndOfTrack) player?.pauseAtEndOfMediaItems = false
        _state.value = State.Off
    }

    private companion object {
        const val FADE_MS = 15_000L
    }
}
