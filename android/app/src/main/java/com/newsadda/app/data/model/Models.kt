package com.newsadda.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mirrors backend/newsadda/export.py. Unknown fields are ignored for forward compatibility. */
@Serializable
data class Feed(
    val version: Int = 1,
    @SerialName("generated_at") val generatedAt: String? = null,
    val cursor: String? = null,
    val incremental: Boolean = false,
    @SerialName("supports_since") val supportsSince: Boolean = false,
    @SerialName("window_hours") val windowHours: Int = 36,
    @SerialName("x_available") val xAvailable: Boolean = false,
    @SerialName("ai_summaries") val aiSummaries: Boolean = false,
    @SerialName("live_wire") val liveWire: List<LiveFeed> = emptyList(),
    @SerialName("delta_since") val deltaSince: String? = null,
    val sources: List<SourceStatus> = emptyList(),
    val entities: Entities = Entities(),
    val categories: Map<String, String> = emptyMap(),
    val stories: List<Story> = emptyList(),
)

@Serializable
data class Story(
    val id: String,
    val title: String,
    val summary: String? = null,
    @SerialName("summary_origin") val summaryOrigin: String? = null,
    @SerialName("headline_only") val headlineOnly: Boolean = false,
    val kind: String = "article",
    @SerialName("sort_at") val sortAt: String,
    @SerialName("first_seen_at") val firstSeenAt: String,
    @SerialName("last_report_at") val lastReportAt: String? = null,
    @SerialName("changed_at") val changedAt: String? = null,
    val categories: List<String> = emptyList(),
    val entities: StoryEntities = StoryEntities(),
    val breaking: Boolean = false,
    val reasons: List<String> = emptyList(),
    val labels: List<String> = emptyList(),
    @SerialName("legal_status") val legalStatus: List<String> = emptyList(),
    val statement: Boolean = false,
    @SerialName("statement_type") val statementType: String? = null,
    val corroboration: String = "single",
    @SerialName("publisher_count") val publisherCount: Int = 1,
    val reports: List<Report> = emptyList(),
)

@Serializable
data class StoryEntities(
    val leaders: List<String> = emptyList(),
    val figures: List<String> = emptyList(),
    val parties: List<String> = emptyList(),
    val districts: List<String> = emptyList(),
    val seats: List<String> = emptyList(),
)

@Serializable
data class Report(
    val id: String,
    @SerialName("source_id") val sourceId: String,
    @SerialName("source_name") val sourceName: String,
    val publisher: String? = null,
    val via: String? = null,
    val domain: String? = null,
    val kind: String = "article",
    val url: String,
    val title: String,
    val summary: String? = null,
    @SerialName("summary_origin") val summaryOrigin: String? = null,
    val author: String? = null,
    val handle: String? = null,
    @SerialName("post_context") val postContext: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("published_raw") val publishedRaw: String? = null,
    @SerialName("tz_assumed") val tzAssumed: Boolean = false,
    @SerialName("published_suspect") val publishedSuspect: Boolean = false,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("first_seen_at") val firstSeenAt: String,
)

@Serializable
data class SourceStatus(
    val id: String,
    val name: String = "",
    @SerialName("name_hi") val nameHi: String = "",
    val kind: String = "rss",
    val via: String? = null,
    val enabled: Boolean = true,
    val status: String = "pending",
    @SerialName("status_text") val statusText: String = "",
    @SerialName("last_ok_at") val lastOkAt: String? = null,
    @SerialName("last_attempt_at") val lastAttemptAt: String? = null,
    val error: String? = null,
    @SerialName("error_text") val errorText: String? = null,
    @SerialName("last_item_count") val lastItemCount: Int = 0,
    @SerialName("total_items") val totalItems: Int = 0,
    val domain: String? = null,
)

/** A public RSS feed the phone may poll itself for the fastest headlines. */
@Serializable
data class LiveFeed(
    val id: String,
    val name: String = "",
    @SerialName("name_hi") val nameHi: String = "",
    val domain: String? = null,
    val url: String,
)

/** A headline read straight from a publisher feed by the phone (not grouped or labelled by the backend). */
@Serializable
data class LiveItem(
    val id: String,
    val title: String,
    val url: String,
    val publisher: String,
    val domain: String? = null,
    val publishedAt: String? = null,
    val seenAt: Long,
    val breakingHint: Boolean = false,
)

@Serializable
data class Entities(
    val leaders: List<Entity> = emptyList(),
    val figures: List<Entity> = emptyList(),
    val parties: List<Entity> = emptyList(),
    val districts: List<Entity> = emptyList(),
    val seats: List<Entity> = emptyList(),
)

@Serializable
data class Entity(
    val id: String,
    @SerialName("name_hi") val nameHi: String,
    val name: String? = null,
    val party: String? = null,
    @SerialName("role_hi") val roleHi: String? = null,
    @SerialName("area_hi") val areaHi: String? = null,
    val key: Boolean = false,
)

/** What the app keeps on disk between launches. */
@Serializable
data class Cache(
    val stories: List<Story> = emptyList(),
    val sources: List<SourceStatus> = emptyList(),
    val entities: Entities = Entities(),
    val categories: Map<String, String> = emptyMap(),
    val xAvailable: Boolean = false,
    val liveWire: List<LiveFeed> = emptyList(),
    val generatedAt: String? = null,
    val cursor: String? = null,
    val supportsSince: Boolean = false,
    /** story id -> epoch ms when this phone first received it */
    val localSeen: Map<String, Long> = emptyMap(),
)

@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String? = null,
    val publishedAt: String? = null,
    val size: Long? = null,
)
