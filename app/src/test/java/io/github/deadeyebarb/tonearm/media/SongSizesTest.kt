package io.github.deadeyebarb.tonearm.media

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SongSizesTest {
    private val unset = C.LENGTH_UNSET.toLong()

    @Test
    fun `a response without a length owes the rest of the file, or the part asked for`() {
        assertEquals(685_000L, SongSizes.expected(685_000, 0, unset))
        // Resumed after 475 136 bytes.
        assertEquals(209_864L, SongSizes.expected(685_000, 475_136, unset))
        assertEquals(65_536L, SongSizes.expected(685_000, 475_136, 65_536))
        assertEquals(209_864L, SongSizes.expected(685_000, 475_136, 1_000_000))
        assertNull(SongSizes.expected(685_000, 685_000, unset))
    }

    @Test
    fun `a response that ends before the file does was cut off`() {
        assertEquals(684_000L, SongSizes.afterRead(685_000, 1_000))
        assertEquals(0L, SongSizes.afterRead(1_000, 1_000))
        // The end, with everything there.
        assertEquals(0L, SongSizes.afterRead(0, C.RESULT_END_OF_INPUT))
        // The end, with 209 864 bytes still to come.
        assertNull(SongSizes.afterRead(209_864, C.RESULT_END_OF_INPUT))
    }

    @Test
    fun `the size comes from the file version a song is asked for at`() {
        assertEquals(685_000L, SongUri.sizeOf(SongUri.versionOf("flac", 685_000)))
        assertEquals(685_000L, SongUri.sizeOf(SongUri.versionOf(null, 685_000)))
        assertNull(SongUri.sizeOf(SongUri.versionOf("flac", null)))
        assertNull(SongUri.sizeOf(null))
        assertNull(SongUri.sizeOf("flac"))
    }
}
