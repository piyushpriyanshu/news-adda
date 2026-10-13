package com.newsadda.app.data

import android.content.Context
import android.util.Log
import com.newsadda.app.data.model.LiveFeed
import com.newsadda.app.data.model.LiveItem
import com.newsadda.app.data.model.Story
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory

/**
 * "Live wire": the phone itself reads a handful of the fastest public RSS feeds
 * (listed by the backend) on every refresh, so a headline can appear within
 * minutes of publication even when the backend's next run is still pending.
 * These items are shown as-is: not grouped, not labelled, clearly marked.
 */
object LiveWire {
    private const val TAG = "LiveWire"
    private const val KEEP_MS = 3 * 60 * 60_000L

    private val _items = MutableStateFlow<List<LiveItem>>(emptyList())
    val items: StateFlow<List<LiveItem>> = _items
    private val etags = ConcurrentHashMap<String, String>()
    private val seen = ConcurrentHashMap<String, Long>()
    private val cached = ConcurrentHashMap<String, List<LiveItem>>()

    /** Breaking-looking words in a headline (subset of the backend's rules). */
    private val BREAKING_HINTS = listOf(
        "ब्रेकिंग", "बड़ी खबर", "इस्तीफा", "इस्तीफे", "गिरफ्तार", "निलंबित", "बर्खास्त", "दलबदल", "टिकट",
        "चुनाव आयोग", "उपचुनाव", "हाईकोर्ट", "हाई कोर्ट", "सुप्रीम कोर्ट", "छापा", "छापेमारी", "निधन",
    ).map { FeedLogic.normalize(it) }

    suspend fun refresh(feeds: List<LiveFeed>, stories: List<Story>, nowMs: Long = System.currentTimeMillis(),
                        trustedKeys: Collection<String> = emptyList()) {
        if (feeds.isEmpty()) return
        val fresh = coroutineScope {
            feeds.map { f -> async(Dispatchers.IO) { runCatching { fetch(f, nowMs) }.getOrElse { cached[f.id].orEmpty() } } }.awaitAll()
        }.flatten()
        _items.value = combine(credible(fresh, trustedKeys.toSet()), stories, nowMs)
    }

    /**
     * Same credibility rules as the backend: Hindi headlines only, and Google
     * News items only from established outlets (publisher keys sent by the backend).
     */
    fun credible(items: List<LiveItem>, trusted: Set<String>): List<LiveItem> = items.filter { i ->
        FeedLogic.hindiShare(i.title) >= 0.5 &&
            (!i.viaGoogle || trusted.isEmpty() || FeedLogic.publisherKey(i.publisher) in trusted)
    }

    private fun fetch(f: LiveFeed, nowMs: Long): List<LiveItem> {
        val req = Request.Builder().url(f.url)
            .header("Accept", "application/rss+xml, application/xml;q=0.9, */*;q=0.5")
            .apply { etags[f.url]?.let { header("If-None-Match", it) } }
            .build()
        Net.client.newCall(req).execute().use { r ->
            if (r.code == 304) return cached[f.id].orEmpty()
            if (!r.isSuccessful) {
                Log.w(TAG, "${f.id}: HTTP ${r.code}")
                return cached[f.id].orEmpty()
            }
            r.header("ETag")?.let { etags[f.url] = it }
            val items = parse(r.body!!.bytes(), f, nowMs, seen)
            cached[f.id] = items
            return items
        }
    }

    /** Newest first, last 3 hours, one entry per headline, minus stories the backend already has. */
    fun combine(items: List<LiveItem>, stories: List<Story>, nowMs: Long): List<LiveItem> {
        val known = HashSet<String>()
        stories.forEach { s -> s.reports.forEach { known += key(it.title) } }
        val out = LinkedHashMap<String, LiveItem>()
        items.sortedByDescending { it.publishedAt ?: "" }.forEach { i ->
            val k = key(i.title)
            val t = FeedLogic.instant(i.publishedAt)?.toEpochMilli() ?: i.seenAt
            if (k !in known && k !in out && nowMs - t < KEEP_MS) out[k] = i
        }
        return out.values.take(60)
    }

    private fun key(title: String) = FeedLogic.normalize(title).filter { it.isLetterOrDigit() }

    fun parse(bytes: ByteArray, feed: LiveFeed, nowMs: Long, seenMap: MutableMap<String, Long> = HashMap()): List<LiveItem> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { isExpandEntityReferences = false }
        }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        val nodes = doc.getElementsByTagName("item")
        val google = feed.url.contains("news.google.com")
        val out = ArrayList<LiveItem>()
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            var title = text(e, "title") ?: continue
            val link = text(e, "link") ?: continue
            var publisher = feed.name.ifBlank { feed.id }
            var domain = feed.domain
            if (google) {
                val src = e.getElementsByTagName("source").item(0) as? Element
                val name = src?.textContent?.trim()
                if (!name.isNullOrBlank()) {
                    publisher = name
                    if (title.endsWith(" - $name")) title = title.removeSuffix(" - $name").trim()
                }
                src?.getAttribute("url")?.takeIf { it.isNotBlank() }?.let { domain = siteDomain(it) }
            }
            val id = sha(link)
            val seenAt = seenMap.getOrPut(id) { nowMs }
            val norm = FeedLogic.normalize(title)
            out += LiveItem(
                id = id, title = title, url = link, publisher = publisher, domain = domain, viaGoogle = google,
                publishedAt = parseDate(text(e, "pubDate") ?: text(e, "dc:date")),
                seenAt = seenAt, breakingHint = BREAKING_HINTS.any { norm.contains(it) },
            )
        }
        return out
    }

    private fun text(e: Element, tag: String): String? =
        e.getElementsByTagName(tag).item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
            ?.replace(Regex("<[^>]+>"), "")?.trim()

    fun siteDomain(url: String): String? = runCatching {
        var h = java.net.URI(url.trim()).host?.lowercase() ?: return null
        for (p in listOf("www.", "api.", "feeds.", "rss.", "m.")) if (h.startsWith(p)) h = h.removePrefix(p)
        h
    }.getOrNull()

    /** RFC 1123 ("Tue, 13 Oct 2026 07:35:00 GMT" / "+0530") or ISO-8601; null if absent or unreadable. */
    fun parseDate(s: String?): String? {
        if (s.isNullOrBlank()) return null
        val v = s.trim()
        val instant = runCatching { ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(v).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(v) }.getOrNull()
        return instant?.toString()
    }

    private fun sha(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    @Suppress("unused")
    fun clear(ctx: Context) {
        _items.value = emptyList(); cached.clear(); etags.clear()
    }
}
