package com.newsadda.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
    val names = remember(cache.entities, cache.categories) { Names(cache.entities, cache.categories) }
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
                            Icon(Icons.Filled.ArrowBack, contentDescription = "वापस")
                        }
                    },
                    title = {
                        Column {
                            Text(if (searchOpen) "खोज" else "न्यूज़ अड्डा", style = MaterialTheme.typography.titleLarge)
                            val last = repo.lastSuccessMs.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) }
                            Text(
                                if (last == null) "उत्तर प्रदेश राजनीति" else "अपडेट: ${TimeFmt.short(last, now)} (${TimeFmt.ago(last, now)})",
                                style = MaterialTheme.typography.bodySmall, color = LocalNewsColors.current.muted,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    actions = {
                        if (!searchOpen) IconButton(onClick = { vm.searchOpen.value = true }) {
                            Icon(Icons.Filled.Search, contentDescription = "खोजें")
                        }
                        TextButton(onClick = { vm.refresh("button") }, enabled = !repo.refreshing) {
                            Text(if (repo.refreshing) "अपडेट हो रहा…" else "अभी अपडेट करें")
                        }
                    },
                )
                if (repo.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!searchOpen) {
                    PrimaryScrollableTabRow(selectedTabIndex = section.ordinal, edgePadding = 8.dp,
                        containerColor = MaterialTheme.colorScheme.background) {
                        Section.entries.forEach { sec ->
                            Tab(selected = sec == section, onClick = { vm.section.value = sec },
                                text = { Text(sec.title, maxLines = 1) })
                        }
                    }
                }
                StatusBanners(vm, now)
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (searchOpen) {
                SearchScreen(visible, cache.entities, cache.sources, cache.categories, env)
            } else when (section) {
                Section.HOME -> HomeScreen(vm, visible, env)
                Section.BREAKING -> BreakingScreen(visible, env)
                Section.LEADERS -> LeadersScreen(vm, visible, env)
                Section.CONTROVERSY -> ControversyScreen(visible, env)
                Section.SEATS -> SeatsScreen(vm, visible, env)
                Section.STATEMENTS -> StatementsScreen(vm, visible, env)
                Section.DISTRICTS -> DistrictsScreen(vm, visible, env)
                Section.SAVED -> SavedScreen(vm, env)
                Section.SETTINGS -> SettingsScreen(vm)
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
    val cache = repo.cache
    repo.error?.let { err ->
        val text = when (err) {
            FetchOutcome.Kind.OFFLINE -> "इंटरनेट उपलब्ध नहीं"
            FetchOutcome.Kind.TIMEOUT -> "सर्वर ने समय पर जवाब नहीं दिया"
            FetchOutcome.Kind.SERVER -> "सर्वर से त्रुटि (${repo.errorDetail ?: ""})"
            FetchOutcome.Kind.BAD_DATA -> "सर्वर से गलत डेटा मिला"
            FetchOutcome.Kind.BAD_URL -> "बैकएंड पता गलत है — सेटिंग्स देखें"
        }
        val suffix = if (cache.stories.isNotEmpty()) " — सहेजी गई खबरें दिखाई जा रही हैं" else ""
        Notice(text + suffix, c.breaking, "फिर कोशिश" to { vm.refresh("retry") })
    }
    val generated = FeedLogic.instant(cache.generatedAt)
    if (repo.error == null && generated != null && java.time.Duration.between(generated, now).toMinutes() > 45) {
        Notice("सर्वर की फ़ीड ${TimeFmt.ago(generated, now)} बनी थी — संग्रह में देरी हो सकती है।", c.caution)
    }
    val failing = cache.sources.count { it.status == "error" }
    if (failing > 0 && repo.error == null) {
        Notice("$failing स्रोत अभी त्रुटि में हैं; बाकी से खबरें आ रही हैं।", c.muted, "देखें" to { vm.section.value = Section.SETTINGS })
    }
    val info = up.available
    if (info != null) {
        if (up.readyFile != null) {
            Notice("नया संस्करण ${info.versionName} डाउनलोड हो चुका है", c.multi, "इंस्टॉल करें" to { vm.installUpdate() })
        } else if (!up.downloading) {
            Notice("नया संस्करण ${info.versionName} उपलब्ध है", c.multi, "डाउनलोड" to { vm.downloadUpdate() })
        } else {
            Notice("नया संस्करण डाउनलोड हो रहा है… ${(up.progress * 100).toInt()}%", c.multi)
        }
    }
}
