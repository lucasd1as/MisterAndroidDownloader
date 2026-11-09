package io.github.lucasd1as.mdm

import android.app.Application
import android.util.Log

class MdmApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        // Starts the download manager; downloads that were running when the process died are
        // queued again by it. A failure is kept in `Downloads.initError` for the screen.
        runCatching { Downloads.init(this) }.onFailure { Log.e("MDM", "native library did not start", it) }
    }
}
