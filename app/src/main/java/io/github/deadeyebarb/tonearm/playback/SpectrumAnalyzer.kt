package io.github.deadeyebarb.tonearm.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real-time spectrum of what is playing, for the visualizer. Fed from [TappedAudioSink] on the
 * playback thread with decoded PCM (16/24/32-bit or float, so it also works with hi-res float
 * output) and read by the UI. Frames carry the sink's own timestamps and are matched against its
 * playout clock, so the bars line up with what you hear rather than with what was just decoded.
 *
 * Unlike android.media.audiofx.Visualizer this needs no microphone permission.
 */
class SpectrumAnalyzer {
    @Volatile private var watchers = 0

    // Format (playback thread)
    private var pcmEncoding = C.ENCODING_INVALID
    private var channels = 0
    private var decimation = 1
    private var effectiveRate = 0
    private var sampleRate = 0

    // Sample accumulation (playback thread)
    private val window = FloatArray(FFT_SIZE)
    private var writeIndex = 0
    private var samplesSinceFrame = 0
    private var decimationAccumulator = 0f
    private var decimationCount = 0
    private var lastBufferTimeUs = C.TIME_UNSET

    // FFT scratch
    private val re = FloatArray(FFT_SIZE)
    private val im = FloatArray(FFT_SIZE)
    private val hann = FloatArray(FFT_SIZE) { (0.5 - 0.5 * cos(2 * PI * it / (FFT_SIZE - 1))).toFloat() }
    private var bandEdges = IntArray(BANDS + 1)

    // Frames shared with the UI
    private val lock = Any()
    private val frameTimes = LongArray(HISTORY)
    private val frameBands = Array(HISTORY) { FloatArray(BANDS) }
    private var frameCount = 0
    private var frameHead = 0

    // Playout clock: last position the sink reported, and when.
    @Volatile private var clockPositionUs = C.TIME_UNSET
    @Volatile private var clockWallUs = 0L
    @Volatile private var clockAdvancing = false

    /** The UI registers while a visualizer is on screen; no work is done otherwise. */
    fun watch() {
        watchers++
    }

    fun unwatch() {
        watchers = (watchers - 1).coerceAtLeast(0)
    }

    // --- playback thread -------------------------------------------------------------------

    fun configure(format: Format) {
        synchronized(lock) { frameCount = 0 }
        if (format.sampleMimeType != MimeTypes.AUDIO_RAW || format.channelCount <= 0 || format.sampleRate <= 0) {
            pcmEncoding = C.ENCODING_INVALID
            return
        }
        pcmEncoding = format.pcmEncoding
        channels = format.channelCount
        sampleRate = format.sampleRate
        decimation = max(1, format.sampleRate / 44_100)
        effectiveRate = format.sampleRate / decimation
        bandEdges = computeBandEdges(effectiveRate)
        resetAccumulation()
    }

    fun flush() {
        resetAccumulation()
        synchronized(lock) { frameCount = 0 }
        clockAdvancing = false
    }

    fun onPosition(positionUs: Long) {
        val now = SystemClock.elapsedRealtimeNanos() / 1000
        if (positionUs != clockPositionUs) {
            clockAdvancing = clockPositionUs != C.TIME_UNSET && positionUs > clockPositionUs
            clockPositionUs = positionUs
            clockWallUs = now
        } else if (now - clockWallUs > 120_000) {
            clockAdvancing = false
        }
    }

    fun onBuffer(buffer: ByteBuffer, presentationTimeUs: Long) {
        if (watchers == 0 || pcmEncoding == C.ENCODING_INVALID || presentationTimeUs == lastBufferTimeUs) return
        lastBufferTimeUs = presentationTimeUs
        val bytesPerSample = when (pcmEncoding) {
            C.ENCODING_PCM_8BIT -> 1
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 4
            else -> return
        }
        val bigEndian = pcmEncoding == C.ENCODING_PCM_16BIT_BIG_ENDIAN || pcmEncoding == C.ENCODING_PCM_24BIT_BIG_ENDIAN ||
            pcmEncoding == C.ENCODING_PCM_32BIT_BIG_ENDIAN
        val data = buffer.duplicate().order(if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        val frameBytes = bytesPerSample * channels
        val start = data.position()
        val frames = (data.limit() - start) / frameBytes
        val hop = max(effectiveRate / FRAMES_PER_SECOND, 1)
        val usPerFrame = 1_000_000.0 / sampleRate
        for (f in 0 until frames) {
            var sum = 0f
            val base = start + f * frameBytes
            for (ch in 0 until channels) sum += sample(data, base + ch * bytesPerSample, bigEndian)
            decimationAccumulator += sum / channels
            if (++decimationCount < decimation) continue
            window[writeIndex] = decimationAccumulator / decimation
            writeIndex = (writeIndex + 1) and (FFT_SIZE - 1)
            decimationAccumulator = 0f
            decimationCount = 0
            if (++samplesSinceFrame >= hop) {
                samplesSinceFrame = 0
                analyze(presentationTimeUs + (f * usPerFrame).toLong())
            }
        }
    }

    private fun sample(data: ByteBuffer, index: Int, bigEndian: Boolean): Float = when (pcmEncoding) {
        C.ENCODING_PCM_8BIT -> ((data.get(index).toInt() and 0xFF) - 128) / 128f
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> data.getShort(index) / 32768f
        C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> {
            val b0 = data.get(index).toInt() and 0xFF
            val b1 = data.get(index + 1).toInt() and 0xFF
            val b2 = data.get(index + 2).toInt()
            val v = if (bigEndian) (b0 shl 24 or (b1 shl 16) or ((b2 and 0xFF) shl 8)) shr 8 else (b2 shl 16) or (b1 shl 8) or b0
            v / 8_388_608f
        }
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> data.getInt(index) / 2_147_483_648f
        C.ENCODING_PCM_FLOAT -> data.getFloat(index)
        else -> 0f
    }

    private fun analyze(timeUs: Long) {
        for (i in 0 until FFT_SIZE) {
            re[i] = window[(writeIndex + i) and (FFT_SIZE - 1)] * hann[i]
            im[i] = 0f
        }
        fft(re, im)
        val bands = FloatArray(BANDS)
        val norm = FFT_SIZE / 4f
        for (b in 0 until BANDS) {
            var peak = 0f
            for (k in bandEdges[b] until max(bandEdges[b + 1], bandEdges[b] + 1)) {
                val mag = sqrt(re[k] * re[k] + im[k] * im[k])
                if (mag > peak) peak = mag
            }
            // dB with a gentle treble lift so the upper bands aren't always flat.
            val db = 20 * log10(peak / norm + 1e-9f) + 9f * b / BANDS
            bands[b] = ((db + FLOOR_DB) / FLOOR_DB).coerceIn(0f, 1f)
        }
        synchronized(lock) {
            frameHead = (frameHead + 1) % HISTORY
            frameTimes[frameHead] = timeUs
            bands.copyInto(frameBands[frameHead])
            frameCount = min(frameCount + 1, HISTORY)
        }
    }

    private fun resetAccumulation() {
        window.fill(0f)
        writeIndex = 0
        samplesSinceFrame = 0
        decimationAccumulator = 0f
        decimationCount = 0
        lastBufferTimeUs = C.TIME_UNSET
    }

    // --- UI thread -------------------------------------------------------------------------

    /** Fills [out] with the spectrum being heard right now. Returns false when nothing is playing. */
    fun read(out: FloatArray): Boolean {
        val position = clockPositionUs
        if (position == C.TIME_UNSET || !clockAdvancing) return false
        val now = SystemClock.elapsedRealtimeNanos() / 1000
        val playout = position + min(now - clockWallUs, 100_000)
        synchronized(lock) {
            if (frameCount == 0) return false
            var best = -1
            for (i in 0 until frameCount) {
                val idx = (frameHead - i + HISTORY) % HISTORY
                if (frameTimes[idx] <= playout) {
                    best = idx
                    break
                }
            }
            if (best < 0) return false
            frameBands[best].copyInto(out, endIndex = min(out.size, BANDS))
        }
        return true
    }

    companion object {
        const val BANDS = 48
        private const val FFT_SIZE = 2048
        private const val HISTORY = 96
        private const val FRAMES_PER_SECOND = 60
        private const val FLOOR_DB = 62f
        private const val MIN_HZ = 35.0
        private const val MAX_HZ = 16_000.0

        private fun computeBandEdges(rate: Int): IntArray {
            val maxHz = min(MAX_HZ, rate / 2.0 - 1)
            return IntArray(BANDS + 1) { b ->
                val hz = MIN_HZ * (maxHz / MIN_HZ).pow(b.toDouble() / BANDS)
                (hz * FFT_SIZE / rate).toInt().coerceIn(1, FFT_SIZE / 2 - 1)
            }
        }

        /** In-place iterative radix-2 FFT. */
        internal fun fft(re: FloatArray, im: FloatArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var len = 2
            while (len <= n) {
                val angle = -2 * PI / len
                val wRe = cos(angle).toFloat()
                val wIm = sin(angle).toFloat()
                var i = 0
                while (i < n) {
                    var curRe = 1f
                    var curIm = 0f
                    for (k in 0 until len / 2) {
                        val a = i + k
                        val b = a + len / 2
                        val tRe = re[b] * curRe - im[b] * curIm
                        val tIm = re[b] * curIm + im[b] * curRe
                        re[b] = re[a] - tRe
                        im[b] = im[a] - tIm
                        re[a] += tRe
                        im[a] += tIm
                        val nextRe = curRe * wRe - curIm * wIm
                        curIm = curRe * wIm + curIm * wRe
                        curRe = nextRe
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}

/** Passes everything through to the real sink while showing decoded PCM to the analyzer. */
class TappedAudioSink(delegate: AudioSink, private val analyzer: SpectrumAnalyzer) : ForwardingAudioSink(delegate) {
    override fun configure(config: AudioSink.AudioSinkConfig) {
        analyzer.configure(config.format)
        super.configure(config)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        analyzer.onBuffer(buffer, presentationTimeUs)
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
        super.getCurrentPositionUs(sourceEnded).also { if (it != AudioSink.CURRENT_POSITION_NOT_SET) analyzer.onPosition(it) }

    override fun flush() {
        analyzer.flush()
        super.flush()
    }

    override fun reset() {
        analyzer.flush()
        super.reset()
    }
}
