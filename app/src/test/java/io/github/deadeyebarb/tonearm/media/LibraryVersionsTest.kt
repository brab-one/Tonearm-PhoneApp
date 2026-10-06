package io.github.deadeyebarb.tonearm.media

import io.github.deadeyebarb.tonearm.subsonic.Song
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryVersionsTest {
    private val mp3 = Song("1", title = "Lucky", suffix = "mp3", size = 8_000_000, bitRate = 320)
    private val cdFlac = Song("1", title = "Lucky", suffix = "flac", size = 30_000_000, bitDepth = 16, samplingRate = 44_100)
    private val hiRes = Song("1", title = "Lucky", suffix = "flac", size = 90_000_000, bitDepth = 24, samplingRate = 96_000)

    @Test
    fun `lossless beats lossy, hi-res beats CD quality`() {
        assertTrue(LibraryVersions.isUpgrade(mp3, cdFlac))
        assertTrue(LibraryVersions.isUpgrade(cdFlac, hiRes))
        assertFalse(LibraryVersions.isUpgrade(hiRes, cdFlac))
        assertFalse(LibraryVersions.isUpgrade(cdFlac, mp3))
        assertFalse(LibraryVersions.isUpgrade(cdFlac, cdFlac.copy(size = 30_000_100)))
    }

    @Test
    fun `a replaced file is noticed by id, format or size`() {
        assertTrue(LibraryVersions.changed(mp3, cdFlac))
        assertTrue(LibraryVersions.changed(cdFlac, cdFlac.copy(id = "2")))
        assertTrue(LibraryVersions.changed(cdFlac, cdFlac.copy(size = 30_000_100)))
        assertFalse(LibraryVersions.changed(cdFlac, cdFlac.copy(title = "Lucky (Remastered)")))
    }
}
