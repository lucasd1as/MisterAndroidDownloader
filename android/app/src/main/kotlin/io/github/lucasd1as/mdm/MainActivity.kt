package io.github.lucasd1as.mdm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import io.github.lucasd1as.mdm.ui.MdmRoot
import io.github.lucasd1as.mdm.ui.MdmTheme

class MainActivity : ComponentActivity() {
    private val viewModel: DownloadsViewModel by viewModels()

    /** An address handed to us (shared, or opened from a browser), for the add dialog to offer. */
    private var incoming by mutableStateOf<String?>(null)

    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* downloads work either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) takeAddress(intent)
        askForNotificationsOnce()
        // Downloads picked up again after the process was killed need their service.
        if (runCatching { Downloads.list().active }.getOrDefault(0) > 0) DownloadService.ensureRunning(this)

        setContent {
            MdmTheme {
                MdmRoot(
                    viewModel = viewModel,
                    incoming = incoming,
                    onIncomingHandled = { incoming = null },
                    initError = Downloads.initError,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeAddress(intent)
    }

    private fun takeAddress(intent: Intent?) {
        incoming = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let(UrlText::first)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: incoming
    }

    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
