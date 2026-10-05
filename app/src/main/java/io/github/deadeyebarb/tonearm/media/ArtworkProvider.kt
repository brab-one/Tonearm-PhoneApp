package io.github.deadeyebarb.tonearm.media

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.github.deadeyebarb.tonearm.container
import io.github.deadeyebarb.tonearm.youtube.YouTubeMusic
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/**
 * Serves `content://<app>.artwork/cover/<server>/<coverId>` so the notification, lock screen and
 * Android Auto can show cover art. They can't fetch from the server themselves: requests need
 * the app's credentials and, with mTLS, its client certificate. Nothing but images is exposed.
 */
class ArtworkProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val segments = uri.pathSegments
        if (segments.size != 3 || segments[0] != "cover" || mode != "r") throw FileNotFoundException(uri.toString())
        val file = ArtworkCache.fetch(requireNotNull(context), segments[1], segments[2])
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/*"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}

object ArtworkCache {
    private const val MAX_BYTES = 100L * 1024 * 1024
    private const val SIZE = 1024

    fun fetch(context: Context, serverId: String, coverId: String): File {
        val dir = File(context.cacheDir, "artwork").apply { mkdirs() }
        val file = File(dir, sha1("$serverId/$coverId"))
        if (file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        val (client, request) = if (YouTubeMusic.isYouTube(serverId)) {
            // YouTube Music covers are plain URLs; only its image hosts are fetched.
            val url = coverId.toHttpUrlOrNull()?.takeIf { it.isHttps && YouTubeMusic.isThumbnailHost(it.host) }
                ?: throw FileNotFoundException("Not a YouTube Music cover")
            context.container.baseHttpClient to Request.Builder().url(url).build()
        } else {
            val session = context.container.sessions.sessionBlocking(serverId) ?: throw FileNotFoundException("Unknown server")
            session.client to Request.Builder().url(session.coverUrl(coverId, SIZE)).build()
        }
        client.newCall(request).execute().use { response ->
            val type = response.header("Content-Type").orEmpty()
            if (!response.isSuccessful || !type.startsWith("image/")) throw FileNotFoundException("No cover art (HTTP ${response.code})")
            val tmp = File.createTempFile("art", ".tmp", dir)
            response.body.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(file)) tmp.delete()
        }
        trim(dir)
        return file
    }

    private fun trim(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (f in files) {
            total += f.length()
            if (total > MAX_BYTES) f.delete()
        }
    }

    private fun sha1(text: String) =
        MessageDigest.getInstance("SHA-1").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }
}
