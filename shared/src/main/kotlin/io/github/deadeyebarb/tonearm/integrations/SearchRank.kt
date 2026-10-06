package io.github.deadeyebarb.tonearm.integrations

/**
 * How well a song fits a search, from 0 to 1, so the best matches come first whatever their source (the
 * library, YouTube Music, Deezer). Every searched word should be in the title or artist; a title made of
 * nothing but searched words beats a long one that merely contains them.
 */
object SearchRank {
    fun score(query: String, title: String, artist: String?, album: String? = null): Double {
        val words = words(query)
        if (words.isEmpty()) return 0.0
        val cleanTitle = SongMatch.cleanTitle(title)
        val titleWords = words(cleanTitle)
        val artistWords = words(artist.orEmpty())
        val albumWords = words(album.orEmpty())
        val covered = words.sumOf { w ->
            when {
                w in titleWords || w in artistWords -> 1.0
                titleWords.any { it.startsWith(w) } || artistWords.any { it.startsWith(w) } -> 0.7
                w in albumWords -> 0.5
                else -> 0.0
            }
        } / words.size
        val precise = if (titleWords.isEmpty()) 0.0 else titleWords.count { it in words }.toDouble() / titleWords.size
        val q = Names.normalize(query)
        val exact = q == Names.normalize(cleanTitle) ||
            (artist != null && (q == Names.normalize("$artist $cleanTitle") || q == Names.normalize("$cleanTitle $artist")))
        return (0.65 * covered + 0.35 * precise + if (exact) 0.2 else 0.0).coerceAtMost(1.0)
    }

    /** Good enough to show among the best matches. */
    const val GOOD = 0.6

    private fun words(text: String): Set<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()
}
