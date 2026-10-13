package com.newsadda.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newsadda.app.data.FeedLogic
import com.newsadda.app.data.FetchOutcome
import com.newsadda.app.data.TimeFmt
import kotlinx.coroutines.delay
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsAddaApp(vm: MainViewModel) {
    val repo by vm.repo.collectAsState()
    val settings by vm.settings.collectAsState()
    val section by vm.section.collectAsState()
    val searchOpen by vm.searchOpen.collectAsState()
    val openId by vm.openStoryId.collectAsState()
    val msg by vm.message.collectAsState()
    val snack = remember { SnackbarHostState() }

    // Re-render relative times ("5 मिनट पहले") every 30 s.
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = Instant.now() } }
    LaunchedEffect(repo.lastAttemptMs) { now = Instant.now() }
    LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it); vm.message.value = null } }

    val cache = repo.cache
    val str = LocalStr.current
    val names = remember(cache.entities, str) { Names(cache.entities, str) }
    val visible = remember(cache.stories, settings.hiddenSources) {
        cache.stories.filter { FeedLogic.visible(it, settings.hiddenSources) }
    }
    val savedIds = remember(repo.bookmarks) { repo.bookmarks.map { it.id }.toSet() }
    val env = ListEnv(
        names = names, newIds = repo.newIds, savedIds = savedIds, now = now,
        refreshing = repo.refreshing, onRefresh = { vm.refresh("pull") },
        onOpen = { vm.openStoryId.value = it.id }, onSave = { vm.toggleBookmark(it) },
    )

    BackHandler(enabled = searchOpen) { vm.searchOpen.value = false }
    BackHandler(enabled = !searchOpen && section != Section.HOME) { vm.section.value = Section.HOME }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            Column {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    navigationIcon = {
                        if (searchOpen) IconButton(onClick = { vm.searchOpen.value = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = str.back)
                        }
                    },
                    title = {
                        Column {
                            Text(if (searchOpen) str.search else str.appName, style = MaterialTheme.typography.titleLarge)
                            val last = repo.lastSuccessMs.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) }
                            Text(
                                if (last == null) str.tagline else str.updatedAt(TimeFmt.short(last, now, str), TimeFmt.ago(last, now, str)),
                                style = MaterialTheme.typography.bodySmall, color = LocalNewsColors.current.muted,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    actions = {
                        if (!searchOpen) IconButton(onClick = { vm.searchOpen.value = true }) {
                            Icon(Icons.Filled.Search, contentDescription = str.search)
                        }
                        TextButton(onClick = { vm.refresh("button") }, enabled = !repo.refreshing) {
                            Text(if (repo.refreshing) str.updating else str.updateNow)
                        }
                    },
                )
                if (repo.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!searchOpen) {
                    PrimaryScrollableTabRow(selectedTabIndex = section.ordinal, edgePadding = 8.dp,
                        containerColor = MaterialTheme.colorScheme.background) {
                        Section.entries.forEach { sec ->
                            Tab(selected = sec == section, onClick = { vm.section.value = sec },
                                text = { Text(sec.title(str), maxLines = 1) })
                        }
                    }
                }
                StatusBanners(vm, now)
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (searchOpen) {
                SearchScreen(visible, cache.entities, cache.sources, env)
            } else when (section) {
                Section.HOME -> HomeScreen(vm, visible, env)
                Section.BREAKING -> BreakingScreen(visible, env)
                Section.LEADERS -> LeadersScreen(vm, visible, env)
                Section.CONTROVERSY -> ControversyScreen(visible, env)
                Section.SEATS -> SeatsScreen(vm, visible, env)
                Section.STATEMENTS -> StatementsScreen(vm, visible, env)
                Section.DISTRICTS -> DistrictsScreen(vm, visible, env)
                Section.SAVED -> SavedScreen(vm, env)
                Section.SETTINGS -> SettingsScreen(vm, names)
            }
        }
    }

    openId?.let { id ->
        vm.story(id)?.let { s ->
            StoryDetailSheet(s, names, saved = id in savedIds, onSave = { vm.toggleBookmark(s) },
                onDismiss = { vm.openStoryId.value = null })
        } ?: run { vm.openStoryId.value = null }
    }
}

@Composable
private fun StatusBanners(vm: MainViewModel, now: Instant) {
    val repo by vm.repo.collectAsState()
    val up by vm.updates.collectAsState()
    val c = LocalNewsColors.current
    val str = LocalStr.current
    val cache = repo.cache
    repo.error?.let { err ->
        val text = when (err) {
            FetchOutcome.Kind.OFFLINE -> str.offline
            FetchOutcome.Kind.TIMEOUT -> str.timeout
            FetchOutcome.Kind.SERVER -> str.serverError(repo.errorDetail ?: "")
            FetchOutcome.Kind.BAD_DATA -> str.badData
            FetchOutcome.Kind.BAD_URL -> str.badUrl
        }
        val suffix = if (cache.stories.isNotEmpty()) str.showingCached else ""
        Notice(text + suffix, c.breaking, str.retry to { vm.refresh("retry") })
    }
    val generated = FeedLogic.instant(cache.generatedAt)
    if (repo.error == null && generated != null && java.time.Duration.between(generated, now).toMinutes() > 45) {
        Notice(str.feedStale(TimeFmt.ago(generated, now, str)), c.caution)
    }
    val failing = cache.sources.count { it.status == "error" }
    if (failing > 0 && repo.error == null) {
        Notice(str.sourcesFailing(failing), c.muted, str.view to { vm.section.value = Section.SETTINGS })
    }
    val info = up.available
    if (info != null) {
        if (up.readyFile != null) {
            Notice(str.updateReady(info.versionName), c.multi, str.install to { vm.installUpdate() })
        } else if (!up.downloading) {
            Notice(str.updateAvailable(info.versionName), c.multi, str.download to { vm.downloadUpdate() })
        } else {
            Notice(str.downloading((up.progress * 100).toInt()), c.multi)
        }
    }
}
