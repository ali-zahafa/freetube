package com.tensei.freetube

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ClipRangeTest {
    private val bytes = ByteArray(5 * 1024 * 1024) { (it xor (it shr 12)).toByte() }
    private val spec = DataSpec(Uri.parse("https://example.test/media"))
    private val workers = AtomicInteger()
    private val peak = AtomicInteger()

    private fun factory(reject: Boolean) = DataSource.Factory {
        object : DataSource {
            var pos = 0
            var end = 0
            var part = false
            var headers = emptyMap<String, List<String>>()
            override fun getResponseHeaders() = headers
            override fun getUri() = spec.uri
            override fun addTransferListener(listener: TransferListener) {}
            override fun open(request: DataSpec): Long {
                pos = request.position.toInt()
                end = if (request.length == C.LENGTH_UNSET.toLong()) bytes.size else pos + request.length.toInt()
                part = request.length in 1..(128L * 1024)
                headers = if (part && !reject) mapOf("Content-Range" to listOf("bytes $pos-${end - 1}/${bytes.size}"))
                    else mapOf("Accept-Ranges" to listOf("bytes"))
                if (part) {
                    val count = workers.incrementAndGet()
                    peak.updateAndGet { maxOf(it, count) }
                    Thread.sleep(15)
                }
                return (end - pos).toLong()
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = minOf(length, end - pos)
                if (count == 0) return C.RESULT_END_OF_INPUT
                bytes.copyInto(buffer, offset, pos, pos + count)
                pos += count
                return count
            }
            override fun close() { if (part) { workers.decrementAndGet(); part = false } }
        }
    }

    @Test fun recordsEqualPayloadTimingsWithFixedRequestLatency() {
        val count = 2 * 1024 * 1024
        val serial = ByteArray(count)
        val parallel = ByteArray(count)
        val upstream = factory(false)
        val serialStart = System.nanoTime()
        for (range in DownloadRanges.plan(0L, count.toLong(), 16, 128L * 1024)) {
            val src = upstream.createDataSource()
            try {
                val size = (range.last - range.first + 1).toInt()
                src.open(spec.subrange(range.first, size.toLong()))
                assertEquals(size, src.read(serial, range.first.toInt(), size))
            } finally { src.close() }
        }
        val serialMs = (System.nanoTime() - serialStart) / 1000000
        val src = RangeDownloadDataSource(upstream)
        val parallelStart = System.nanoTime()
        try {
            src.open(spec.subrange(0, count.toLong()))
            var offset = 0
            while (offset < count) {
                val n = src.read(parallel, offset, count - offset)
                assertTrue(n > 0)
                offset += n
            }
        } finally { src.close() }
        val parallelMs = (System.nanoTime() - parallelStart) / 1000000
        android.util.Log.i("ClipRangeTest", "2 MiB, 15 ms per request: serial=${serialMs}ms parallel=${parallelMs}ms")
        assertArrayEquals(serial, parallel)
    }

    @Test fun seekAndFallbackReturnExactBytes() {
        for (reject in listOf(false, true)) {
            val source = RangeDownloadDataSource(factory(reject))
            for (start in listOf(2600000L, 34567L)) {
                val size = 1300000
                val actual = ByteArray(size)
                try {
                    source.open(spec.subrange(start, size.toLong()))
                    var done = 0
                    while (done < size) {
                        val read = source.read(actual, done, minOf(size - done, 65536))
                        assertTrue(read > 0)
                        done += read
                    }
                    assertArrayEquals(bytes.copyOfRange(start.toInt(), start.toInt() + size), actual)
                    assertEquals(C.RESULT_END_OF_INPUT, source.read(actual, 0, 1))
                } finally { source.close() }
                assertEquals(0, workers.get())
            }
        }
        assertTrue(peak.get() in 2..16)
    }
}
