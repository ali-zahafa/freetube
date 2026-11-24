package com.tensei.freetube

internal object WatchHistoryPolicy {
    const val minimumPlaybackMs = 3_000L
    const val defaultRetentionDays = 90
    val retentionOptions = listOf(7, 14, 30, defaultRetentionDays)

    fun cutoff(nowMs: Long, retentionDays: Int): Long = nowMs - retentionDays * 86_400_000L

    fun percent(positionMs: Long, durationMs: Long): Int? {
        if (positionMs <= 0 || durationMs <= 0) return null
        return (100.0 * positionMs / durationMs).toInt().coerceIn(1, 100)
    }

    fun resumePosition(positionMs: Long, durationMs: Long, live: Boolean): Long =
        if (live || (durationMs > 0 && positionMs >= durationMs - 2_000)) 0 else positionMs.coerceAtLeast(0)
}
