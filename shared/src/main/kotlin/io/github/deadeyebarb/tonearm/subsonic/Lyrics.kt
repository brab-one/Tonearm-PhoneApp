package io.github.deadeyebarb.tonearm.subsonic

data class LyricLine(val startMs: Long?, val text: String)

data class Lyrics(val synced: Boolean, val lines: List<LyricLine>) {
    /** Index of the line being sung at [positionMs], or -1 before the first line. */
    fun indexAt(positionMs: Long): Int {
        if (!synced) return -1
        var index = -1
        for ((i, line) in lines.withIndex()) {
            if ((line.startMs ?: continue) <= positionMs) index = i else break
        }
        return index
    }

    companion object {
        private val TIMESTAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
        private val METADATA = Regex("""^\[[a-zA-Z]+:.*]$""")

        fun from(structured: StructuredLyrics): Lyrics {
            val synced = structured.synced && structured.line.any { it.start != null }
            val lines = structured.line.map { line ->
                LyricLine(line.start?.let { (it - structured.offset).coerceAtLeast(0) }.takeIf { synced }, line.value)
            }
            return Lyrics(synced, if (synced) lines.sortedBy { it.startMs ?: 0 } else lines)
        }

        /** Parses LRC (`[mm:ss.xx] text`, several stamps per line allowed) or falls back to plain text. */
        fun parse(text: String): Lyrics {
            val synced = mutableListOf<LyricLine>()
            val plain = mutableListOf<LyricLine>()
            for (raw in text.lines()) {
                val line = raw.trim()
                val stamps = TIMESTAMP.findAll(line).toList()
                if (stamps.isNotEmpty() && line.startsWith("[")) {
                    val content = line.substring(stamps.last().range.last + 1).trim()
                    for (stamp in stamps) {
                        val (min, sec, frac) = stamp.destructured
                        val fracMs = when (frac.length) {
                            0 -> 0L
                            1 -> frac.toLong() * 100
                            2 -> frac.toLong() * 10
                            else -> frac.take(3).toLong()
                        }
                        synced += LyricLine(min.toLong() * 60_000 + sec.toLong() * 1000 + fracMs, content)
                    }
                } else if (!METADATA.matches(line)) {
                    plain += LyricLine(null, raw.trimEnd())
                }
            }
            return if (synced.isNotEmpty()) {
                Lyrics(true, synced.sortedBy { it.startMs })
            } else {
                Lyrics(false, plain.dropWhile { it.text.isBlank() }.dropLastWhile { it.text.isBlank() })
            }
        }
    }
}
