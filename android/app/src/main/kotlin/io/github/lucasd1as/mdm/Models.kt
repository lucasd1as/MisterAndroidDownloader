package io.github.lucasd1as.mdm

import org.json.JSONObject

enum class Status(val wire: String) {
    QUEUED("queued"),
    DOWNLOADING("downloading"),
    PAUSED("paused"),
    COMPLETE("complete"),
    FAILED("failed");

    companion object {
        fun parse(wire: String): Status = entries.firstOrNull { it.wire == wire } ?: FAILED
    }
}

data class DownloadItem(
    val id: Long,
    val url: String,
    val name: String,
    /** The file in the app's own folder while it is there (not yet copied to Downloads). */
    val path: String?,
    val status: Status,
    val size: Long?,
    val downloaded: Long,
    val speed: Long,
    val connections: Int,
    val error: String?,
    val addedAt: Long,
    val finishedAt: Long?,
    /** content:// URI of the finished file in the public Downloads folder. */
    val exported: String?,
    val checksum: String?,
    /** Downloaded straight into the user's chosen folder: [path] is the final file. */
    val inPlace: Boolean,
) {
    val isActive: Boolean get() = status == Status.QUEUED || status == Status.DOWNLOADING

    /** 0..1, or null when the size is not known. */
    val fraction: Float?
        get() = size?.takeIf { it > 0 }?.let { (downloaded.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }

    companion object {
        fun parse(o: JSONObject) = DownloadItem(
            id = o.getLong("id"),
            url = o.getString("url"),
            name = o.getString("name"),
            path = o.optStringOrNull("path"),
            status = Status.parse(o.getString("status")),
            size = o.optLongOrNull("size"),
            downloaded = o.optLong("downloaded"),
            speed = o.optLong("speed"),
            connections = o.optInt("connections"),
            error = o.optStringOrNull("error"),
            addedAt = o.optLong("added_at"),
            finishedAt = o.optLongOrNull("finished_at"),
            exported = o.optStringOrNull("exported"),
            checksum = o.optStringOrNull("checksum"),
            inPlace = o.optBoolean("in_place", false),
        )
    }
}

data class AppSettings(
    val maxConnections: Int = 16,
    val maxActive: Int = 3,
    /** Bytes per second for everything together; 0 is unlimited. */
    val speedLimit: Long = 0,
    /** A folder to download straight into (empty: the app's folder, copied to Downloads). */
    val saveDir: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("max_connections", maxConnections)
        .put("max_active", maxActive)
        .put("speed_limit", speedLimit)
        .put("save_dir", saveDir)

    companion object {
        fun parse(o: JSONObject) = AppSettings(
            maxConnections = o.optInt("max_connections", 16),
            maxActive = o.optInt("max_active", 3),
            speedLimit = o.optLong("speed_limit", 0),
            saveDir = o.optString("save_dir", ""),
        )
    }
}

data class Listing(
    val items: List<DownloadItem>,
    val settings: AppSettings,
    /** Bytes per second, all downloads together. */
    val speed: Long,
    /** Queued or downloading. */
    val active: Int,
) {
    companion object {
        val EMPTY = Listing(emptyList(), AppSettings(), 0, 0)

        fun parse(o: JSONObject): Listing {
            val arr = o.getJSONArray("items")
            return Listing(
                items = List(arr.length()) { DownloadItem.parse(arr.getJSONObject(it)) },
                settings = AppSettings.parse(o.getJSONObject("settings")),
                speed = o.optLong("speed"),
                active = o.optInt("active"),
            )
        }
    }
}

data class Probe(val name: String, val size: Long?, val url: String)

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else getString(key)

internal fun JSONObject.optLongOrNull(key: String): Long? =
    if (isNull(key)) null else getLong(key)
