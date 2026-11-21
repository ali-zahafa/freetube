package com.tensei.freetube

import java.util.Locale

data class DownloadClip(val start: Long, val end: Long) {
    init { require(start >= 0 && end > start && end <= Long.MAX_VALUE / 1000) }

    val suffix get() = "_clip_${start}-${end}"
    val label get() = "${time(start)}–${time(end)}"

    companion object {
        fun time(sec: Long): String = String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60)

        private fun seconds(text: String): Long? {
            val parts = text.trim().split(':')
            if (parts.size != 2 || parts[0].length !in 1..9 || parts[1].length != 2 ||
                parts.any { n -> !n.all(Char::isDigit) }) return null
            val seconds = parts[1].toIntOrNull()?.takeIf { it < 60 } ?: return null
            return (parts[0].toLongOrNull() ?: return null) * 60 + seconds
        }

        fun parse(from: String, to: String, duration: Long): DownloadClip? {
            val start = seconds(from) ?: return null
            val end = seconds(to) ?: return null
            if (end <= start || duration > 0 && end > duration) return null
            return DownloadClip(start, end)
        }
    }
}
