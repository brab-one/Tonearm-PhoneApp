package io.github.deadeyebarb.tonearm.ui.common

import io.github.deadeyebarb.tonearm.subsonic.Song
import java.util.Locale

fun formatDuration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = s % 3600 / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%d:%02d", m, sec)
}

fun formatMs(ms: Long) = formatDuration(ms / 1000)

/** "1 h 12 min" style, for album and playlist totals. */
fun formatTotal(seconds: Int): String {
    val minutes = (seconds + 30) / 60
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.getDefault(), "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.getDefault(), "%.0f MB", bytes / (1L shl 20).toDouble())
    else -> "${bytes / 1024} KB"
}

private val LOSSLESS = setOf("flac", "alac", "wav", "aif", "aiff", "ape", "wv", "dsf", "dff", "tta")

fun isLossless(suffix: String?) = suffix?.lowercase() in LOSSLESS

fun isHiRes(bitDepth: Int?, sampleRate: Int?) = (bitDepth ?: 0) > 16 || (sampleRate ?: 0) > 48_000

fun khz(rate: Int): String = if (rate % 1000 == 0) "${rate / 1000}" else String.format(Locale.getDefault(), "%.1f", rate / 1000.0)

fun resolution(bitDepth: Int?, sampleRate: Int?): String? = when {
    bitDepth != null && sampleRate != null -> "$bitDepth-bit / ${khz(sampleRate)} kHz"
    sampleRate != null -> "${khz(sampleRate)} kHz"
    bitDepth != null -> "$bitDepth-bit"
    else -> null
}

/** "FLAC · 24-bit / 96 kHz · 2 834 kbps" */
fun qualityLabel(codec: String?, bitDepth: Int?, sampleRate: Int?, bitRateKbps: Int?): String =
    listOfNotNull(codec?.uppercase(), resolution(bitDepth, sampleRate), bitRateKbps?.takeIf { it > 0 }?.let { "$it kbps" })
        .joinToString(" · ")

fun Song.shortQuality(): String? {
    val codec = suffix?.uppercase() ?: return null
    return if (isLossless(suffix) && isHiRes(bitDepth, samplingRate)) "$codec ${resolution(bitDepth, samplingRate)}" else codec
}
