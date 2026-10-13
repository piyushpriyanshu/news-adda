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

enum class Section(val title: String) {
    HOME("होम"),
    BREAKING("ब्रेकिंग न्यूज़"),
    LEADERS("नेता ट्रैकर"),
    CONTROVERSY("राजनीतिक विवाद"),
    SEATS("सीट और बाहुबली"),
    STATEMENTS("नेताओं के बयान"),
    DISTRICTS("जिला समाचार"),
    SAVED("सहेजी गई"),
    SETTINGS("सेटिंग्स"),
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

    private var loop: Job? = null

    init {
        viewModelScope.launch { NewsRepository.ensureLoaded(ctx) }
    }

    /** Called while the app is in the foreground: refresh now, then every N minutes. */
    fun startForegroundLoop() {
        loop?.cancel()
        loop = viewModelScope.launch {
            NewsRepository.ensureLoaded(ctx)
            refresh("foreground")
            while (isActive) {
                val minutes = settings.value.refreshMinutes
                delay(minutes * 60_000L)
                refresh("timer")
            }
        }
    }

    fun stopForegroundLoop() {
        loop?.cancel()
        loop = null
    }

    fun refresh(reason: String = "manual") {
        viewModelScope.launch { NewsRepository.refresh(ctx, reason) }
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
                message.value = "इंस्टॉल की अनुमति दें, फिर वापस आकर दोबारा \"इंस्टॉल करें\" दबाएं"
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
