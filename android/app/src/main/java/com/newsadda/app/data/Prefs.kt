package com.newsadda.app.data

import android.content.Context
import com.newsadda.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Settings(
    val refreshMinutes: Int = 5,
    val feedUrl: String = BuildConfig.DEFAULT_FEED_URL,
    val followedLeaders: Set<String> = emptySet(),
    val preferredDistricts: Set<String> = emptySet(),
    val hiddenSources: Set<String> = emptySet(),
    val newsNotifications: Boolean = true,
    val notifyAllBreaking: Boolean = false,
    val updateCheckHours: Int = 3,
    val autoDownloadUpdates: Boolean = true,
    val textScale: Float = 1.0f,
    val politicsOnlyHome: Boolean = false,
    /** "en" or "hi": interface language only; news stays in Hindi. */
    val language: String = "en",
)

object Prefs {
    val REFRESH_OPTIONS = listOf(1, 5, 10, 15, 30)
    val UPDATE_OPTIONS = listOf(1, 3, 6, 12)
    val TEXT_SCALES = listOf(0.9f, 1.0f, 1.15f, 1.3f)

    private const val FILE = "settings"
    private val state = MutableStateFlow(Settings())
    private var loaded = false

    fun flow(ctx: Context): StateFlow<Settings> {
        load(ctx)
        return state
    }

    fun get(ctx: Context): Settings {
        load(ctx)
        return state.value
    }

    @Synchronized
    private fun load(ctx: Context) {
        if (loaded) return
        val p = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        state.value = Settings(
            refreshMinutes = p.getInt("refresh", 5).takeIf { it in REFRESH_OPTIONS } ?: 5,
            feedUrl = p.getString("feed_url", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_FEED_URL,
            followedLeaders = p.getStringSet("leaders", emptySet())!!.toSet(),
            preferredDistricts = p.getStringSet("districts", emptySet())!!.toSet(),
            hiddenSources = p.getStringSet("hidden_sources", emptySet())!!.toSet(),
            newsNotifications = p.getBoolean("news_notif", true),
            notifyAllBreaking = p.getBoolean("notify_all_breaking", false),
            updateCheckHours = p.getInt("update_hours", 3).takeIf { it in UPDATE_OPTIONS } ?: 3,
            autoDownloadUpdates = p.getBoolean("auto_download", true),
            textScale = p.getFloat("text_scale", 1.0f),
            politicsOnlyHome = p.getBoolean("politics_only", false),
            language = if (p.getString("lang", "en") == "hi") "hi" else "en",
        )
        loaded = true
    }

    fun update(ctx: Context, change: (Settings) -> Settings) {
        load(ctx)
        val s = change(state.value)
        state.value = s
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt("refresh", s.refreshMinutes)
            .putString("feed_url", s.feedUrl)
            .putStringSet("leaders", s.followedLeaders)
            .putStringSet("districts", s.preferredDistricts)
            .putStringSet("hidden_sources", s.hiddenSources)
            .putBoolean("news_notif", s.newsNotifications)
            .putBoolean("notify_all_breaking", s.notifyAllBreaking)
            .putInt("update_hours", s.updateCheckHours)
            .putBoolean("auto_download", s.autoDownloadUpdates)
            .putFloat("text_scale", s.textScale)
            .putBoolean("politics_only", s.politicsOnlyHome)
            .putString("lang", s.language)
            .apply()
    }

    // Small bits of runtime state, kept apart from user settings.
    private fun rt(ctx: Context) = ctx.applicationContext.getSharedPreferences("runtime", Context.MODE_PRIVATE)
    fun getLong(ctx: Context, key: String) = rt(ctx).getLong(key, 0L)
    fun putLong(ctx: Context, key: String, v: Long) = rt(ctx).edit().putLong(key, v).apply()
    fun getString(ctx: Context, key: String): String? = rt(ctx).getString(key, null)
    fun putString(ctx: Context, key: String, v: String?) = rt(ctx).edit().putString(key, v).apply()
    fun getSet(ctx: Context, key: String): Set<String> = rt(ctx).getStringSet(key, emptySet())!!.toSet()
    fun putSet(ctx: Context, key: String, v: Set<String>) = rt(ctx).edit().putStringSet(key, v).apply()
}
