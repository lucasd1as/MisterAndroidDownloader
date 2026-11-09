package io.github.lucasd1as.mdm

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DownloadsViewModel(app: Application) : AndroidViewModel(app) {
    /** The list, refreshed twice a second while the screen is visible. */
    val listing: StateFlow<Listing> = flow {
        while (true) {
            emit(runCatching { Downloads.list() }.getOrNull())
            delay(500)
        }
    }
        .mapNotNull { it }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(3000), Listing.EMPTY)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** One-off messages for the snackbar. */
    val messages: SharedFlow<String> = _messages

    private val ctx get() = getApplication<Application>()

    private fun message(text: String) {
        _messages.tryEmit(text)
    }

    /** Runs [block] off the main thread, reporting a native error as a message. */
    private fun background(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: NativeException) {
                message(e.message ?: ctx.getString(R.string.error_unknown))
            }
        }
    }

    fun add(urls: List<String>) = background {
        var added = 0
        var firstError: String? = null
        for (url in urls) {
            try {
                Downloads.add(url)
                added++
            } catch (e: NativeException) {
                if (firstError == null) firstError = e.message
            }
        }
        if (added > 0) DownloadService.ensureRunning(ctx)
        firstError?.let { message(it) }
    }

    /** Looks the address up for the add dialog; null when the server can't say. */
    suspend fun probe(url: String): Result<Probe> = withContext(Dispatchers.IO) {
        runCatching { Downloads.probe(url) }
    }

    fun pause(item: DownloadItem) = background { Downloads.pause(item.id) }

    fun resume(item: DownloadItem) = background {
        Downloads.resume(item.id)
        DownloadService.ensureRunning(ctx)
    }

    fun pauseAll() = background { Downloads.pauseAll() }

    fun resumeAll() = background {
        Downloads.resumeAll()
        DownloadService.ensureRunning(ctx)
    }

    fun remove(item: DownloadItem, deleteFile: Boolean) = background {
        if (deleteFile) item.exported?.let { Exporter.delete(ctx, Uri.parse(it)) }
        Downloads.remove(item.id, deleteFile)
    }

    fun clearCompleted() = background { Downloads.clearCompleted() }

    fun setSettings(settings: AppSettings) = background { Downloads.setSettings(settings) }

    fun open(item: DownloadItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val opened = Exporter.open(ctx, item)
            if (!opened) {
                message(
                    ctx.getString(
                        if (item.exported == null) R.string.msg_in_app_folder else R.string.msg_cannot_open,
                    ),
                )
            }
        }
    }

    fun copyLink(item: DownloadItem) {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("link", item.url))
        message(ctx.getString(R.string.msg_link_copied))
    }

    /** The first address on the clipboard, for the Paste button. */
    fun clipboardUrl(): String? {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
        return text?.let { UrlText.first(it) }
    }
}
