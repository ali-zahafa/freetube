package com.tensei.freetube

import org.junit.Assert.*
import org.junit.Test

class ParallelDownloadTest {
    @Test fun rangeBatchesResumeWithoutGapsOrDuplicates() {
        val source = ByteArray(550001) { (it * 31).toByte() }
        val output = java.io.ByteArrayOutputStream()
        output.write(source, 0, 1535)
        while (output.size() < source.size) {
            val ranges = DownloadRanges.plan(output.size().toLong(), (source.size - output.size()).toLong(), 16, 1700)
            assertTrue(ranges.size <= 16)
            for (range in ranges) {
                assertEquals(output.size().toLong(), range.first)
                output.write(source, range.first.toInt(), (range.last - range.first + 1).toInt())
            }
        }
        assertArrayEquals(source, output.toByteArray())
    }
}
