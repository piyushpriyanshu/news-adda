package com.newsadda.app.data

import com.newsadda.app.data.model.Cache
import com.newsadda.app.data.model.Feed
import com.newsadda.app.data.model.Story
import java.time.Duration
import java.time.Instant

/** Pure functions (no Android types) so they can be unit-tested on the JVM. */
object FeedLogic {
    /** How long stories stay on the phone after they were last reported. */
    val KEEP: Duration = Duration.ofHours(72)

    fun instant(s: String?): Instant? = try {
        if (s.isNullOrBlank()) null else Instant.parse(s)
    } catch (e: Exception) {
        null
    }

    /**
     * Merge a feed (full snapshot or incremental) into the cache.
     * Stories are upserted by id; nothing is dropped just because a snapshot
     * no longer contains it, only by age. Returns the new cache and the ids
     * that this phone had never seen before.
     */
    fun merge(cache: Cache, feed: Feed, nowMs: Long): Pair<Cache, Set<String>> {
        val byId = LinkedHashMap<String, Story>()
        cache.stories.forEach { byId[it.id] = it }
        val fresh = mutableSetOf<String>()
        for (s in feed.stories) {
            if (!byId.containsKey(s.id) && !cache.localSeen.containsKey(s.id)) fresh += s.id
            byId[s.id] = s
        }
        val cutoff = Instant.ofEpochMilli(nowMs).minus(KEEP)
        val kept = byId.values.filter { st ->
            val t = instant(st.lastReportAt) ?: instant(st.sortAt) ?: return@filter true
            t.isAfter(cutoff)
        }.sortedByDescending { it.sortAt }
        val keptIds = kept.map { it.id }.toSet()
        val seen = cache.localSeen.filterKeys { it in keptIds }.toMutableMap()
        fresh.forEach { seen[it] = nowMs }
        return cache.copy(
            stories = kept,
            sources = feed.sources.ifEmpty { cache.sources },
            entities = if (feed.entities.districts.isEmpty()) cache.entities else feed.entities,
            categories = feed.categories.ifEmpty { cache.categories },
            xAvailable = feed.xAvailable,
            liveWire = feed.liveWire.ifEmpty { cache.liveWire },
            trustedKeys = feed.trustedKeys.ifEmpty { cache.trustedKeys },
            generatedAt = feed.generatedAt ?: cache.generatedAt,
            cursor = feed.cursor ?: cache.cursor,
            supportsSince = feed.supportsSince,
            localSeen = seen,
        ) to fresh
    }

    /** Same publisher key as the backend: "UP Tak", "UPTak", "www.uptak.in" -> "uptak". */
    fun publisherKey(name: String): String {
        var n = normalize(name.substringBefore('(')).trim()
        n = n.removePrefix("www.")
        n = n.replace(Regex("\\.(com|in|co\\.in|net|org)$"), "")
        return n.filter { it.isLetterOrDigit() || it in '\u0900'..'\u097F' }
    }

    /** Share of letters that are Devanagari; the app shows Hindi news only. */
    fun hindiShare(text: String): Double {
        val letters = text.filter { it.isLetter() || it in '\u0900'..'\u097F' }
        if (letters.isEmpty()) return 0.0
        return letters.count { it in '\u0900'..'\u097F' }.toDouble() / letters.length
    }

    /** Fold nukta/chandrabindu and case so "आज़ाद" finds "आजाद". */
    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                '़', '​', '‌', '‍' -> {}
                'ँ' -> sb.append('ं')
                'क़' -> sb.append('क')
                'ख़' -> sb.append('ख')
                'ग़' -> sb.append('ग')
                'ज़' -> sb.append('ज')
                'ड़' -> sb.append('ड')
                'ढ़' -> sb.append('ढ')
                'फ़' -> sb.append('फ')
                'य़' -> sb.append('य')
                else -> sb.append(ch.lowercaseChar())
            }
        }
        return sb.toString()
    }

    fun matchesQuery(story: Story, query: String, extra: String = ""): Boolean {
        val words = normalize(query).split(' ', ',', '।').filter { it.isNotBlank() }
        if (words.isEmpty()) return true
        val hay = normalize(buildString {
            append(extra).append(' ')
            append(story.title).append(' ')
            append(story.summary ?: "").append(' ')
            story.reports.forEach { append(it.title).append(' ').append(it.sourceName).append(' ').append(it.publisher ?: "").append(' ') }
        })
        return words.all { hay.contains(it) }
    }

    fun isBreakingNow(story: Story, now: Instant, hours: Long = 6): Boolean {
        if (!story.breaking) return false
        val seen = instant(story.firstSeenAt) ?: return false
        return Duration.between(seen, now).toHours() < hours
    }

    fun isRecent(story: Story, now: Instant, minutes: Long = 30): Boolean {
        val seen = instant(story.firstSeenAt) ?: return false
        return Duration.between(seen, now).toMinutes() < minutes
    }

    /** A story is hidden when every report comes from a source the user switched off. */
    fun visible(story: Story, hiddenSources: Set<String>): Boolean =
        hiddenSources.isEmpty() || story.reports.any { it.sourceId !in hiddenSources }

    fun isControversy(s: Story) =
        "controversy" in s.categories || "allegation" in s.labels || "counter" in s.labels

    fun isSeatsAndFigures(s: Story) =
        s.entities.figures.isNotEmpty() || s.entities.seats.isNotEmpty() || "election" in s.categories

    fun isPolitical(s: Story) =
        s.entities.leaders.isNotEmpty() || s.entities.figures.isNotEmpty() || s.entities.parties.isNotEmpty() ||
            s.categories.any { it == "politics" || it == "election" || it == "controversy" }

    /** Words that make a raw headline (Live wire) a UP-politics item. */
    private val POLITICAL_WORDS = listOf(
        "भाजपा", "बीजेपी", "BJP", "सपा", "बसपा", "कांग्रेस", "रालोद", "सुभासपा", "अपना दल", "निषाद पार्टी",
        "विधायक", "सांसद", "मंत्री", "मुख्यमंत्री", "उपमुख्यमंत्री", "सीएम", "CM", "डिप्टी सीएम", "राज्यमंत्री",
        "विधानसभा", "विधान परिषद", "एमएलसी", "चुनाव", "उपचुनाव", "प्रत्याशी", "टिकट", "पार्टी", "नेता", "जिलाध्यक्ष",
        "प्रदेश अध्यक्ष", "इस्तीफा", "पलटवार", "निशाना", "तंज", "आरोप", "बयान", "सियासत", "सियासी", "राजनीति",
        "राजनीतिक", "सरकार", "योगी", "अखिलेश", "मायावती", "केशव", "ब्रजेश पाठक", "राजभर", "शिवपाल", "डिंपल",
        "आजम", "अनुप्रिया", "जयंत", "चंद्रशेखर", "आकाश आनंद", "राहुल", "प्रियंका", "बाहुबली", "माफिया",
        "पूर्व सांसद", "पूर्व विधायक", "दलबदल",
    ).map { " " + normalize(it) + " " }
    private val NON_WORD = Regex("[^\\p{L}\\p{M}\\p{N}]+")

    /** True when a headline names a party, leader or political action (or one of [names]). */
    fun isPoliticalHeadline(title: String, names: Collection<String> = emptyList()): Boolean {
        val t = " " + normalize(title).replace(NON_WORD, " ") + " "
        return POLITICAL_WORDS.any { t.contains(it) } ||
            names.any { n -> n.length > 2 && t.contains(" " + normalize(n).replace(NON_WORD, " ").trim() + " ") }
    }

    private val STOP = setOf(
        "का", "की", "के", "को", "में", "से", "पर", "ने", "और", "है", "हैं", "था", "थी", "थे", "भी", "तो", "ही",
        "एक", "दो", "यह", "वह", "इस", "उस", "लिए", "साथ", "बाद", "अब", "कर", "करें", "किया", "गया", "गई", "हुआ",
        "हुई", "रहा", "रही", "रहे", "बोले", "कहा", "news", "up", "the", "a", "of", "to", "in", "and",
    )

    /** Content words of a headline (for spotting the same news told twice). */
    fun terms(title: String): Set<String> =
        normalize(title).replace(NON_WORD, " ").split(' ').filter { it.length > 1 && it !in STOP }.toSet()

    /** True when two headlines report the same news (most content words shared). */
    fun sameNews(a: Set<String>, b: Set<String>): Boolean {
        if (a.size < 3 || b.size < 3) return false
        val inter = a.count { it in b }
        return inter.toDouble() / (a.size + b.size - inter) >= 0.5
    }

    /** Hindi headline without an English SEO tail ("… - cm yogi to lay foundation"). */
    fun cleanTitle(title: String): String {
        val m = Regex("\\s+[-|–—]\\s+([^\\u0900-\\u097F]{12,})$").find(title) ?: return title
        val head = title.substring(0, m.range.first)
        return if (hindiShare(head) >= 0.6 && Regex("[A-Za-z]{3}").containsMatchIn(m.groupValues[1])) head.trimEnd() else title
    }

    /** Refresh is due when the chosen interval has passed since the last attempt. */
    fun refreshDue(lastAttemptMs: Long, intervalMin: Int, nowMs: Long): Boolean =
        lastAttemptMs <= 0 || nowMs - lastAttemptMs >= intervalMin * 60_000L - 1_000L

    /**
     * Which URL to fetch. A static feed (raw.githubusercontent.com etc.) has a
     * small sibling delta.json with only the last 45 minutes of changes; use it
     * when the cache is fresh enough, and the full feed otherwise. GitHub's CDN
     * caches files for up to 5 minutes, so add a per-minute cache-buster there.
     */
    fun feedUrl(base: String, useDelta: Boolean, nowMs: Long): String {
        var url = base.trim()
        if (useDelta && url.substringBefore('?').endsWith("/feed.json")) {
            url = url.substringBefore('?').removeSuffix("feed.json") + "delta.json"
        }
        if (url.contains("raw.githubusercontent.com")) {
            url += (if ('?' in url) "&" else "?") + "t=" + (nowMs / 60_000)
        }
        return url
    }

    /** Delta is safe only if the last full sync is recent enough to leave no gap. */
    fun canUseDelta(cache: Cache, lastFullMs: Long, nowMs: Long): Boolean =
        cache.stories.isNotEmpty() && !cache.supportsSince && lastFullMs > 0 && nowMs - lastFullMs < 40 * 60_000L

    /** "1.0.12" style version compare used only as a fallback to versionCode. */
    fun isNewer(remoteCode: Int, localCode: Int) = remoteCode > localCode
}
