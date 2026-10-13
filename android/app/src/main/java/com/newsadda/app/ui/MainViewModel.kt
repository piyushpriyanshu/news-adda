package com.newsadda.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.newsadda.app.data.FeedLogic
import com.newsadda.app.data.NewsRepository
import com.newsadda.app.data.Prefs
import com.newsadda.app.data.RepoState
import com.newsadda.app.data.Settings
import com.newsadda.app.data.model.Story
import com.newsadda.app.update.UpdateManager
import com.newsadda.app.work.Schedules
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Section { HOME, BREAKING, LEADERS, CONTROVERSY, SEATS, STATEMENTS, DISTRICTS, SAVED, SETTINGS }

fun Section.title(s: Str) = when (this) {
    Section.HOME -> s.home
    Section.BREAKING -> s.breaking
    Section.LEADERS -> s.leaders
    Section.CONTROVERSY -> s.controversy
    Section.SEATS -> s.seats
    Section.STATEMENTS -> s.statements
    Section.DISTRICTS -> s.districts
    Section.SAVED -> s.saved
    Section.SETTINGS -> s.settings
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()

    val repo: StateFlow<RepoState> = NewsRepository.state
    val settings: StateFlow<Settings> = Prefs.flow(app)
    val updates = UpdateManager.state

    val section = MutableStateFlow(Section.HOME)
    val leaderFilter = MutableStateFlow<String?>(null)     // null = followed / all
    val seatFilter = MutableStateFlow<String?>(null)       // "figure:id" or "seat:id" or "district:id"
    val districtFilter = MutableStateFlow<String?>(null)
    val openStoryId = MutableStateFlow<String?>(null)
    val searchOpen = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    /** Newest breaking story that arrived while the app is open (shown as a banner). */
    val alert = MutableStateFlow<Story?>(null)

    private var loop: Job? = null

    init {
        viewModelScope.launch { NewsRepository.ensureLoaded(ctx) }
    }

    /** Called while the app is in the foreground: refresh now, then every N minutes. */
    fun startForegroundLoop() {
        loop?.cancel()
        loop = viewModelScope.launch {
            NewsRepository.ensureLoaded(ctx)
            announce(NewsRepository.refresh(ctx, "foreground"))
            while (isActive) {
                val minutes = settings.value.refreshMinutes
                delay(minutes * 60_000L)
                announce(NewsRepository.refresh(ctx, "timer"))
            }
        }
    }

    fun stopForegroundLoop() {
        loop?.cancel()
        loop = null
    }

    fun refresh(reason: String = "manual") {
        viewModelScope.launch { announce(NewsRepository.refresh(ctx, reason)) }
    }

    private fun announce(fresh: Set<String>) {
        if (fresh.isEmpty()) return
        val hidden = settings.value.hiddenSources
        val b = repo.value.cache.stories
            .filter { it.id in fresh && it.breaking && FeedLogic.visible(it, hidden) }
            .maxByOrNull { it.firstSeenAt } ?: return
        alert.value = b
        buzz()
    }

    private fun buzz() {
        runCatching {
            val v = if (android.os.Build.VERSION.SDK_INT >= 31) {
                ctx.getSystemService(android.os.VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(android.os.Vibrator::class.java)
            }
            v?.vibrate(android.os.VibrationEffect.createOneShot(120, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    fun checkUpdates(reason: String) {
        viewModelScope.launch { UpdateManager.checkNow(ctx, reason) }
    }

    fun downloadUpdate() {
        val info = updates.value.available ?: return
        viewModelScope.launch { UpdateManager.download(ctx, info) }
    }

    fun installUpdate() {
        if (!UpdateManager.install(ctx)) {
            if (!UpdateManager.canInstall(ctx)) {
                message.value = Str.of(ctx).allowInstall
            }
        }
    }

    fun acknowledgeNew() = NewsRepository.acknowledgeNew(ctx)

    fun toggleBookmark(story: Story) = NewsRepository.toggleBookmark(ctx, story)

    fun updateSettings(change: (Settings) -> Settings) {
        val before = settings.value
        Prefs.update(ctx, change)
        val after = settings.value
        if (before.feedUrl != after.feedUrl) {
            NewsRepository.resetSync(ctx)
            refresh("feed-url-changed")
        }
        if (before.refreshMinutes != after.refreshMinutes) startForegroundLoop()
        if (before.language != after.language) com.newsadda.app.work.Notifier.createChannels(ctx)
        if (before.updateCheckHours != after.updateCheckHours || before.newsNotifications != after.newsNotifications) {
            Schedules.apply(ctx)
        }
    }

    fun toggleFollowLeader(id: String) = updateSettings {
        it.copy(followedLeaders = if (id in it.followedLeaders) it.followedLeaders - id else it.followedLeaders + id)
    }

    fun toggleDistrict(id: String) = updateSettings {
        it.copy(preferredDistricts = if (id in it.preferredDistricts) it.preferredDistricts - id else it.preferredDistricts + id)
    }

    fun toggleSource(id: String) = updateSettings {
        it.copy(hiddenSources = if (id in it.hiddenSources) it.hiddenSources - id else it.hiddenSources + id)
    }

    fun clearCache() {
        viewModelScope.launch {
            NewsRepository.clearCache(ctx)
            NewsRepository.refresh(ctx, "cache-cleared")
        }
    }

    fun story(id: String): Story? =
        repo.value.cache.stories.firstOrNull { it.id == id } ?: repo.value.bookmarks.firstOrNull { it.id == id }

    fun visibleStories(): List<Story> {
        val hidden = settings.value.hiddenSources
        return repo.value.cache.stories.filter { FeedLogic.visible(it, hidden) }
    }
}
