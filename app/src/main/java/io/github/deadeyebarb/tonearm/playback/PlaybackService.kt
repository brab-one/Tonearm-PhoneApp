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
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.media.MediaIds
import io.github.deadeyebarb.tonearm.media.toQueueSong
import io.github.deadeyebarb.tonearm.subsonic.NoServerException
import io.github.deadeyebarb.tonearm.subsonic.StarKind
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApiException
import io.github.deadeyebarb.tonearm.subsonic.userMessage
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Owns the ExoPlayer and the media session: background playback, the media notification,
 * lock screen, Bluetooth/headset buttons, Android Auto browsing and playback resumption.
 */
class PlaybackService : MediaLibraryService() {
    private val c by lazy { container }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var tree: LibraryTree
    private lateinit var scrobbler: Scrobbler
    private lateinit var cacheAhead: CacheAhead
    /** The YouTube Music item auto-request last ran for, so pausing and resuming doesn't repeat it. */
    private var swapJob: Job? = null

    /** The last song the queue was extended after ("When the queue ends"). */
    private var continuedAfter: String? = null
    private val searchResults = mutableMapOf<String, List<MediaItem>>()
    private var saveJob: Job? = null

    private val toggleStarCommand = SessionCommand(CMD_TOGGLE_STAR, Bundle.EMPTY)
    private val shuffleCommand = SessionCommand(CMD_TOGGLE_SHUFFLE, Bundle.EMPTY)
    private val repeatCommand = SessionCommand(CMD_CYCLE_REPEAT, Bundle.EMPTY)

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

        tree = LibraryTree(c)
        session = MediaLibrarySession.Builder(this, player, Callback())
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
        scrobbler = Scrobbler(c.api, c.sessions, c.settings, c.integrations, scope).also(player::addListener)
        cacheAhead = CacheAhead(player, c.media, c.settings, scope).also(player::addListener)
        player.addListener(listener)

        observeSettings()
        scope.launch { c.starred.overrides.collect { updateButtons() } }
        scope.launch { c.likes.pending.items.collect { updateButtons() } }
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
        restoreQueue()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!isPlaybackOngoing) pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        scrobbler.finish()
        cacheAhead.release()
        val snapshot = c.queueStore.snapshot(player)
        runBlocking { runCatching { c.queueStore.save(snapshot) } }
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
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED)) updateButtons()
            if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED)) {
                continueIfLast()
                updateButtons()
                useLibraryFiles()
            }
            if (events.containsAny(
                    Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED,
                )
            ) {
                scheduleSave()
            }
        }
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
        // Phone notification, lock screen, Android Auto and Automotive all pick these up.
        session.setMediaButtonPreferences(listOf(star, shuffle, repeat))
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
        if (player.mediaItemCount == 0 || player.hasNextMediaItem() || player.repeatMode != Player.REPEAT_MODE_OFF) return
        if (c.settings.state.value.whenQueueEnds == QueueEnd.STOP) return
        val item = player.currentMediaItem ?: return
        if (continuedAfter == item.mediaId) return
        continuedAfter = item.mediaId
        val last = item.toQueueSong() ?: return
        val played = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).toQueueSong() }.map(QueueContinuation::key).toSet()
        scope.launch {
            val more = runCatching { c.continuation.next(last, played) }.getOrDefault(emptyList())
            if (more.isEmpty() || player.hasNextMediaItem()) return@launch
            player.addMediaItems(more.map { c.mediaItems.song(it) })
        }
    }

    private fun toggleStar() {
        val entry = player.currentMediaItem?.toQueueSong() ?: return
        val liked = c.likes.isLiked(entry)
        scope.launch {
            runCatching { c.likes.set(entry, !liked) }.onFailure { c.messages.show(it.userMessage()) }
        }
    }

    private fun restoreQueue() {
        scope.launch {
            val saved = c.queueStore.load() ?: return@launch
            if (player.mediaItemCount > 0) return@launch
            player.setMediaItems(c.queueStore.mediaItems(saved), saved.index, saved.positionMs)
            player.shuffleModeEnabled = saved.shuffle
            player.repeatMode = saved.repeatMode
            // Not prepared on purpose: nothing is fetched until the user presses play.
        }
    }

    /** Saves soon after changes, and every 15 s while playing so the position stays fresh. */
    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1_000)
            while (isActive) {
                c.queueStore.save(c.queueStore.snapshot(player))
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
            val commands = defaults.buildUpon().add(toggleStarCommand).add(shuffleCommand).add(repeatCommand).build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(commands)
                .build()
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
                else -> return super.onCustomCommand(session, controller, customCommand, args)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            return super.onCustomCommand(session, controller, customCommand, args)
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
            tree.resolveForPlayback(mediaItems, startIndex, startPositionMs)
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
            val saved = c.queueStore.load() ?: throw UnsupportedOperationException("Nothing to resume")
            MediaItemsWithStartPosition(c.queueStore.mediaItems(saved), saved.index, saved.positionMs)
        }
    }

    companion object {
        private const val TAG = "PlaybackService"
        /** How many of the next songs are checked for a library file to play instead. */
        private const val UPGRADE_AHEAD = 3
        const val CMD_TOGGLE_STAR = "io.github.deadeyebarb.tonearm.TOGGLE_STAR"
        /** Subsonic codes meaning the login is wrong or not accepted. */
        private val AUTH_ERRORS = setOf(40, 41, 42, 43, 44)
        const val CMD_TOGGLE_SHUFFLE = "io.github.deadeyebarb.tonearm.TOGGLE_SHUFFLE"
        const val CMD_CYCLE_REPEAT = "io.github.deadeyebarb.tonearm.CYCLE_REPEAT"
    }
}
