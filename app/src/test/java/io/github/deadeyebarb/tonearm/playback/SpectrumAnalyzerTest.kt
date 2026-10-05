package io.github.deadeyebarb.tonearm.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin

class SpectrumAnalyzerTest {
    private fun pcm16Tone(hz: Double, rate: Int, seconds: Double): ByteBuffer {
        val frames = (rate * seconds).toInt()
        val buffer = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val v = (sin(2 * PI * hz * i / rate) * 0.8 * Short.MAX_VALUE).toInt().toShort()
            buffer.putShort(v).putShort(v)
        }
        buffer.flip()
        return buffer
    }

    private fun format(rate: Int, encoding: Int) = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(encoding).setChannelCount(2).setSampleRate(rate).build()

    @Test
    fun `fft finds the bin of a pure tone`() {
        val n = 2048
        val re = FloatArray(n) { sin(2 * PI * 64 * it / n).toFloat() }
        val im = FloatArray(n)
        SpectrumAnalyzer.fft(re, im)
        val peak = (0 until n / 2).maxBy { re[it] * re[it] + im[it] * im[it] }
        assertEquals(64, peak)
    }

    @Test
    fun `a 1 kHz tone lights the matching band once it is being heard`() {
        val analyzer = SpectrumAnalyzer()
        analyzer.watch()
        analyzer.configure(format(44_100, C.ENCODING_PCM_16BIT))
        val buffer = pcm16Tone(1000.0, 44_100, 0.5)
        analyzer.onBuffer(buffer, presentationTimeUs = 0)
        assertEquals("the analyzer must not consume the sink's buffer", 0, buffer.position())

        val bands = FloatArray(SpectrumAnalyzer.BANDS)
        assertFalse("nothing is playing yet", analyzer.read(bands))
        analyzer.onPosition(100_000)
        analyzer.onPosition(400_000)
        assertTrue(analyzer.read(bands))

        val loudest = bands.indices.maxBy { bands[it] }
        // Bands are log-spaced from 35 Hz to 16 kHz.
        val expected = (SpectrumAnalyzer.BANDS * ln(1000.0 / 35) / ln(16_000.0 / 35)).roundToInt()
        assertTrue("loudest band $loudest, expected about $expected", loudest in expected - 2..expected + 1)
        assertTrue(bands[loudest] > 0.5f)
    }

    @Test
    fun `nothing is analysed while no visualizer is watching`() {
        val analyzer = SpectrumAnalyzer()
        analyzer.configure(format(44_100, C.ENCODING_PCM_16BIT))
        analyzer.onBuffer(pcm16Tone(1000.0, 44_100, 0.5), 0)
        analyzer.onPosition(100_000)
        analyzer.onPosition(400_000)
        assertFalse(analyzer.read(FloatArray(SpectrumAnalyzer.BANDS)))
    }
}
