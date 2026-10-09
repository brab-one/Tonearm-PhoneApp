package io.github.deadeyebarb.tonearm.mixes

import io.github.deadeyebarb.tonearm.integrations.Names
import io.github.deadeyebarb.tonearm.integrations.NextSong
import io.github.deadeyebarb.tonearm.integrations.SongMatch
import io.github.deadeyebarb.tonearm.subsonic.ServerSession
import io.github.deadeyebarb.tonearm.subsonic.Song
import io.github.deadeyebarb.tonearm.subsonic.SubsonicApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.random.Random

/**
 * A mix from a sentence without the AI: the genres, artists and words in it, matched in the library. What the apps
 * play when the Tonearm server has no AI, or the AI couldn't answer.
 */
object SentenceMix {
    private val stopWords = setOf(
        "the", "and", "for", "with", "some", "music", "songs", "song", "mix", "playlist", "like", "play", "tracks", "track",
        "tunes", "that", "this", "from", "more", "make", "give", "want", "something", "please", "into", "about", "really",
        "very", "just", "all", "any", "are", "was", "but", "not", "you", "your", "our", "out", "who", "what", "when",
        "where", "while", "kind", "sort", "lots", "bit", "stuff", "things", "good", "best", "great", "nice", "little", "much",
        "get", "put", "let", "can", "could", "would", "should", "mood", "vibe", "vibes", "feel", "feeling", "listen",
    )

    /** Lowercase words of three letters or more, without the ones that don't say anything about the music. */
    fun terms(sentence: String): List<String> =
        sentence.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && it !in stopWords }.distinct()

    /** The [genres] the sentence names, longer ones first ("drum and bass" before "bass"), each only once. */
    fun genresIn(sentence: String, genres: List<String>): List<String> {
        var text = " " + Names.normalize(sentence) + " "
        val found = mutableListOf<String>()
        val byLength = genres.filter { Names.normalize(it).isNotEmpty() }.distinctBy(Names::normalize)
            .sortedWith(compareByDescending<String> { Names.normalize(it).split(' ').size }.thenByDescending { Names.normalize(it).length })
        for (genre in byLength) {
            val name = " " + Names.normalize(genre) + " "
            if (name in text) {
                found += genre
                text = text.replace(name, " | ")
            }
        }
        return found
    }

    /**
     * Up to [size] library songs for [sentence]: songs drawn at random from the genres it names (three at most), the
     * artists it names, and songs found for its other words; shuffled with [random], without disliked songs. The same
     * sentence is shuffled the same way unless [random] is another one ("Ask again").
     */
    suspend fun build(
        api: SubsonicApi,
        session: ServerSession,
        sentence: String,
        isDisliked: (String?, String) -> Boolean,
        size: Int = 30,
        random: Random = Random(sentence.trim().hashCode()),
    ): List<Song> = coroutineScope {
        val genres = genresIn(sentence, runCatching { api.genres(session) }.getOrDefault(emptyList()).map { it.value }).take(MAX_GENRES)
        val artists = artistsIn(sentence, runCatching { api.artists(session) }.getOrDefault(emptyList()).flatMap { it.artist }.map { it.name })
        // Words a genre or an artist already stands for aren't searched for again.
        val named = (genres + artists).flatMap(::terms).toSet()
        val words = terms(sentence).filter { it.length >= 4 && it !in named }.take(MAX_WORDS)
        val gate = Semaphore(4)
        val found = (
            // At random: a genre's songs in the server's order would always be the same first ones of a big genre.
            genres.map { genre -> async { gate.withPermit { runCatching { api.randomSongs(GENRE_SONGS, session, genre) }.getOrDefault(emptyList()) } } } +
                artists.map { artist -> async { gate.withPermit { songsBy(api, session, artist) } } } +
                words.map { word -> async { gate.withPermit { runCatching { api.search(word, 0, 0, WORD_SONGS, session).song }.getOrDefault(emptyList()) } } }
            ).awaitAll().flatten()
        val songs = found.distinctBy { it.id }.filterNot { isDisliked(it.artistLabel, it.title) }.shuffled(random)
        RadioPolicy.spread(songs.map { NextSong(it, false) }).map { it.song }.take(size)
    }

    /** Library artists whose whole name is in the sentence. */
    private fun artistsIn(sentence: String, names: List<String>): List<String> {
        val text = " " + Names.normalize(sentence) + " "
        return names.filter { Names.normalize(it).length >= 2 && " " + Names.normalize(it) + " " in text }.distinctBy(Names::normalize).take(MAX_ARTISTS)
    }

    /** An artist's top songs; songs found by their name where the server knows no top songs (Navidrome without Last.fm). */
    private suspend fun songsBy(api: SubsonicApi, session: ServerSession, artist: String): List<Song> =
        api.topSongs(artist, ARTIST_SONGS).ifEmpty {
            val key = Names.normalize(artist)
            runCatching { api.search(artist, 0, 0, ARTIST_SONGS * 2, session).song }.getOrDefault(emptyList())
                .filter { Names.normalize(SongMatch.primaryArtist(it.artistLabel)) == key }.take(ARTIST_SONGS)
        }

    private const val MAX_GENRES = 3
    private const val MAX_ARTISTS = 5
    private const val MAX_WORDS = 6
    private const val GENRE_SONGS = 100
    private const val ARTIST_SONGS = 15
    private const val WORD_SONGS = 20
}
