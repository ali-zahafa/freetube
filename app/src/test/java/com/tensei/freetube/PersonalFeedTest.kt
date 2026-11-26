package com.tensei.freetube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersonalFeedTest {
    @Test fun combinesArabicSearchesAndViewingInterestsWithoutRepetition() {
        assertEquals(listOf("الفلك", "فضاء", "NASA"), PersonalFeed.topics(
            listOf(" الفلك ", "NASA", " "), listOf("فضاء", "nasa")))
        assertEquals(emptyList<String>(), PersonalFeed.topics(emptyList(), emptyList()))
    }

    @Test fun usesEachProvidersOwnContinuation() {
        assertNull(PersonalFeed.standardToken("local-interests:end"))
        assertEquals("anon_feed:2:start", PersonalFeed.standardToken("anon_feed:2:start"))
    }
}
