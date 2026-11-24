package com.tensei.freetube

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchHistoryPolicyTest {
    @Test fun retentionUsesRollingDaysWithNinetyAsDefault() {
        val now = 2_678_400_000L
        assertEquals(90, WatchHistoryPolicy.defaultRetentionDays)
        assertEquals(listOf(7, 14, 30, 90), WatchHistoryPolicy.retentionOptions)
        assertEquals(2_073_600_000L, WatchHistoryPolicy.cutoff(now, 7))
        assertEquals(1_468_800_000L, WatchHistoryPolicy.cutoff(now, 14))
        assertEquals(86_400_000L, WatchHistoryPolicy.cutoff(now, 30))
    }

    @Test fun thumbnailPercentageTracksPlaybackPosition() {
        assertEquals(50, WatchHistoryPolicy.percent(30000, 60000))
        assertEquals(100, WatchHistoryPolicy.percent(60000, 60000))
        assertEquals(null, WatchHistoryPolicy.percent(30000, -1))
    }

    @Test fun completedAndLiveVideosRestartWhileOtherVideosResume() {
        assertEquals(0L, WatchHistoryPolicy.resumePosition(58_000, 60_000, live = false))
        assertEquals(0L, WatchHistoryPolicy.resumePosition(25_000, 60_000, live = true))
        assertEquals(25_000L, WatchHistoryPolicy.resumePosition(25_000, 60_000, live = false))
        assertEquals(0L, WatchHistoryPolicy.resumePosition(-1, 0, live = false))
    }
}
