package com.newsadda.app.data

import android.content.Context
import android.util.Log
import com.newsadda.app.data.model.Cache
import com.newsadda.app.data.model.Story
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

data class RepoState(
    val cache: Cache = Cache(),
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val lastAttemptMs: Long = 0,
    val lastSuccessMs: Long = 0,
    val error: FetchOutcome.Kind? = null,
    val errorDetail: String? = null,
    /** Stories this phone received after the user last acknowledged new items. */
    val ackAtMs: Long = 0,
    val bookmarks: List<Story> = emptyList(),
) {
    val newIds: Set<String>
        get() = cache.localSeen.filter { it.value > ackAtMs }.keys
}

/** Single source of truth for stories on the phone: network + on-disk cache. */
object NewsRepository {
    private const val TAG = "NewsRepo"
    private val _state = MutableStateFlow(RepoState())
    val state: StateFlow<RepoState> = _state
    private val mutex = Mutex()
    private val client = FeedClient()

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "cache.json")
    private fun bookmarkFile(ctx: Context) = File(ctx.filesDir, "bookmarks.json")

    suspend fun ensureLoaded(ctx: Context) {
        if (_state.value.loaded) return
        mutex.withLock {
            if (_state.value.loaded) return
            val (cache, bookmarks) = withContext(Dispatchers.IO) {
                val c = runCatching {
                    Net.json.decodeFromString(Cache.serializer(), cacheFile(ctx).readText())
                }.getOrElse { Cache() }
                val b = runCatching {
                    Net.json.decodeFromString(ListSerializer(Story.serializer()), bookmarkFile(ctx).readText())
                }.getOrElse { emptyList() }
                c to b
            }
            _state.update {
                it.copy(
                    cache = cache, bookmarks = bookmarks, loaded = true,
                    lastSuccessMs = Prefs.getLong(ctx, "last_success"),
                    lastAttemptMs = Prefs.getLong(ctx, "last_attempt"),
                    ackAtMs = Prefs.getLong(ctx, "ack_at"),
                )
            }
        }
    }

    /**
     * Fetch from the backend and merge. Safe to call from many places at once
     * (open, foreground, timer, pull-to-refresh, button): calls are serialised.
     * Returns ids of stories new to this phone.
     */
    suspend fun refresh(ctx: Context, reason: String): Set<String> {
        ensureLoaded(ctx)
        return mutex.withLock {
            val settings = Prefs.get(ctx)
            val now = System.currentTimeMillis()
            _state.update { it.copy(refreshing = true) }
            Prefs.putLong(ctx, "last_attempt", now)
            val urlKey = "etag:" + settings.feedUrl
            val cache = _state.value.cache
            val since = if (cache.supportsSince && cache.stories.isNotEmpty()) cache.cursor else null
            // Without cached stories a 304 would leave the screen empty, so skip the validator.
            val etag = if (cache.stories.isEmpty()) null else Prefs.getString(ctx, urlKey)
            val outcome = client.fetch(settings.feedUrl, etag, since)
            Log.i(TAG, "refresh($reason): ${outcome::class.simpleName}")
            var fresh: Set<String> = emptySet()
            when (outcome) {
                is FetchOutcome.Updated -> {
                    val wasEmpty = cache.stories.isEmpty()
                    val (merged, newIds) = FeedLogic.merge(cache, outcome.feed, now)
                    fresh = newIds
                    withContext(Dispatchers.IO) {
                        val tmp = File(ctx.filesDir, "cache.json.tmp")
                        tmp.writeText(Net.json.encodeToString(Cache.serializer(), merged))
                        tmp.renameTo(cacheFile(ctx))
                    }
                    if (since == null) Prefs.putString(ctx, urlKey, outcome.etag)
                    Prefs.putLong(ctx, "last_success", now)
                    // The very first load is not "new news": everything would light up.
                    val ack = if (wasEmpty) now else _state.value.ackAtMs
                    if (wasEmpty) Prefs.putLong(ctx, "ack_at", now)
                    _state.update {
                        it.copy(cache = merged, refreshing = false, lastAttemptMs = now, lastSuccessMs = now,
                            error = null, errorDetail = null, ackAtMs = ack)
                    }
                }
                is FetchOutcome.NotModified -> {
                    Prefs.putLong(ctx, "last_success", now)
                    _state.update {
                        it.copy(refreshing = false, lastAttemptMs = now, lastSuccessMs = now, error = null, errorDetail = null)
                    }
                }
                is FetchOutcome.Failed -> _state.update {
                    it.copy(refreshing = false, lastAttemptMs = now, error = outcome.kind, errorDetail = outcome.detail)
                }
            }
            if (_state.value.cache.stories.isEmpty()) emptySet() else fresh
        }
    }

    fun acknowledgeNew(ctx: Context) {
        val now = System.currentTimeMillis()
        Prefs.putLong(ctx, "ack_at", now)
        _state.update { it.copy(ackAtMs = now) }
    }

    fun toggleBookmark(ctx: Context, story: Story) {
        val list = _state.value.bookmarks.toMutableList()
        if (list.removeAll { it.id == story.id }.not()) list.add(0, story)
        _state.update { it.copy(bookmarks = list) }
        runCatching {
            bookmarkFile(ctx).writeText(Net.json.encodeToString(ListSerializer(Story.serializer()), list))
        }
    }

    /** After the feed URL changes: forget validators and the incremental cursor. */
    fun resetSync(ctx: Context) {
        _state.update { it.copy(cache = it.cache.copy(cursor = null, supportsSince = false)) }
    }

    suspend fun clearCache(ctx: Context) = mutex.withLock {
        withContext(Dispatchers.IO) { cacheFile(ctx).delete() }
        val keepSettings = Prefs.get(ctx)
        Prefs.putString(ctx, "etag:" + keepSettings.feedUrl, null)
        _state.update { it.copy(cache = Cache(), error = null) }
    }
}
