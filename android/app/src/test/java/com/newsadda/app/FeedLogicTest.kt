package com.newsadda.app

import com.newsadda.app.data.FeedLogic
import com.newsadda.app.data.Net
import com.newsadda.app.data.TimeFmt
import com.newsadda.app.data.model.Cache
import com.newsadda.app.data.model.Feed
import com.newsadda.app.data.model.Story
import com.newsadda.app.ui.Str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FeedLogicTest {
    // Synthetic feed produced by the backend's own test pipeline: keeps app and backend formats in sync.
    private val sample: Feed by lazy {
        val text = javaClass.classLoader!!.getResource("sample_feed.json")!!.readText()
        Net.json.decodeFromString(Feed.serializer(), text)
    }

    private fun story(id: String, sortAt: String, firstSeen: String = sortAt, breaking: Boolean = false) =
        Story(id = id, title = "शीर्षक $id", sortAt = sortAt, firstSeenAt = firstSeen, breaking = breaking)

    @Test
    fun parsesBackendFeedFormat() {
        assertEquals(1, sample.version)
        assertTrue(sample.stories.isNotEmpty())
        val resign = sample.stories.first { it.title.contains("इस्तीफा") }
        assertEquals("multi", resign.corroboration)
        assertTrue(resign.reports.size >= 2)
        assertTrue(resign.reports.all { it.url.startsWith("https://") && it.sourceName.isNotBlank() })
        val headlineOnly = sample.stories.first { it.headlineOnly }
        assertNull(headlineOnly.reports.first().publishedAt) // no invented time
        assertEquals(75, sample.entities.districts.size)
        assertTrue(sample.sources.any { it.status == "error" })
    }

    @Test
    fun mergeUpsertsAndFlagsOnlyUnseenStories() {
        val now = Instant.parse("2026-10-13T08:00:00Z").toEpochMilli()
        val first = Feed(stories = listOf(story("a", "2026-10-13T07:00:00Z"), story("b", "2026-10-13T07:30:00Z")))
        val (c1, fresh1) = FeedLogic.merge(Cache(), first, now)
        assertEquals(setOf("a", "b"), fresh1)
        assertEquals(listOf("b", "a"), c1.stories.map { it.id }) // newest first

        val updatedB = story("b", "2026-10-13T07:30:00Z").copy(title = "बदला शीर्षक")
        val second = Feed(stories = listOf(updatedB, story("c", "2026-10-13T07:50:00Z")))
        val (c2, fresh2) = FeedLogic.merge(c1, second, now + 60_000)
        assertEquals(setOf("c"), fresh2)
        assertEquals(listOf("c", "b", "a"), c2.stories.map { it.id }) // "a" kept though absent from snapshot
        assertEquals("बदला शीर्षक", c2.stories.first { it.id == "b" }.title)
    }

    @Test
    fun mergeDropsStoriesOlderThanRetention() {
        val now = Instant.parse("2026-10-13T08:00:00Z").toEpochMilli()
        val feed = Feed(stories = listOf(story("old", "2026-10-09T07:00:00Z"), story("new", "2026-10-13T07:00:00Z")))
        val (c, _) = FeedLogic.merge(Cache(), feed, now)
        assertEquals(listOf("new"), c.stories.map { it.id })
    }

    @Test
    fun mergeKeepsCachedSourcesWhenFeedOmitsThem() {
        val at = Instant.parse(sample.generatedAt!!).toEpochMilli()
        val (c1, _) = FeedLogic.merge(Cache(), sample, at)
        assertEquals(sample.stories.size, c1.stories.size)
        val (c2, _) = FeedLogic.merge(c1, Feed(stories = emptyList()), at + 60_000)
        assertEquals(c1.sources, c2.sources)
        assertEquals(75, c2.entities.districts.size)
    }

    @Test
    fun searchFoldsNuktaAndCase() {
        val s = story("x", "2026-10-13T07:00:00Z").copy(title = "चंद्रशेखर आज़ाद का बयान", summary = "Bhim Army")
        assertTrue(FeedLogic.matchesQuery(s, "आजाद"))
        assertTrue(FeedLogic.matchesQuery(s, "bhim army"))
        assertFalse(FeedLogic.matchesQuery(s, "मायावती"))
    }

    @Test
    fun breakingLabelExpiresAndRecentWindow() {
        val now = Instant.parse("2026-10-13T08:00:00Z")
        val fresh = story("f", "2026-10-13T07:50:00Z", breaking = true)
        val old = story("o", "2026-10-13T00:00:00Z", breaking = true)
        assertTrue(FeedLogic.isBreakingNow(fresh, now))
        assertFalse(FeedLogic.isBreakingNow(old, now))
        assertTrue(FeedLogic.isRecent(fresh, now))
        assertFalse(FeedLogic.isRecent(old, now))
    }

    @Test
    fun refreshIntervalDecision() {
        val t0 = 1_000_000L
        assertTrue(FeedLogic.refreshDue(0, 5, t0))
        assertFalse(FeedLogic.refreshDue(t0, 5, t0 + 4 * 60_000))
        assertTrue(FeedLogic.refreshDue(t0, 5, t0 + 5 * 60_000))
        assertTrue(FeedLogic.refreshDue(t0, 1, t0 + 60_000))
    }

    @Test
    fun hiddenSourcesHideOnlyFullyHiddenStories() {
        val resign = sample.stories.first { it.title.contains("इस्तीफा") }
        val ids = resign.reports.map { it.sourceId }.toSet()
        assertTrue(FeedLogic.visible(resign, setOf(ids.first())))
        assertFalse(FeedLogic.visible(resign, ids))
    }

    @Test
    fun timesRenderInIstInBothLanguages() {
        val en = Str(false)
        val hi = Str(true)
        val now = Instant.parse("2026-10-13T10:00:00Z") // 15:30 IST
        assertEquals("13:05", TimeFmt.short(Instant.parse("2026-10-13T07:35:00Z"), now, en))
        assertEquals("Yesterday 21:10", TimeFmt.short(Instant.parse("2026-10-12T15:40:00Z"), now, en))
        assertEquals("कल 21:10", TimeFmt.short(Instant.parse("2026-10-12T15:40:00Z"), now, hi))
        assertEquals("10 Oct 09:00", TimeFmt.short(Instant.parse("2026-10-10T03:30:00Z"), now, en))
        assertEquals("10 अक्टू 09:00", TimeFmt.short(Instant.parse("2026-10-10T03:30:00Z"), now, hi))
        assertEquals("13 Oct 2026, 13:05 IST", TimeFmt.full(Instant.parse("2026-10-13T07:35:00Z"), en))
        assertEquals("25 min ago", TimeFmt.ago(Instant.parse("2026-10-13T09:35:00Z"), now, en))
        assertEquals("25 मिनट पहले", TimeFmt.ago(Instant.parse("2026-10-13T09:35:00Z"), now, hi))
        // Late UTC evening is already the next day in IST.
        assertEquals("02:00", TimeFmt.short(Instant.parse("2026-10-12T20:30:00Z"), Instant.parse("2026-10-12T21:00:00Z"), en))
    }

    @Test
    fun labelsAndReasonsLocalise() {
        val en = Str(false)
        val hi = Str(true)
        val resign = sample.stories.first { it.title.contains("इस्तीफा") }
        assertTrue("multi_source" in resign.labels)
        assertTrue("resignation" in resign.reasons)
        assertEquals("Resignation", en.reason("resignation") { it })
        assertEquals("इस्तीफा", hi.reason("resignation") { it })
        assertEquals("Leader: Yogi", en.reason("leaders:yogi") { "Yogi" })
        assertEquals("Allegation", en.label("allegation"))
        assertEquals("पुष्टि की प्रतीक्षा", hi.label("awaiting_confirmation"))
        assertEquals("FIR", en.legal("fir"))
        assertEquals("Politics", en.category("politics"))
        // Every district has an English and a Hindi name.
        assertTrue(sample.entities.districts.all { !it.name.isNullOrBlank() && it.nameHi.isNotBlank() })
    }

    @Test
    fun englishSearchFindsHindiStoryThroughEntityNames() {
        val s = story("y", "2026-10-13T07:00:00Z").copy(title = "लखनऊ में बैठक")
        assertFalse(FeedLogic.matchesQuery(s, "lucknow"))
        assertTrue(FeedLogic.matchesQuery(s, "lucknow", "Lucknow लखनऊ"))
    }

    @Test
    fun liveWireHeadlinesArePoliticalOnlyWhenTheyNameParties() {
        assertTrue(FeedLogic.isPoliticalHeadline("सपा विधायक ने भाजपा पर साधा निशाना"))
        assertTrue(FeedLogic.isPoliticalHeadline("दंगा किया तो कमाई जब्त, उपद्रवियों को CM योगी की चेतावनी"))
        assertTrue(FeedLogic.isPoliticalHeadline("राजा भैया का बड़ा बयान", listOf("राजा भैया")))
        assertFalse(FeedLogic.isPoliticalHeadline("चेहरा तो चमक रहा मगर गर्दन पर जम गए हैं मैल? एक्सपर्ट ने दिया घरेलू उपाय"))
        assertFalse(FeedLogic.isPoliticalHeadline("सहयोगी कंपनी ने खोला नया शोरूम"))  // whole words only
    }
}
