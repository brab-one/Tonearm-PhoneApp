package io.github.deadeyebarb.tonearm.integrations

import java.text.Normalizer

object Names {
    /** Case-, accent- and article-insensitive name used to match names across services. */
    fun normalize(name: String): String {
        val folded = Normalizer.normalize(name, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "")
        return folded.lowercase().replace('&', ' ').replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
            .removePrefix("the ").trim()
    }

    fun key(artist: String, title: String) = normalize(artist) + "|" + normalize(title)
}
