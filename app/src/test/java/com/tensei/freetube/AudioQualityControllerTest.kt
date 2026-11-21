package com.tensei.freetube

import org.junit.Assert.*
import org.junit.Test

class AudioQualityControllerTest {
    @Test fun ranksKnownRatesAndPreservesAutoOrdering() {
        val rates = listOf(-1, 64000, 128000, 160000)
        assertEquals(64000, rates.maxBy { AudioQuality.DATA_SAVER.rank(it, -1) })
        assertEquals(160000, rates.maxBy { AudioQuality.HIGH.rank(it, -1) })
        assertEquals(128000, AudioQuality.HIGH.rank(-1, 128000))
        assertEquals(-1, AudioQuality.AUTO.rank(-1, 128000))
    }
}
