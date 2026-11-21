package com.tensei.freetube

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.EOFException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withPermit

/** Read ahead at most one small window. A seek discards it, so cutting a clip stays seekable. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class RangeDownloadDataSource(private val upstream: DataSource.Factory) : DataSource {
    companion object {
        fun factory(upstream: DataSource.Factory) = DataSource.Factory { RangeDownloadDataSource(upstream) }
        private const val WINDOW = 2L * 1024 * 1024
    }

    private val listeners = mutableListOf<TransferListener>()
    private fun source() = upstream.createDataSource().also { listeners.forEach(it::addTransferListener) }
    private var direct: DataSource? = null
    private lateinit var spec: DataSpec
    private var consumed = 0L
    private var total: Long? = null
    private var length = 0L
    private var resolved: Uri? = null
    private var headers = emptyMap<String, List<String>>()
    private var window = ByteArray(0)
    private var index = 0

    override fun addTransferListener(transferListener: TransferListener) { listeners.add(transferListener) }
    override fun getUri() = resolved
    override fun getResponseHeaders() = headers

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        consumed = 0
        val first = source()
        direct = first
        length = first.open(spec)
        resolved = first.uri
        headers = first.responseHeaders
        total = headers.entries.firstOrNull { it.key.equals("Content-Range", true) }
            ?.value?.firstOrNull()?.substringAfterLast('/')?.toLongOrNull()
            ?: length.takeIf { spec.position == 0L && spec.length == C.LENGTH_UNSET.toLong() && it > 0 }
        val ranges = headers.any { (k, v) -> k.equals("Content-Range", true) ||
            (k.equals("Accept-Ranges", true) && v.any { it.equals("bytes", true) }) }
        if (ranges && spec.httpMethod == DataSpec.HTTP_METHOD_GET && spec.uri.scheme in listOf("https", "http")
            && length >= DownloadRanges.MIN_PARALLEL_BYTES) {
            first.close()
            direct = null
        }
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (readLength == 0) return 0
        direct?.let { return it.read(buffer, offset, readLength) }
        if (consumed == length) return C.RESULT_END_OF_INPUT
        if (index == window.size) {
            try {
                window = fetchWindow()
                index = 0
            } catch (failure: IOException) {
                val fallback = source()
                direct = fallback
                fallback.open(spec.subrange(consumed))
                return fallback.read(buffer, offset, readLength)
            }
        }
        val count = minOf(readLength, window.size - index)
        window.copyInto(buffer, offset, index, index + count)
        index += count
        consumed += count
        return count
    }

    private fun fetchWindow(): ByteArray {
        val size = minOf(WINDOW, length - consumed)
        val result = ByteArray(size.toInt())
        val start = spec.position + consumed
        val ranges = DownloadRanges.plan(start, size, DownloadRanges.MAX_CONNECTIONS, 128L * 1024)
        runBlocking {
            ranges.map { range -> async(Dispatchers.IO) {
                DownloadRanges.permits.withPermit {
                    val part = source()
                    try {
                        val bytes = range.last - range.first + 1
                        part.open(spec.subrange(range.first - spec.position, bytes))
                        val header = part.responseHeaders.entries.firstOrNull { it.key.equals("Content-Range", true) }
                            ?.value?.firstOrNull()
                        val resourceLength = header?.substringAfterLast('/')?.toLongOrNull()
                        if (resourceLength == null || resourceLength <= range.last ||
                            (total != null && resourceLength != total) ||
                            header?.substringBefore('/') != "bytes ${range.first}-${range.last}") throw IOException("Range mismatch")
                        var position = (range.first - start).toInt()
                        val end = position + bytes.toInt()
                        while (position < end) {
                            val read = part.read(result, position, end - position)
                            if (read <= 0) throw EOFException("Incomplete clip range")
                            position += read
                        }
                    } finally { part.close() }
                }
            } }.awaitAll()
        }
        return result
    }

    override fun close() {
        try { direct?.close() } finally {
            direct = null
            resolved = null
            headers = emptyMap()
            window = ByteArray(0)
            index = 0
        }
    }
}
