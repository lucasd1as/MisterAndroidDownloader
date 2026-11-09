package io.github.lucasd1as.mdm

import android.content.Context
import android.os.Environment
import android.webkit.WebSettings
import org.json.JSONObject
import java.io.File

class NativeException(message: String) : Exception(message)

/**
 * The download manager, as Kotlin: every function is one call into the Rust library. They take
 * a lock briefly and return; the ones that touch the network ([probe]) block, so call them from
 * a background dispatcher.
 */
object Downloads {
    @Volatile
    private var ready = false

    /** Why the native library did not start, for the screen to say. */
    @Volatile
    var initError: String? = null
        private set

    fun init(context: Context) {
        synchronized(this) {
            if (ready) return
            val app = context.applicationContext
            try {
                val dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: File(app.filesDir, "downloads")
                val config = JSONObject()
                    .put("data_dir", File(app.filesDir, "mdm").absolutePath)
                    .put("download_dir", dir.absolutePath)
                    .put("user_agent", userAgent(app))
                val reply = JSONObject(Native.nativeInit(app, config.toString()))
                check(reply.optBoolean("ok")) { reply.optString("error", "unknown error") }
                ready = true
                initError = null
            } catch (e: Throwable) {
                initError = e.message ?: e.toString()
                throw e
            }
        }
    }

    /** The device's browser's User-Agent, as the sites it visits see it. */
    private fun userAgent(context: Context): String? =
        runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull()

    private fun call(method: String, args: JSONObject = JSONObject()): JSONObject {
        val reply = JSONObject(Native.nativeCall(method, args.toString()))
        if (!reply.optBoolean("ok")) throw NativeException(reply.optString("error", "unknown error"))
        return reply
    }

    fun list(): Listing = Listing.parse(call("list").getJSONObject("result"))

    /** Queues a download; returns its id. */
    fun add(url: String, referer: String? = null): Long {
        val args = JSONObject().put("url", url)
        if (referer != null) args.put("referer", referer)
        return call("add", args).getJSONObject("result").getLong("id")
    }

    fun probe(url: String): Probe {
        val r = call("probe", JSONObject().put("url", url)).getJSONObject("result")
        return Probe(r.getString("name"), r.optLongOrNull("size"), r.getString("url"))
    }

    fun pause(id: Long) { call("pause", idArgs(id)) }
    fun resume(id: Long) { call("resume", idArgs(id)) }
    fun pauseAll() { call("pause_all") }
    fun resumeAll() { call("resume_all") }

    fun remove(id: Long, deleteFiles: Boolean) {
        call("remove", idArgs(id).put("delete_files", deleteFiles))
    }

    fun clearCompleted(): Int = call("clear_completed").getJSONObject("result").getInt("removed")

    fun setExported(id: Long, uri: String) {
        call("set_exported", idArgs(id).put("uri", uri))
    }

    fun setSettings(settings: AppSettings): AppSettings =
        AppSettings.parse(call("set_settings", settings.toJson()).getJSONObject("result"))

    private fun idArgs(id: Long) = JSONObject().put("id", id)
}
