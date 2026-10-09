package io.github.deadeyebarb.tonearm.playback

import io.github.deadeyebarb.tonearm.data.QueueEnd
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.deadeyebarb.tonearm.MainActivity
import io.github.deadeyebarb.tonearm.R
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.likes.Dislikes
import io.github.deadeyebarb.tonearm.media.MediaIds
import io.github.deadeyebarb.tonearm.media.QueueSong
import io.github.deadeyebarb.tonearm.media.toQueueSong
import io.github.deadeyebarb.tonearm.mixes.RadioSeed
import io.github.deadeyebarb.tonearm.subsonic.NoServerException
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.subsonic.StarKind
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApiException
import io.github.deadeyebarb.tonearm.subsonic.userMessage
import io.github.deadeyebarb.tonearm.widget.NowPlayingCard
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the ExoPlayer and the media session: background playback, the media notification,
 * lock screen, Bluetooth/headset buttons, Android Auto browsing and playback resumption.
 */
class PlaybackService : MediaLibraryService() {
    private val c by lazy { container }
    // A failure in one of its jobs is logged; it mustn't take the music down with it.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e -> Log.w(TAG, "Playback service job failed", e) },
    )
    private lateinit var player: ExoPlayer
    /** What the session controls: the player, held back from playing while the car start looks for the desktop's queue. */
    private lateinit var gate: HandoffGatePlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var tree: LibraryTree
    private lateinit var scrobbler: Scrobbler
    private lateinit var cacheAhead: CacheAhead
    private lateinit var radio: RadioQueue
    private lateinit var carHandoff: CarHandoff
    /** Puts the saved queue back; playback resumption, the car start and a radio from the shortcut wait for it. */
    private lateinit var restoreJob: Job
    /** When this phone last played (ms); saved with the queue. */
    private var lastPlayedAt = 0L
    /** Songs said "Not this one" to whose dislike couldn't be saved yet: sent again once online, left out meanwhile. */
    private val unsavedDislikes = mutableListOf<QueueSong>()
    private var continueJob: Job? = null
    /** The YouTube Music item auto-request last ran for, so pausing and resuming doesn't repeat it. */
    private var swapJob: Job? = null

    /** The queue entry the queue was last extended after ("When the queue ends"); a new queue has new entries. */
    private var continuedAfter: Any? = null
    private val searchResults = mutableMapOf<String, List<MediaItem>>()
    private var saveJob: Job? = null
    private var recoverJob: Job? = null
    /** Tries at getting going again after network errors, since playback last ran. */
    private var recoveries = 0

    private val toggleStarCommand = SessionCommand(CMD_TOGGLE_STAR, Bundle.EMPTY)
    private val shuffleCommand = SessionCommand(CMD_TOGGLE_SHUFFLE, Bundle.EMPTY)
    private val repeatCommand = SessionCommand(CMD_CYCLE_REPEAT, Bundle.EMPTY)
    private val notThisOneCommand = SessionCommand(PlaybackCommands.NOT_THIS_ONE, Bundle.EMPTY)
    private val toggleRadioCommand = SessionCommand(PlaybackCommands.TOGGLE_RADIO, Bundle.EMPTY)
    private val startRadioCommand = SessionCommand(PlaybackCommands.START_RADIO, Bundle.EMPTY)
    private val stopRadioCommand = SessionCommand(PlaybackCommands.STOP_RADIO, Bundle.EMPTY)

    override fun onCreate() {
        super.onCreate()
        c.downloads // Loads the download index so downloaded songs play offline.
        val settings = runBlocking { c.settings.current() }

        val renderers = object : DefaultRenderersFactory(this) {
            // Wraps the audio sink so the visualizer sees decoded PCM, including hi-res float output.
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink? =
                super.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams)?.let { TappedAudioSink(it, c.spectrum) }
        }
            // 32-bit float output keeps 24-bit FLAC at full resolution instead of dithering to 16-bit.
            .setEnableAudioFloatOutput(settings.hiResOutput)
        player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    c.media.playbackDataSourceFactory,
                    DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true),
                ),
            )
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        // Shuffle plays the whole queue from the song picked, and songs added keep their place.
        player.setShuffleOrder(QueueShuffleOrder())

        tree = LibraryTree(c, onHomeChanged = { scope.launch { session.notifyChildrenChanged(MediaIds.HOME, Int.MAX_VALUE, null) } })
        gate = HandoffGatePlayer(player)
        session = MediaLibrarySession.Builder(this, gate, Callback())
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setLibraryErrorReplicationMode(MediaLibrarySession.LIBRARY_ERROR_REPLICATION_MODE_FATAL)
            .build()

        c.volume.attach(player)
        c.sleepTimer.attach(player)
        c.effects.attach(player.audioSessionId)
        scrobbler = c.scrobbler.also(player::addListener)
        cacheAhead = CacheAhead(player, c.media, c.settings, scope).also(player::addListener)
        radio = RadioQueue(player, c, scope, leaveOut = ::isUnsavedDislike, onChange = ::onRadioChanged).also(player::addListener)
        carHandoff = CarHandoff(
            c, player, gate, scope, restored = { restoreJob.join() }, phonePlayedAt = { lastPlayedAt }, beforeTakeOver = radio::stop,
        ).also(player::addListener)
        player.addListener(listener)

        observeSettings()
        scope.launch { c.starred.overrides.collect { updateButtons() } }
        scope.launch { c.likes.pending.items.collect { updateButtons() } }
        scope.launch { c.likes.phone.collect { updateButtons() } }
        // "Not this one" shows once the Tonearm server that keeps dislikes is found.
        scope.launch { c.tonearmServer.server.map { it?.dislikes == true }.distinctUntilChanged().collect { updateButtons() } }
        // Back online: what couldn't be scrobbled or disliked goes now.
        scope.launch {
            c.network.online.filter { it }.collect {
                scrobbler.retry()
                retryDislikes()
            }
        }
        // When a server is added (or switched), drop any "set up Tonearm" error the car is showing
        // and have connected browsers reload, so Android Auto picks up the library right away.
        scope.launch {
            c.servers.state.map { it?.active?.id }.distinctUntilChanged().collect { activeId ->
                if (activeId != null) {
                    session.clearReplicatedLibraryError()
                    session.notifyChildrenChanged(MediaIds.ROOT, Int.MAX_VALUE, null)
                }
            }
        }
        // Daily Discovery and Your mix are on Home once the Tonearm server is found, which can take a while.
        scope.launch {
            c.tonearmServer.server.map { it?.history == true }.distinctUntilChanged().collect {
                session.notifyChildrenChanged(MediaIds.HOME, Int.MAX_VALUE, null)
            }
        }
        restoreQueue()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!isPlaybackOngoing) pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        // It sends the last song after the service is gone.
        scrobbler.finish()
        player.removeListener(scrobbler)
        cacheAhead.release()
        val snapshot = c.queueStore.snapshot(player, radio.current, playedAt())
        runBlocking { runCatching { c.queueStore.save(snapshot) } }
        // The widget and the tile show it paused; their controls start the service again.
        c.nowPlaying.update { it?.copy(playing = false, live = false) }
        c.effects.release()
        c.sleepTimer.detach()
        c.volume.detach()
        player.release()
        session.release()
        scope.cancel()
        super.onDestroy()
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            applyReplayGain()
            updateButtons()
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            c.effects.attach(audioSessionId)
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Playback error", error)
            recover(error)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Playing, or played until now.
            lastPlayedAt = System.currentTimeMillis()
            if (isPlaying) {
                recoveries = 0
                recoverJob?.cancel()
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED)) updateButtons()
            if (events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED) && player.shuffleModeEnabled && player.mediaItemCount > 0) {
                // Shuffle turned on: everything else plays after the song playing (see QueueShuffleOrder).
                this@PlaybackService.player.setShuffleOrder(QueueShuffleOrder().cloneAndSet(player.mediaItemCount, player.currentMediaItemIndex))
            }
            if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED)) {
                continueIfLast()
                updateButtons()
                useLibraryFiles()
                // The songs playing and up next keep their quality; others choose again when they play.
                val next = player.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }?.let(player::getMediaItemAt)
                c.media.keepPinned(listOfNotNull(player.currentMediaItem, next).mapNotNull { it.localConfiguration?.uri })
            }
            if (events.containsAny(
                    Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED,
                )
            ) {
                scheduleSave()
            }
            if (events.containsAny(
                    Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    Player.EVENT_IS_PLAYING_CHANGED,
                )
            ) {
                publishNowPlaying()
            }
        }
    }

    /** For the widget and the quick settings tile. */
    private fun publishNowPlaying() {
        c.nowPlaying.value = NowPlayingCard.from(player.currentMediaItem?.toQueueSong(), player.playWhenReady, player.currentMediaItemIndex, player.mediaItemCount)
    }

    /** When this phone last played: now while it plays. */
    private fun playedAt(): Long = if (player.isPlaying) System.currentTimeMillis().also { lastPlayedAt = it } else lastPlayedAt

    private fun onRadioChanged() {
        updateButtons()
        // The phone's Now Playing and the widget read it from here.
        session.setSessionExtras(Bundle().apply { radio.current?.let { putString(PlaybackCommands.EXTRA_RADIO, it.title) } })
        scheduleSave()
    }

    private fun observeSettings() {
        scope.launch {
            c.settings.state.map { it.replayGain to it.replayGainPreampDb }.distinctUntilChanged().collect { applyReplayGain() }
        }
        scope.launch {
            c.settings.state.map { it.audioOffload }.distinctUntilChanged().collect { offload ->
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setAudioOffloadPreferences(
                        AudioOffloadPreferences.Builder()
                            .setAudioOffloadMode(
                                if (offload) AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                                else AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED,
                            )
                            .setIsGaplessSupportRequired(true)
                            .build(),
                    )
                    .build()
            }
        }
        scope.launch {
            c.settings.state.map { it.equalizer }.distinctUntilChanged().collect { c.effects.apply(it) }
        }
    }

    private fun applyReplayGain() {
        val settings = c.settings.state.value
        val info = player.currentMediaItem?.toQueueSong()?.song?.replayGain
        c.volume.replayGain = ReplayGain.linearGain(settings.replayGain, settings.replayGainPreampDb, info)
    }

    private fun updateButtons() {
        val entry = player.currentMediaItem?.toQueueSong()
        val liked = entry != null && c.likes.isLiked(entry)
        // Dislikes are kept by the Tonearm server: without it there's no "Not this one".
        val notThisOne = CommandButton.Builder(CommandButton.ICON_THUMB_DOWN_UNFILLED)
            .setDisplayName("Not this one")
            .setSessionCommand(notThisOneCommand)
            .setEnabled(entry != null)
            .build()
            .takeIf { c.tonearmServer.server.value?.dislikes == true }
        val star = CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName(if (liked) "Unlike" else "Like")
            .setSessionCommand(toggleStarCommand)
            .setEnabled(entry != null)
            .build()
        val shuffle = CommandButton.Builder(if (player.shuffleModeEnabled) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
            .setDisplayName(if (player.shuffleModeEnabled) "Shuffle off" else "Shuffle on")
            .setSessionCommand(shuffleCommand)
            .build()
        val repeat = CommandButton.Builder(
            when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
                Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
                else -> CommandButton.ICON_REPEAT_OFF
            },
        )
            .setDisplayName("Repeat")
            .setSessionCommand(repeatCommand)
            .build()
        val radioOn = radio.current != null
        // Its own drawables, not ICON_RADIO: Automotive draws that one the same whether the radio is on or off.
        val radioButton = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setCustomIconResId(if (radioOn) R.drawable.ic_car_radio_on else R.drawable.ic_car_radio)
            .setDisplayName(if (radioOn) "Stop radio" else "Start radio")
            .setSessionCommand(toggleRadioCommand)
            .build()
        // Phone notification, lock screen, Android Auto and Automotive all pick these up, the first ones first.
        session.setMediaButtonPreferences(listOfNotNull(notThisOne, star, radioButton, shuffle, repeat))
    }

    /**
     * The next songs in the queue play from the library's own file when it has one: a YouTube Music song
     * once Lidarr has downloaded it (usually as FLAC), a library song whose file was upgraded since.
     */
    private fun useLibraryFiles() {
        val session = c.sessions.active.value ?: return
        val current = player.currentMediaItemIndex
        if (current == C.INDEX_UNSET) return
        val upcoming = CacheAhead.upcoming(player.currentTimeline, current, player.repeatMode, player.shuffleModeEnabled, UPGRADE_AHEAD)
            .map { player.getMediaItemAt(it) }
        swapJob?.cancel()
        swapJob = scope.launch {
            for (item in upcoming) {
                val entry = item.toQueueSong() ?: continue
                val better = c.versions.better(entry, session) ?: continue
                // The queue may have moved on while the server answered.
                val index = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == item.mediaId } ?: continue
                if (index == player.currentMediaItemIndex) continue
                val context = MediaIds.parse(item.mediaId).context
                player.replaceMediaItem(index, c.mediaItems.song(better, context))
            }
        }
    }

    /** On the last song, appends what "When the queue ends" asks for, so playback carries on gaplessly. */
    private fun continueIfLast() {
        // A radio adds its own.
        if (radio.current != null) return
        if (player.mediaItemCount == 0 || player.hasNextMediaItem() || player.repeatMode != Player.REPEAT_MODE_OFF) return
        if (c.settings.state.value.whenQueueEnds == QueueEnd.STOP) return
        val item = player.currentMediaItem ?: return
        // The entry, not the song: the same song played again in a new queue gets its continuation too.
        val entry = player.currentTimeline.getWindow(player.currentMediaItemIndex, Timeline.Window()).uid
        if (continuedAfter == entry) return
        continuedAfter = entry
        val last = item.toQueueSong() ?: return
        val played = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).toQueueSong() }.map(QueueContinuation::key).toSet()
        continueJob = scope.launch {
            val more = runCatching { c.continuation.next(last, played) }.getOrDefault(emptyList()).filterNot { isUnsavedDislike(it.song) }
            if (more.isEmpty() || player.hasNextMediaItem() || radio.current != null) return@launch
            // Right after the song playing, which is the last to play, shuffled or not.
            player.addMediaItems(player.currentMediaItemIndex + 1, more.map { c.mediaItems.song(it) })
        }
    }

    /**
     * After a network error (a tunnel, a dead zone) while the music should play: while offline it skips
     * to the next song that plays without a connection, otherwise it waits for one and carries on.
     */
    private fun recover(error: PlaybackException) {
        val online = c.network.online.value
        // Offline, any reading error is down to that (a YouTube Music song can't be looked up, say).
        val lostConnection = error.errorCode in CONNECTION_ERRORS || (!online && error.errorCode in IO_ERRORS)
        if (!lostConnection || !player.playWhenReady || recoveries >= MAX_RECOVERIES) return
        recoveries++
        recoverJob?.cancel()
        recoverJob = scope.launch {
            if (!online) {
                val upcoming = CacheAhead.upcoming(player.currentTimeline, player.currentMediaItemIndex, player.repeatMode, player.shuffleModeEnabled, player.mediaItemCount)
                val uris = upcoming.map { player.getMediaItemAt(it).localConfiguration?.uri }
                val offline = withContext(Dispatchers.IO) { uris.indexOfFirst { it != null && c.media.playsOffline(it) } }
                if (offline >= 0 && player.playerError != null) {
                    player.seekToDefaultPosition(upcoming[offline])
                    player.prepare()
                    c.messages.show("Offline: skipped to a song that plays without a connection")
                    return@launch
                }
                c.network.online.first { it }
            }
            delay(RECOVER_DELAY_MS * recoveries)
            if (player.playerError != null) player.prepare()
        }
    }

    private fun toggleStar() {
        val entry = player.currentMediaItem?.toQueueSong() ?: return
        val liked = c.likes.isLiked(entry)
        scope.launch {
            runCatching {
                c.likes.set(entry, !liked)
                if (!liked) c.undislike(entry.song)
            }.onFailure {
                val message = it.userMessage()
                c.messages.show(message)
                // The car shows it for a moment too.
                session.sendError(SessionError(SessionError.ERROR_UNKNOWN, message))
            }
        }
    }

    /**
     * Not this one: skips the song, takes it out of the queue and dislikes it (it isn't liked anymore either). On the last
     * song the radio or "When the queue ends" adds the next ones first, so there's something to move on to.
     */
    private fun notThisOne() {
        val item = player.currentMediaItem ?: return
        val entry = item.toQueueSong() ?: return
        val index = player.currentMediaItemIndex
        scope.launch {
            if (!player.hasNextMediaItem()) {
                withTimeoutOrNull(MORE_FOR_SKIP_MS) { if (radio.current != null) radio.awaitMore() else continueJob?.join() }
            }
            if (player.currentMediaItemIndex == index && player.currentMediaItem?.mediaId == item.mediaId && player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
            }
            // Seeking leaves the queue as it was, so the song is still at its index (unless the queue changed meanwhile).
            if (index < player.mediaItemCount && player.getMediaItemAt(index).mediaId == item.mediaId) player.removeMediaItem(index)
            dislike(entry, announce = true)
        }
    }

    private suspend fun dislike(entry: QueueSong, announce: Boolean) {
        val song = entry.song
        try {
            c.dislikes.set(song.artist.orEmpty(), song.title, song.album, true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't save the dislike", e)
            // Without a server that keeps dislikes, trying again won't help.
            if (c.tonearmServer.server.value?.dislikes != true) return c.messages.show(e.userMessage())
            if (unsavedDislikes.none { it == entry }) unsavedDislikes += entry
            return
        }
        if (announce) c.messages.show("Disliked “${song.title}”: it goes to the end of searches and out of mixes and picks")
        // The dislike is saved; not liking it anymore may fail on its own.
        if (c.likes.isLiked(entry)) runCatching { c.likes.set(entry, false) }
    }

    private fun retryDislikes() {
        if (unsavedDislikes.isEmpty()) return
        val waiting = unsavedDislikes.toList()
        unsavedDislikes.clear()
        scope.launch { for (entry in waiting) dislike(entry, announce = false) }
    }

    private fun isUnsavedDislike(song: Song): Boolean {
        if (unsavedDislikes.isEmpty()) return false
        val key = Dislikes.songKey(song.artistLabel, song.title)
        return unsavedDislikes.any { Dislikes.songKey(it.song.artistLabel, it.song.title) == key }
    }

    /**
     * Starts a radio from the current song ([PlaybackCommands.SEED_CURRENT]; the picks without one) or from the picks,
     * once the saved queue is back. A problem is shown on the phone and, for a moment, in the car.
     */
    private suspend fun startRadio(seed: String?, play: Boolean): SessionResult {
        restoreJob.join()
        val from = if (seed == PlaybackCommands.SEED_PICKS) RadioSeed(RadioSeed.PICKS) else RadioQueue.seedOf(player.currentMediaItem?.toQueueSong())
        val problem = radio.start(from, play).await() ?: return SessionResult(SessionResult.RESULT_SUCCESS)
        val error = SessionError(SessionError.ERROR_UNKNOWN, problem)
        c.messages.show(problem)
        session.sendError(error)
        return SessionResult(error)
    }

    private fun restoreQueue() {
        restoreJob = scope.launch {
            val saved = c.queueStore.load() ?: return@launch
            lastPlayedAt = maxOf(lastPlayedAt, saved.playedAt)
            if (player.mediaItemCount > 0) return@launch
            player.setMediaItems(c.queueStore.mediaItems(saved), saved.index, saved.positionMs)
            player.shuffleModeEnabled = saved.shuffle
            player.repeatMode = saved.repeatMode
            radio.restore(saved.radio)
            // Not prepared on purpose: nothing is fetched until the user presses play.
        }
    }

    /** Saves soon after changes, and every 15 s while playing so the position stays fresh. */
    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1_000)
            while (isActive) {
                try {
                    c.queueStore.save(c.queueStore.snapshot(player, radio.current, playedAt()))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Storage full, say: it's saved next time.
                    Log.w(TAG, "Couldn't save the queue", e)
                }
                if (!player.isPlaying) break
                delay(15_000)
            }
        }
    }

    /**
     * Shown full-screen by Android Auto / Automotive with a button that opens the app. Media3
     * only forwards "authentication expired" library errors to the car, which fits: the user has
     * to set up or fix their login on the phone.
     */
    private fun signInError(message: String): SessionError {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val extras = Bundle().apply {
            putString(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT, "Open Tonearm")
            putParcelable(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT, open)
        }
        return SessionError(SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED, message, extras)
    }

    private fun <T> List<T>.page(page: Int, size: Int): List<T> =
        if (size <= 0 || size == Int.MAX_VALUE) this else drop(page * size).take(size)

    private inner class Callback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val defaults = if (controller.isTrusted) {
                MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
            } else {
                MediaSession.ConnectionResult.DEFAULT_UNTRUSTED_SESSION_AND_LIBRARY_COMMANDS
            }
            val commands = defaults.buildUpon().add(toggleStarCommand).add(shuffleCommand).add(repeatCommand)
                .add(notThisOneCommand).add(toggleRadioCommand).add(startRadioCommand).add(stopRadioCommand)
                .build()
            carHandoff.onConnect(session, controller)
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            carHandoff.onDisconnected(session, controller)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_TOGGLE_STAR -> toggleStar()
                CMD_TOGGLE_SHUFFLE -> player.shuffleModeEnabled = !player.shuffleModeEnabled
                CMD_CYCLE_REPEAT -> player.repeatMode = when (player.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                PlaybackCommands.NOT_THIS_ONE -> notThisOne()
                PlaybackCommands.TOGGLE_RADIO -> if (radio.current != null) radio.stop() else scope.launch { startRadio(PlaybackCommands.SEED_CURRENT, play = false) }
                PlaybackCommands.STOP_RADIO -> radio.stop()
                // Answered once the radio plays, so a controller made just for this can let go after it.
                PlaybackCommands.START_RADIO -> return scope.future { startRadio(args.getString(PlaybackCommands.ARG_SEED), play = true) }
                else -> return super.onCustomCommand(session, controller, customCommand, args)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
            LibraryResult.ofItem(tree.root(), LibraryParams.Builder().setExtras(tree.rootExtras()).build()),
        )

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            try {
                LibraryResult.ofItemList(tree.children(parentId).page(page, pageSize), params)
            } catch (e: NoServerException) {
                LibraryResult.ofError(signInError("Add your music server in Tonearm on your phone."))
            } catch (e: SubsonicApiException) {
                if (e.code in AUTH_ERRORS) {
                    LibraryResult.ofError(signInError("Tonearm can't sign in to your server. Check it in the app on your phone."))
                } else {
                    LibraryResult.ofError(SessionError(SessionError.ERROR_IO, e.userMessage()))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Browsing $parentId failed", e)
                LibraryResult.ofError(SessionError(SessionError.ERROR_IO, e.userMessage()))
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            runCatching { tree.item(mediaId) }.getOrNull()?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future {
            val results = runCatching { tree.search(query) }.getOrDefault(emptyList())
            searchResults[query] = results
            session.notifySearchResultChanged(browser, query, results.size, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val results = searchResults[query] ?: runCatching { tree.search(query) }.getOrDefault(emptyList())
            LibraryResult.ofItemList(results.page(page, pageSize), params)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future { tree.resolve(mediaItems).toMutableList() }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
            try {
                // A song picked in the car plays its album or playlist; the app sends whole queues itself.
                tree.resolveForPlayback(mediaItems, startIndex, startPositionMs, expandSongs = controller.packageName != packageName)
                    // A new queue: the radio playing doesn't go on with it.
                    .also { radio.stop() }
            } catch (e: NoSuchElementException) {
                // A voice search found nothing: say so (the car too), and what was playing carries on.
                val message = e.message ?: "Nothing found"
                c.messages.show(message)
                session.sendError(SessionError(SessionError.ERROR_BAD_VALUE, message))
                throw e
            }
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
            // The saved queue, or the desktop's when the car start takes that over.
            restoreJob.join()
            carHandoff.awaitDecision()
            if (player.mediaItemCount > 0) {
                MediaItemsWithStartPosition((0 until player.mediaItemCount).map(player::getMediaItemAt), player.currentMediaItemIndex, player.currentPosition)
            } else {
                val saved = c.queueStore.load() ?: throw UnsupportedOperationException("Nothing to resume")
                MediaItemsWithStartPosition(c.queueStore.mediaItems(saved), saved.index, saved.positionMs)
            }
        }
    }

    companion object {
        private const val TAG = "PlaybackService"
        /** How many of the next songs are checked for a library file to play instead. */
        private const val UPGRADE_AHEAD = 3
        private val CONNECTION_ERRORS = setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        )
        /** PlaybackException's codes for errors reading the media. */
        private val IO_ERRORS = 2000..2999
        private const val MAX_RECOVERIES = 10
        private const val RECOVER_DELAY_MS = 2_000L
        /** How long "Not this one" on the last song waits for more songs to move on to. */
        private const val MORE_FOR_SKIP_MS = 5_000L
        const val CMD_TOGGLE_STAR = "io.github.deadeyebarb.tonearm.TOGGLE_STAR"
        /** Subsonic codes meaning the login is wrong or not accepted. */
        private val AUTH_ERRORS = setOf(40, 41, 42, 43, 44)
        const val CMD_TOGGLE_SHUFFLE = "io.github.deadeyebarb.tonearm.TOGGLE_SHUFFLE"
        const val CMD_CYCLE_REPEAT = "io.github.deadeyebarb.tonearm.CYCLE_REPEAT"
    }
}
