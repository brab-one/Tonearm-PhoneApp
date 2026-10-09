package io.github.deadeyebarb.tonearm.connect

import android.content.Context
import androidx.core.content.edit
import io.github.deadeyebarb.tonearm.data.TonearmServerInfo
import io.github.deadeyebarb.tonearm.subsonic.ServerSession

/**
 * What the last lookup of the Tonearm server found at each music server, kept across restarts. Right after a cold start
 * (a car's), before it's looked up again, [ConnectRouter.server] is null whether there's a Tonearm server or not: this
 * tells the two apart.
 */
class KnownTonearmServers(context: Context) {
    private val prefs = context.getSharedPreferences("tonearm-servers", Context.MODE_PRIVATE)

    /** Remembers what the lookup at a music server ([ConnectRouter.keyOf]) found: its Tonearm server, or null for none. */
    fun lookedUp(key: String, info: TonearmServerInfo?) {
        val resume = info?.resume == true
        val dislikes = info?.dislikes == true
        if (known(RESUME + key) == resume && known(DISLIKES + key) == dislikes) return
        prefs.edit {
            putBoolean(RESUME + key, resume)
            putBoolean(DISLIKES + key, dislikes)
        }
    }

    /**
     * Whether [session]'s music server has a Tonearm server that keeps where devices left off, as last looked up (also
     * before this start); null when it never was.
     */
    fun resume(session: ServerSession): Boolean? = known(RESUME + ConnectRouter.keyOf(session))

    /** Whether it has one that keeps dislikes, the same way. */
    fun dislikes(session: ServerSession): Boolean? = known(DISLIKES + ConnectRouter.keyOf(session))

    private fun known(key: String): Boolean? = if (prefs.contains(key)) prefs.getBoolean(key, false) else null

    private companion object {
        const val RESUME = "resume|"
        const val DISLIKES = "dislikes|"
    }
}
