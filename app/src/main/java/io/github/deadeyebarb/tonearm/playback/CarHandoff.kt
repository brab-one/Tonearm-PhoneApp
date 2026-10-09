package io.github.deadeyebarb.tonearm.playback

import android.util.Log
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import io.github.deadeyebarb.tonearm.AppContainer
import io.github.deadeyebarb.tonearm.connect.ConnectCommand
import io.github.deadeyebarb.tonearm.connect.DeviceState
import io.github.deadeyebarb.tonearm.connect.Handoff
import io.github.deadeyebarb.tonearm.connect.Handoffs
import io.github.deadeyebarb.tonearm.connect.toQueueSong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The session's player. While [hold] is set, play only notes that it's wanted, so a car that starts the music by itself
 * doesn't play the phone's old song for a moment before the desktop's takes its place.
 */
class HandoffGatePlayer(player: Player) : ForwardingPlayer(player) {
    var hold = false
    private var playWanted = false

    override fun play() {
        if (hold) playWanted = true else super.play()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (hold && playWhenReady) {
            playWanted = true
        } else {
            if (!playWhenReady) playWanted = false
            super.setPlayWhenReady(playWhenReady)
        }
    }

    override fun pause() {
        playWanted = false
        super.pause()
    }

    /** Ends the hold; true when play was asked for meanwhile. */
    fun endHold(): Boolean {
        hold = false
        return playWanted.also { playWanted = false }
    }
}

/**
 * Continue from the desktop: when the car starts and the phone isn't playing, the phone takes over the queue the desktop
 * played last (its song, place and queue), if the desktop played after the phone did. The desktop is paused once the
 * phone really plays.
 */
class CarHandoff(
    private val c: AppContainer,
    private val player: Player,
    private val gate: HandoffGatePlayer,
    private val scope: CoroutineScope,
    /** Waits until the saved queue is back in the player. */
    private val restored: suspend () -> Unit,
    /** When this phone last played (ms). */
    private val phonePlayedAt: () -> Long,
    /** The desktop's queue is about to replace this one's. */
    private val beforeTakeOver: () -> Unit,
) : Player.Listener {
    private val cars = mutableSetOf<MediaSession.ControllerInfo>()
    /** When the last car controller went away; 0 before any came. */
    private var carLeftAt = 0L
    private var decision: Job? = null
    /** How often something was picked since the service started ([picked]), to see the user chose something during the lookup. */
    private var picks = 0
    /** The device to pause once this phone plays. */
    private var pauseOnPlay: String? = null

    fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
        if (!isCar(session, controller)) return
        // One drive until every car controller has been gone a while (switching apps, a reconnect).
        val newDrive = cars.isEmpty() && (carLeftAt == 0L || System.currentTimeMillis() - carLeftAt > NEW_DRIVE_MS)
        cars += controller
        if (!newDrive || decision?.isActive == true) return
        // Not looked up yet right after a cold start: what the last lookup found then.
        val resume = c.tonearmServer.server.value?.resume ?: c.sessions.active.value?.let(c.knownServers::resume)
        // Without the setting, a connection or a server that keeps where devices left off, nothing waits.
        if (!c.settings.state.value.continueFromDesktop || !c.network.online.value || resume == false || player.playWhenReady) return
        gate.hold = true
        val picked = picks
        decision = scope.launch {
            try {
                takeOver(picked)
            } finally {
                // The play asked for meanwhile, unless the service is going away.
                if (gate.endHold() && isActive) {
                    player.prepare()
                    player.play()
                }
            }
        }
    }

    fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
        if (!isCar(session, controller)) return
        cars -= controller
        if (cars.isEmpty()) carLeftAt = System.currentTimeMillis()
    }

    /** Returns once the car start's lookup is done (at once when there's none). */
    suspend fun awaitDecision() {
        decision?.join()
    }

    /**
     * Something to play was picked in the car or on the phone (a queue, songs added, a radio, a voice search): it wins
     * over the desktop's queue. The app's own changes to the queue (the saved one put back, a radio's next songs) don't count.
     */
    fun picked() {
        picks++
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (!isPlaying) return
        val device = pauseOnPlay ?: return
        pauseOnPlay = null
        scope.launch {
            runCatching { c.connect.send(device, ConnectCommand(ConnectCommand.PAUSE)) }.onFailure { Log.w(TAG, "Couldn't pause $device", it) }
        }
    }

    /** Takes the desktop's queue over, unless something was picked since the car connected (when there had been [picked]). */
    private suspend fun takeOver(picked: Int) {
        // Right after a cold start the settings' state may not be loaded yet.
        if (!c.settings.current().continueFromDesktop) return
        val session = c.sessions.active.value ?: runCatching { c.sessions.awaitActive() }.getOrNull() ?: return
        // The session wasn't there yet when the car connected: the last lookup, also before this start, may have found no
        // Tonearm server that keeps where devices left off, and then there's nothing to wait for.
        if (c.knownServers.resume(session) == false) return
        restored()
        val handoff = withTimeoutOrNull(LOOKUP_MS) {
            try {
                c.connect.resume()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't ask where the desktop left off", e)
                null
            }
        } ?: return
        val plan = planOf(handoff, session.config.baseUrl) ?: return
        // Something was picked in the car or on the phone meanwhile: that wins.
        if (picks != picked || player.playWhenReady) return
        Log.i(TAG, "Continuing from ${handoff.name}: ${plan.queue.size} songs")
        beforeTakeOver()
        player.setMediaItems(plan.queue.map { c.mediaItems.song(it.toQueueSong(session.id)) }, plan.index, plan.positionMs)
        player.shuffleModeEnabled = false
        player.repeatMode = when (plan.repeat) {
            "all" -> Player.REPEAT_MODE_ALL
            "one" -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        pauseOnPlay = plan.pause
    }

    private fun planOf(handoff: Handoff, server: String): Handoffs.Plan? {
        val state = runCatching { c.json.decodeFromString(DeviceState.serializer(), handoff.state) }.getOrNull() ?: return null
        return Handoffs.plan(handoff, state, server, phonePlayedAt(), System.currentTimeMillis())
    }

    private fun isCar(session: MediaSession, controller: MediaSession.ControllerInfo) =
        session.isAutoCompanionController(controller) || session.isAutomotiveController(controller)

    private companion object {
        const val TAG = "CarHandoff"
        /** A car connecting this long after the last one left is a new drive. */
        const val NEW_DRIVE_MS = 10 * 60_000L
        /** How long the car start waits for the Tonearm server at most. */
        const val LOOKUP_MS = 3_000L
    }
}
