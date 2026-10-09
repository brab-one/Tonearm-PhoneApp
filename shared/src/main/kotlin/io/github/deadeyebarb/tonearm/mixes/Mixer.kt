package io.github.deadeyebarb.tonearm.mixes

import io.github.deadeyebarb.tonearm.connect.ConnectClient
import io.github.deadeyebarb.tonearm.connect.ConnectRouter
import io.github.deadeyebarb.tonearm.connect.DailyMixes
import io.github.deadeyebarb.tonearm.connect.Mix
import io.github.deadeyebarb.tonearm.integrations.Continuation
import io.github.deadeyebarb.tonearm.integrations.IntegrationHttpException
import io.github.deadeyebarb.tonearm.integrations.NextSong
import io.github.deadeyebarb.tonearm.likes.Dislikes
import io.github.deadeyebarb.tonearm.subsonic.ServerSession
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import io.github.deadeyebarb.tonearm.weekly.WeeklyPicks
import io.github.deadeyebarb.tonearm.weekly.WeeklyState
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.io.IOException
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.random.Random

/** Where a radio starts: a song (library, YouTube Music when [youtube], or a file on this device when [local]), or the user's picks. */
@Serializable
data class RadioSeed(val kind: String, val song: Song? = null, val youtube: Boolean = false, val local: Boolean = false) {
    val title: String get() = if (kind == PICKS) "Picks radio" else "Radio · " + song?.title.orEmpty()

    companion object {
        const val SONG = "song"
        const val PICKS = "picks"
    }
}

/** A radio batch, from the Tonearm server or made here; [problem] says why there's nothing when [songs] is empty. */
data class RadioResult(val title: String, val songs: List<NextSong>, val more: Boolean, val problem: String? = null)

/**
 * Radio, mixes from a sentence and daily mixes: from the Tonearm server when it makes them, else made here from the
 * library (daily mixes only come from the server). The phone and the desktop both play what this gives.
 */
class Mixer(
    private val api: SubsonicApi,
    private val client: ConnectClient,
    private val router: ConnectRouter,
    youtube: YouTubeMusic,
    private val dislikes: Dislikes,
    private val weekly: WeeklyPicks,
) {
    private val continuation = Continuation(api, youtube, dislikes::isDisliked)
    /** The song radio last made here (its seed's key) and how many batches it has had, to take turns between anchors. */
    @Volatile private var madeHere: Pair<String, Int>? = null

    /** One radio batch. [queue] = the whole queue in order (oldest first). */
    suspend fun radio(session: ServerSession?, seed: RadioSeed, queue: List<NextSong>, allowYouTube: Boolean): RadioResult {
        val keys = queue.map(Continuation::key)
        if (session != null && router.refresh(session)?.radio == true) fromServer(session, seed, keys)?.let { return it }
        return if (seed.kind == RadioSeed.PICKS) picksHere(session, keys, allowYouTube) else similarHere(session, seed, queue, keys, allowYouTube)
    }

    /** The server's batch, without disliked or queued songs; null when it failed, took too long or had nothing. */
    private suspend fun fromServer(session: ServerSession, seed: RadioSeed, keys: List<String>): RadioResult? {
        val picks = seed.kind == RadioSeed.PICKS
        val song = seed.song
        if (!picks && song == null) return null
        val library = !picks && !seed.youtube && !seed.local
        // Only what was queued from the seed on steers a song radio, not what played before it (that still doesn't come again).
        val from = song?.takeIf { !picks }?.let { keys.lastIndexOf(Continuation.key(it.id, seed.youtube)) } ?: -1
        val steer = if (from >= 0) keys.drop(from) else keys
        val batch = try {
            // On the IO dispatcher, so the timeout runs on the clock, not a test's.
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(RADIO_TIMEOUT_MS) {
                    client.radio(
                        session, if (picks) RadioSeed.PICKS else RadioSeed.SONG,
                        id = song?.id?.takeIf { library },
                        artist = song?.artistLabel?.takeIf { !picks && !library },
                        title = song?.title?.takeIf { !picks && !library },
                        exclude = RadioPolicy.exclude(steer),
                        count = RadioPolicy.BATCH,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        // The whole queue: what played before the seed doesn't come again either.
        val queued = keys.toSet()
        val songs = batch.songs.filterNot { dislikes.isDisliked(it.artistLabel, it.title) }.map { NextSong(it, false) }
            .filterNot { Continuation.key(it) in queued }.distinctBy(Continuation::key)
        if (songs.isEmpty()) return null
        return RadioResult(batch.title.ifEmpty { seed.title }, songs, batch.more, batch.problem)
    }

    /**
     * Songs like the seed and, every other batch, like the last song queued, so a long radio moves on but stays near it.
     * When the one whose turn it is has nothing new, the other fills in.
     */
    private suspend fun similarHere(session: ServerSession?, seed: RadioSeed, queue: List<NextSong>, keys: List<String>, allowYouTube: Boolean): RadioResult {
        val first = seed.song ?: return RadioResult(seed.title, emptyList(), false, "There's no song to start the radio from")
        val seedKey = Continuation.key(first.id, seed.youtube)
        val batch = madeHere?.takeIf { it.first == seedKey }?.second?.plus(1) ?: 0
        madeHere = seedKey to batch
        val played = keys.toSet() + seedKey
        val anchors = listOfNotNull(NextSong(first, seed.youtube), queue.lastOrNull()?.takeIf { Continuation.key(it) != seedKey })
            .let { if (batch % 2 == 1) it.reversed() else it }
        var songs = emptyList<NextSong>()
        for (anchor in anchors) {
            songs = continuation.similar(anchor.song, anchor.youtube, session, played, allowYouTube)
            if (songs.isNotEmpty()) break
        }
        val spread = RadioPolicy.spread(songs).take(RadioPolicy.BATCH)
        return RadioResult(seed.title, spread, more = spread.isNotEmpty(), problem = if (spread.isEmpty()) "Nothing more like “${first.title}” turned up" else null)
    }

    /** Picks radio made here: up to four seeds not queued yet, each followed by a few songs like it. */
    private suspend fun picksHere(session: ServerSession?, keys: List<String>, allowYouTube: Boolean): RadioResult {
        val title = RadioSeed(RadioSeed.PICKS).title
        val seeds = session?.let { picksSeeds(it) }.orEmpty()
        if (session == null || seeds.isEmpty()) return RadioResult(title, emptyList(), false, NO_PICKS)
        val queued = keys.toSet()
        val fresh = seeds.filter { Continuation.key(it.id, false) !in queued }.take(PICKS_SEEDS)
        // Every seed has been queued: songs like a few of them keep it going.
        val anchors = fresh.ifEmpty { seeds.shuffled().take(PICKS_SEEDS) }
        val played = queued + anchors.map { Continuation.key(it.id, false) }
        val similar = coroutineScope { anchors.map { async { continuation.similar(it, false, session, played, allowYouTube) } }.awaitAll() }
        val taken = HashSet(queued)
        val batch = mutableListOf<NextSong>()
        anchors.forEachIndexed { i, anchor ->
            if (anchor in fresh && taken.add(Continuation.key(anchor.id, false))) batch += NextSong(anchor, false)
            batch += similar[i].asSequence().filter { taken.add(Continuation.key(it)) }.take(PER_SEED)
        }
        val songs = RadioPolicy.spread(batch).take(RadioPolicy.BATCH)
        return RadioResult(title, songs, more = songs.isNotEmpty(), problem = if (songs.isEmpty()) "Nothing more turned up for your picks radio" else null)
    }

    /** The newest ready weekly picks of the user's own, then songs liked in the last 60 days (newest first, at most 30). */
    private suspend fun picksSeeds(session: ServerSession): List<Song> {
        val week = runCatching { weekly.batches(session) }.getOrDefault(emptyList())
            .filter { it.state.status == WeeklyState.READY }.maxByOrNull { it.state.created }
        val picks = week?.let { runCatching { api.playlist(it.playlist.id, session).entry }.getOrNull() }.orEmpty()
        val since = System.currentTimeMillis() - LIKED_DAYS * 24 * 3600_000L
        val liked = runCatching { api.starred(session).song }.getOrDefault(emptyList())
            .mapNotNull { song -> song.starred?.let(::millis)?.takeIf { it >= since }?.let { song to it } }
            .sortedByDescending { it.second }.map { it.first }.take(LIKED_SEEDS)
        return (picks + liked).distinctBy { it.id }.filterNot { dislikes.isDisliked(it.artistLabel, it.title) }
    }

    /**
     * A mix from a sentence; polls every 3 s while the server's AI works (≤ 5 min), calling [onWaiting]. Without the AI, or
     * when it couldn't answer, the mix is matched in the library ([Mix.ai] null, why the AI wasn't used in [Mix.problem]).
     * [refresh] ("Ask again") has the AI make it again, and a mix matched in the library drawn and shuffled anew.
     * Throws when the AI is still at it after 5 minutes (asking again later picks its answer up).
     */
    suspend fun prompt(session: ServerSession, sentence: String, refresh: Boolean = false, onWaiting: (Mix) -> Unit = {}): Mix {
        val text = sentence.trim()
        var note: String? = null
        if (router.refresh(session)?.mix == true) {
            val answer = try {
                var mix = client.mix(session, text, refresh)
                var polls = 0
                while (mix.running && polls++ < PROMPT_POLLS) {
                    onWaiting(mix)
                    delay(POLL_MS)
                    mix = client.mix(session, text)
                }
                mix
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // The server's own reason, else plainly that it didn't answer (not how the request failed).
                note = (e as? IntegrationHttpException)?.reason ?: SERVER_UNREACHABLE
                null
            }
            if (answer != null) {
                if (answer.running) throw IOException("The AI is still making “$text”; try again in a few minutes")
                val songs = answer.songs.filterNot { dislikes.isDisliked(it.artistLabel, it.title) }
                if (songs.isNotEmpty()) return answer.copy(songs = songs)
                note = answer.problem
            }
        }
        val random = if (refresh) Random(System.nanoTime()) else Random(text.hashCode())
        val songs = SentenceMix.build(api, session, text, dislikes::isDisliked, random = random)
        // The server's "nothing fits" would say the opposite of the songs found here: its AI did answer, but none of
        // the AI's songs could be used.
        if (note == NOTHING_FITS && songs.isNotEmpty()) note = AI_SONGS_LEFT_OUT
        return Mix(
            prompt = text, title = text, songs = songs, ai = null,
            problem = note ?: "Nothing in your library matches “$text”".takeIf { songs.isEmpty() },
        )
    }

    /** Today's daily mixes (polls while running, ≤ 2 min); null when the server has none (`dailyMixes` missing). */
    suspend fun daily(session: ServerSession, zone: ZoneId = ZoneId.systemDefault(), onWaiting: (DailyMixes) -> Unit = {}): DailyMixes? {
        if (router.refresh(session)?.dailyMixes != true) return null
        var daily = client.dailyMixes(session, zone.id)
        var polls = 0
        while (daily.running && polls++ < DAILY_POLLS) {
            onWaiting(daily)
            delay(POLL_MS)
            daily = client.dailyMixes(session, zone.id)
        }
        return daily.copy(mixes = daily.mixes.map { mix -> mix.copy(songs = mix.songs.filterNot { dislikes.isDisliked(it.artistLabel, it.title) }) })
    }

    private fun millis(iso: String): Long? = runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()

    companion object {
        const val NO_PICKS = "Picks radio starts from your weekly picks and the songs you like. Like a few songs first."
        /** The note on a mix matched in the library because the Tonearm server didn't answer. */
        const val SERVER_UNREACHABLE = "The Tonearm server couldn't be reached"
        /** The note on a mix matched in the library because none of the songs the AI picked could be used. */
        const val AI_SONGS_LEFT_OUT = "The AI's songs were all played lately or not found"
        /** The Tonearm server's problem when nothing the AI picked is left. */
        private const val NOTHING_FITS = "Nothing in your library fits that"
        private const val RADIO_TIMEOUT_MS = 8_000L
        private const val POLL_MS = 3_000L
        /** 5 minutes of asking every 3 s. */
        private const val PROMPT_POLLS = 100
        /** 2 minutes. */
        private const val DAILY_POLLS = 40
        private const val PICKS_SEEDS = 4
        private const val PER_SEED = 5
        private const val LIKED_DAYS = 60
        private const val LIKED_SEEDS = 30
    }
}
