package io.github.lucasd1as.mdm

/** Pulling web addresses out of shared or pasted text. */
object UrlText {
    private val url = Regex("""(?i)\b(?:https?|ftps?)://[^\s<>"' ]+""")

    /** Every address in [text], once each, in order, without the punctuation around them. */
    fun all(text: String): List<String> =
        url.findAll(text).map { trim(it.value) }.filter { it.length > 8 }.distinct().toList()

    fun first(text: String): String? = all(text).firstOrNull()

    private fun trim(raw: String): String {
        var s = raw.trimEnd('.', ',', ';', ':', '!', '?', '"', '\'')
        // A closing bracket belongs to the address only if it has its opening one.
        while (s.isNotEmpty() && s.last() in ")]}" && !hasOpening(s)) s = s.dropLast(1)
        return s
    }

    private fun hasOpening(s: String): Boolean = when (s.last()) {
        ')' -> s.count { it == '(' } >= s.count { it == ')' }
        ']' -> s.count { it == '[' } >= s.count { it == ']' }
        else -> s.count { it == '{' } >= s.count { it == '}' }
    }
}

/** Short, language-neutral times for the list: `35s`, `4m 12s`, `1h 05m`. */
object Eta {
    /** Seconds left at [speed] bytes/s, or null when it can't be said. */
    fun seconds(item: DownloadItem): Long? {
        val size = item.size ?: return null
        if (item.status != Status.DOWNLOADING || item.speed <= 0 || size <= item.downloaded) return null
        return (size - item.downloaded) / item.speed
    }

    fun format(seconds: Long): String = when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${(seconds % 60).toString().padStart(2, '0')}s"
        seconds < 86_400 -> "${seconds / 3600}h ${((seconds % 3600) / 60).toString().padStart(2, '0')}m"
        else -> "${seconds / 86_400}d ${((seconds % 86_400) / 3600)}h"
    }
}
