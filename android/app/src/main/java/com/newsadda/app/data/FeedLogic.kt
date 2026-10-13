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
            generatedAt = feed.generatedAt ?: cache.generatedAt,
            cursor = feed.cursor ?: cache.cursor,
            supportsSince = feed.supportsSince,
            localSeen = seen,
        ) to fresh
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

    /** Refresh is due when the chosen interval has passed since the last attempt. */
    fun refreshDue(lastAttemptMs: Long, intervalMin: Int, nowMs: Long): Boolean =
        lastAttemptMs <= 0 || nowMs - lastAttemptMs >= intervalMin * 60_000L - 1_000L

    /** "1.0.12" style version compare used only as a fallback to versionCode. */
    fun isNewer(remoteCode: Int, localCode: Int) = remoteCode > localCode
}
