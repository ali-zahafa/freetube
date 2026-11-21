package com.tensei.freetube

import kotlinx.coroutines.sync.Semaphore

internal object DownloadRanges {
    const val MAX_CONNECTIONS = 16
    const val MIN_PARALLEL_BYTES = 1024L * 1024
    val permits = Semaphore(MAX_CONNECTIONS)

    fun plan(start: Long, length: Long, workers: Int, targetBytes: Long): List<LongRange> {
        require(start >= 0 && length > 0 && workers in 1..MAX_CONNECTIONS && targetBytes > 0)
        val count = minOf(workers.toLong(), 1 + (length - 1) / targetBytes).toInt()
        val size = minOf(targetBytes, 1 + (length - 1) / count)
        var offset = start
        var left = length
        return List(count) {
            val bytes = minOf(left, size)
            (offset until offset + bytes).also { offset += bytes; left -= bytes }
        }
    }
}
