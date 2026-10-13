package com.newsadda.app

import com.newsadda.app.data.FeedLogic
import com.newsadda.app.data.LiveWire
import com.newsadda.app.data.model.Cache
import com.newsadda.app.data.model.LiveFeed
import com.newsadda.app.data.model.Report
import com.newsadda.app.data.model.Story
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Synthetic RSS (test data only) in the shapes real publisher and Google News feeds use. */
class LiveWireTest {
    private val now = Instant.parse("2026-10-13T08:00:00Z").toEpochMilli()

    private val publisherRss = """<?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel><title>t</title>
        <item><title><![CDATA[आजमगढ़ में सपा विधायक ने दिया इस्तीफा]]></title><link>https://pub.test/a1</link>
          <pubDate>Tue, 13 Oct 2026 13:20:00 +0530</pubDate></item>
        <item><title>लखनऊ में बारिश</title><link>https://pub.test/a2</link></item>
        </channel></rss>""".trimIndent().toByteArray()

    private val googleRss = """<?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel><title>g</title>
        <item><title>बलिया में भाजपा नेता गिरफ्तार - Example Times</title><link>https://news.google.com/rss/articles/x1</link>
          <pubDate>Tue, 13 Oct 2026 07:55:00 GMT</pubDate><source url="https://www.exampletimes.in">Example Times</source></item>
        </channel></rss>""".trimIndent().toByteArray()

    @Test
    fun parsesPublisherFeedWithIstTimeAndBreakingHint() {
        val items = LiveWire.parse(publisherRss, LiveFeed("p", "Pub Test", "पब", "pub.test", "https://pub.test/rss"), now)
        assertEquals(2, items.size)
        assertEquals("2026-10-13T07:50:00Z", items[0].publishedAt)
        assertTrue(items[0].breakingHint) // इस्तीफा
        assertNull(items[1].publishedAt) // no invented time
        assertFalse(items[1].breakingHint)
        assertEquals("pub.test", items[0].domain)
    }

    @Test
    fun googleNewsKeepsOriginalPublisherAndDomain() {
        val items = LiveWire.parse(googleRss, LiveFeed("g", "Google", "", "news.google.com", "https://news.google.com/rss/search?q=x"), now)
        assertEquals("बलिया में भाजपा नेता गिरफ्तार", items[0].title)
        assertEquals("Example Times", items[0].publisher)
        assertEquals("exampletimes.in", items[0].domain)
        assertTrue(items[0].breakingHint)
    }

    @Test
    fun combineDropsKnownAndOldHeadlines() {
        val items = LiveWire.parse(publisherRss, LiveFeed("p", "Pub", "", "pub.test", "https://pub.test/rss"), now)
        val known = Story(id = "s", title = "x", sortAt = "2026-10-13T07:00:00Z", firstSeenAt = "2026-10-13T07:00:00Z",
            reports = listOf(Report(id = "r", sourceId = "a", sourceName = "A", url = "https://a.test",
                title = "आजमगढ़ में सपा विधायक ने दिया इस्तीफा", firstSeenAt = "2026-10-13T07:00:00Z")))
        val out = LiveWire.combine(items, listOf(known), now)
        assertEquals(listOf("लखनऊ में बारिश"), out.map { it.title })
        assertTrue(LiveWire.combine(items, emptyList(), now + 4 * 3_600_000L).none { it.publishedAt != null })
    }

    @Test
    fun feedUrlUsesDeltaAndCacheBusterOnGithub() {
        val base = "https://raw.githubusercontent.com/o/r/data/feed.json"
        assertEquals("https://raw.githubusercontent.com/o/r/data/delta.json?t=10", FeedLogic.feedUrl(base, true, 600_000))
        assertEquals("https://raw.githubusercontent.com/o/r/data/feed.json?t=10", FeedLogic.feedUrl(base, false, 600_000))
        assertEquals("https://my.server/feed.json", FeedLogic.feedUrl("https://my.server/feed.json", false, 600_000))
        val withStories = Cache(stories = listOf(Story(id = "a", title = "t", sortAt = "2026-10-13T07:00:00Z", firstSeenAt = "2026-10-13T07:00:00Z")))
        assertTrue(FeedLogic.canUseDelta(withStories, now - 10 * 60_000, now))
        assertFalse(FeedLogic.canUseDelta(withStories, now - 50 * 60_000, now))
        assertFalse(FeedLogic.canUseDelta(Cache(), now - 60_000, now))
    }

    @Test
    fun liveWireKeepsOnlyHindiAndTrustedGooglePublishers() {
        val items = LiveWire.parse(googleRss, LiveFeed("g", "Google", "", "news.google.com", "https://news.google.com/rss/search?q=x"), now)
        assertTrue(LiveWire.credible(items, setOf("exampletimes")).size == 1)
        assertTrue(LiveWire.credible(items, setOf("othertimes")).isEmpty())
        assertEquals("uptak", FeedLogic.publisherKey("UP Tak"))
        assertEquals("uptak", FeedLogic.publisherKey("www.uptak.in"))
        assertEquals(FeedLogic.publisherKey("अमर उजाला"), FeedLogic.publisherKey("अमर  उजाला"))
        assertTrue(FeedLogic.hindiShare("अखिलेश यादव का बयान") > 0.9)
        assertTrue(FeedLogic.hindiShare("Akhilesh Yadav statement") < 0.1)
    }

    @Test
    fun sameNewsFromTwoPublishersIsShownOnceAndEnglishTailIsDropped() {
        fun item(id: String, title: String, pub: String, at: String) = com.newsadda.app.data.model.LiveItem(
            id = id, title = title, url = "https://x.test/$id", publisher = pub, publishedAt = at, seenAt = now)
        val a = item("a", "पूर्व सांसद सुब्रत पाठक की एस्कॉर्ट गाड़ी पलटी, हादसे में सिपाही की मौत, दो घायल", "India TV", "2026-10-13T07:30:00Z")
        val b = item("b", "BJP के पूर्व सांसद सुब्रत पाठक के काफिले की गाड़ी पलटी, हादसे में सिपाही शहीद", "ABP News", "2026-10-13T07:10:00Z")
        val c = item("c", "लखनऊ में सपा का प्रदर्शन, कार्यकर्ताओं ने घेरा कार्यालय", "TV9", "2026-10-13T07:40:00Z")
        val out = LiveWire.combine(listOf(a, b, c), emptyList(), now)
        assertEquals(listOf("c", "b"), out.map { it.id })  // the first to report is kept
        assertEquals("मुख्यमंत्री योगी 15 अक्टूबर को प्रयागराज आएंगे",
            FeedLogic.cleanTitle("मुख्यमंत्री योगी 15 अक्टूबर को प्रयागराज आएंगे - cm yogi adityanath to lay foundation"))
        assertEquals("यूपी में भाजपा - सपा आमने-सामने", FeedLogic.cleanTitle("यूपी में भाजपा - सपा आमने-सामने"))
    }
}
