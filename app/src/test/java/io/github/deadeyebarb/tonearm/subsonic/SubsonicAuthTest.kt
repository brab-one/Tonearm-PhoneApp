package io.github.deadeyebarb.tonearm.subsonic

import io.github.deadeyebarb.tonearm.data.AuthMethod
import io.github.deadeyebarb.tonearm.data.ServerConfig
import io.github.deadeyebarb.tonearm.data.normalizeServerUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubsonicAuthTest {
    @Test
    fun `token matches the example in the Subsonic API docs`() {
        assertEquals("26719a1196d2a940705a59634eb18eab", SubsonicAuth.token("sesame", "c19b2d"))
    }

    @Test
    fun `legacy password is hex encoded`() {
        assertEquals("enc:736573616d65", SubsonicAuth.hexPassword("sesame"))
    }

    @Test
    fun `api url keeps the path prefix and adds token auth`() {
        val session = ServerSession(ServerConfig(name = "x", baseUrl = "https://example.com/music", username = "ann"), "pw", OkHttpClient())
        val url = session.apiUrl("getAlbum", listOf("id" to "al 1", "size" to null))
        assertEquals(listOf("music", "rest", "getAlbum.view"), url.pathSegments)
        assertEquals("ann", url.queryParameter("u"))
        assertEquals(SubsonicAuth.token("pw", url.queryParameter("s")!!), url.queryParameter("t"))
        assertEquals("json", url.queryParameter("f"))
        assertEquals("al 1", url.queryParameter("id"))
        assertNull(url.queryParameter("size"))
        assertNull(url.queryParameter("p"))
    }

    @Test
    fun `api key auth sends no username`() {
        val config = ServerConfig(name = "x", baseUrl = "https://example.com", auth = AuthMethod.API_KEY)
        val url = ServerSession(config, "key123", OkHttpClient()).apiUrl("ping")
        assertEquals("key123", url.queryParameter("apiKey"))
        assertNull(url.queryParameter("u"))
    }

    @Test
    fun `stream url asks for the raw file`() {
        val session = ServerSession(ServerConfig(name = "x", baseUrl = "https://example.com"), "pw", OkHttpClient())
        val url = session.streamUrl("s1", "raw", 0)
        assertEquals("raw", url.queryParameter("format"))
        assertNull(url.queryParameter("maxBitRate"))
        assertEquals("320", session.streamUrl("s1", "mp3", 320).queryParameter("maxBitRate"))
    }

    @Test
    fun `server urls are normalized`() {
        assertEquals("https://music.example.com", normalizeServerUrl("  music.example.com/ "))
        assertEquals("http://192.168.1.5:4533/nd", normalizeServerUrl("http://192.168.1.5:4533/nd//"))
    }
}
