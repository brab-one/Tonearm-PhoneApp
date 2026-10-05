package io.github.deadeyebarb.tonearm

import android.Manifest
import android.app.SearchManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.deadeyebarb.tonearm.ui.TonearmRoot
import io.github.deadeyebarb.tonearm.ui.theme.TonearmTheme
import io.github.deadeyebarb.tonearm.data.AccentColor
import io.github.deadeyebarb.tonearm.media.toQueueSong
import io.github.deadeyebarb.tonearm.ui.player.rememberArtworkColor

class MainActivity : ComponentActivity() {
    private val openPlayerRequests = mutableIntStateOf(0)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private var askedForNotifications = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val c = container
        splash.setKeepOnScreenCondition { c.servers.state.value == null }
        if (savedInstanceState == null) handleIntent(intent)

        // The HUD theme is always dark: light status and navigation bar icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            val settings by c.settings.state.collectAsStateWithLifecycle()
            val player by c.player.state.collectAsStateWithLifecycle()
            // In "album art" accent mode the whole UI takes its neon from the playing cover.
            val playing = player.current?.toQueueSong()
            val artwork = if (settings.accent == AccentColor.ARTWORK) rememberArtworkColor(playing?.serverId, playing?.song?.coverArt) else null
            TonearmTheme(settings, artwork) {
                TonearmRoot(c, openPlayerRequests.intValue, ::requestNotificationPermission)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        container.player.connect()
    }

    override fun onStop() {
        container.player.release()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
            container.player.playFromSearch(intent.getStringExtra(SearchManager.QUERY).orEmpty())
            intent.action = null
        }
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) {
            intent.removeExtra(EXTRA_OPEN_PLAYER)
            openPlayerRequests.intValue++
        }
    }

    /** Download progress notifications need this on Android 13+; asked once, when the first download starts. */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || askedForNotifications) return
        askedForNotifications = true
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "io.github.deadeyebarb.tonearm.OPEN_PLAYER"
    }
}
