package io.github.lucasd1as.mdm

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the process alive while downloads run (a foreground service with a progress
 * notification), copies finished files into Downloads and tells the user about them. It stops
 * itself when nothing is queued, downloading or being copied.
 *
 * The loop runs on the main thread, so [onStartCommand] and the decision to stop never race; the
 * calls into the native library and the file copies run on the IO dispatcher.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null
    private var lastStartId = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // Main thread only.
    private val exporting = mutableSetOf<Long>()
    private val exportFailed = mutableSetOf<Long>()
    private val failureShown = mutableSetOf<Long>()
    private val completionShown = mutableSetOf<Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Every start command of startForegroundService() owes the system a startForeground().
        ServiceCompat.startForeground(
            this, Notifications.ID_SERVICE, Notifications.progress(this, null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (intent?.action == ACTION_PAUSE_ALL) {
            scope.launch(Dispatchers.IO) { runCatching { Downloads.pauseAll() } }
        }
        if (loop?.isActive != true) loop = scope.launch { run() }
        return START_NOT_STICKY
    }

    private suspend fun run() {
        acquireWakeLock()
        try {
            while (true) {
                val listing = try {
                    withContext(Dispatchers.IO) { Downloads.list() }
                } catch (e: Exception) {
                    Log.e(TAG, "list failed", e)
                    break
                }
                handleFinished(listing)
                if (listing.active == 0 && exporting.isEmpty()) break
                ServiceCompat.startForeground(
                    this, Notifications.ID_SERVICE, Notifications.progress(this, listing),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
                delay(1000)
            }
        } finally {
            releaseWakeLock()
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // Ignored if the app asked for another start in the meantime; that start runs a new loop.
        stopSelf(lastStartId)
    }

    private fun handleFinished(listing: Listing) {
        val failedNow = listing.items.filter { it.status == Status.FAILED }.map { it.id }.toSet()
        failureShown.retainAll(failedNow)
        for (item in listing.items) {
            when {
                item.status == Status.COMPLETE && item.inPlace && completionShown.add(item.id) ->
                    Notifications.completed(this, item, null)

                item.status == Status.COMPLETE && !item.inPlace && item.exported == null && item.path != null &&
                    item.id !in exporting && item.id !in exportFailed -> export(item)

                item.status == Status.FAILED && failureShown.add(item.id) ->
                    Notifications.failed(this, item)
            }
        }
    }

    private fun export(item: DownloadItem) {
        exporting += item.id
        scope.launch {
            try {
                val uri: Uri = withContext(Dispatchers.IO) {
                    Exporter.export(applicationContext, item).also { Downloads.setExported(item.id, it.toString()) }
                }
                Notifications.completed(this@DownloadService, item, uri)
            } catch (e: Exception) {
                Log.e(TAG, "copying ${item.name} to Downloads failed", e)
                exportFailed += item.id
                // The file is safe in the app's folder; the next service run tries again.
                Notifications.completed(this@DownloadService, item, null)
            } finally {
                exporting -= item.id
            }
        }
    }

    /** Android 15 limits data-transfer services to about six hours a day. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        runCatching { Downloads.pauseAll() }
        Notifications.timedOut(this)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mdm:downloads").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_LIMIT_MS)
        }
        // Wi-Fi power saving throttles long transfers once the screen is off.
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "mdm:downloads")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MDM"
        const val ACTION_PAUSE_ALL = "io.github.lucasd1as.mdm.PAUSE_ALL"
        private const val WAKE_LOCK_LIMIT_MS = 6 * 60 * 60 * 1000L

        /** Makes sure the service is running; call it when a download is added or resumed. */
        fun ensureRunning(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }
    }
}
