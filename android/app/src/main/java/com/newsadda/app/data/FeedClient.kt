package com.newsadda.app.data

import com.newsadda.app.BuildConfig
import com.newsadda.app.data.model.Feed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

sealed class FetchOutcome {
    data class Updated(val feed: Feed, val etag: String?) : FetchOutcome()
    data object NotModified : FetchOutcome()
    data class Failed(val kind: Kind, val detail: String) : FetchOutcome()

    enum class Kind { OFFLINE, TIMEOUT, SERVER, BAD_DATA, BAD_URL }
}

object Net {
    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "NewsAdda-Android/${BuildConfig.VERSION_NAME}")
                        .build()
                )
            }
            .build()
    }
}

class FeedClient(private val http: OkHttpClient = Net.client) {
    /**
     * Fetch the feed. Sends If-None-Match so an unchanged feed costs one tiny
     * 304, and `since` only when the server said it supports incremental
     * responses (static hosting ignores it).
     */
    suspend fun fetch(feedUrl: String, etag: String?, since: String?): FetchOutcome = withContext(Dispatchers.IO) {
        val base = feedUrl.trim().toHttpUrlOrNull()
            ?: return@withContext FetchOutcome.Failed(FetchOutcome.Kind.BAD_URL, feedUrl)
        val url = if (since != null) base.newBuilder().addQueryParameter("since", since).build() else base
        val req = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .apply { if (etag != null && since == null) header("If-None-Match", etag) }
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                when {
                    resp.code == 304 -> FetchOutcome.NotModified
                    !resp.isSuccessful -> FetchOutcome.Failed(FetchOutcome.Kind.SERVER, "HTTP ${resp.code}")
                    else -> {
                        val body = resp.body?.string().orEmpty()
                        val feed = try {
                            Net.json.decodeFromString(Feed.serializer(), body)
                        } catch (e: Exception) {
                            return@use FetchOutcome.Failed(FetchOutcome.Kind.BAD_DATA, e.message ?: "parse")
                        }
                        FetchOutcome.Updated(feed, resp.header("ETag"))
                    }
                }
            }
        } catch (e: UnknownHostException) {
            FetchOutcome.Failed(FetchOutcome.Kind.OFFLINE, e.message ?: "")
        } catch (e: SocketTimeoutException) {
            FetchOutcome.Failed(FetchOutcome.Kind.TIMEOUT, e.message ?: "")
        } catch (e: IOException) {
            FetchOutcome.Failed(FetchOutcome.Kind.OFFLINE, e.message ?: "")
        } catch (e: IllegalArgumentException) {
            FetchOutcome.Failed(FetchOutcome.Kind.BAD_URL, e.message ?: "")
        }
    }
}
