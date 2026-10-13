package com.newsadda.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newsadda.app.data.FeedLogic
import com.newsadda.app.data.FetchOutcome
import com.newsadda.app.data.TimeFmt
import com.newsadda.app.data.model.Story
import kotlinx.coroutines.delay
import java.time.Instant

private val NAV = listOf(
    Section.HOME, Section.BREAKING, Section.LEADERS, Section.CONTROVERSY,
    Section.SEATS, Section.STATEMENTS, Section.DISTRICTS,
)

@Composable
fun NewsAddaApp(vm: MainViewModel) {
    val repo by vm.repo.collectAsState()
    val settings by vm.settings.collectAsState()
    val section by vm.section.collectAsState()
    val searchOpen by vm.searchOpen.collectAsState()
    val openId by vm.openStoryId.collectAsState()
    val msg by vm.message.collectAsState()
    val alert by vm.alert.collectAsState()
    val snack = remember { SnackbarHostState() }
    val c = LocalNewsColors.current
    val str = LocalStr.current

    // Re-render relative times ("5 min ago") every 30 s.
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = Instant.now() } }
    LaunchedEffect(repo.lastAttemptMs) { now = Instant.now() }
    LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it); vm.message.value = null } }
    LaunchedEffect(alert?.id) { if (alert != null) { delay(20_000); vm.alert.value = null } }

    val cache = repo.cache
    val names = remember(cache.entities, str) { Names(cache.entities, str) }
    val visible = remember(cache.stories, settings.hiddenSources) {
        cache.stories.filter { FeedLogic.visible(it, settings.hiddenSources) }
    }
    val savedIds = remember(repo.bookmarks) { repo.bookmarks.map { it.id }.toSet() }
    val breakingCount = remember(visible, now) { visible.count { FeedLogic.isBreakingNow(it, now) } }
    val env = ListEnv(
        names = names, newIds = repo.newIds, savedIds = savedIds, now = now,
        refreshing = repo.refreshing, onRefresh = { vm.refresh("pull") },
        onOpen = { vm.openStoryId.value = it.id }, onSave = { vm.toggleBookmark(it) },
    )
    val subPage = searchOpen || section == Section.SAVED || section == Section.SETTINGS

    BackHandler(enabled = searchOpen) { vm.searchOpen.value = false }
    BackHandler(enabled = !searchOpen && section != Section.HOME) { vm.section.value = Section.HOME }

    Scaffold(
        containerColor = c.canvas,
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            Surface(color = c.card, shadowElevation = 1.dp) {
                Column(Modifier.statusBarsPadding()) {
                    TopRow(vm, subPage, searchOpen, section)
                    StatusRow(vm, now)
                    if (repo.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (!subPage) {
                        val navState = rememberLazyListState()
                        // Keep the selected section's pill in view.
                        LaunchedEffect(section) {
                            val i = NAV.indexOf(section)
                            if (i >= 0) navState.animateScrollToItem((i - 1).coerceAtLeast(0))
                        }
                        LazyRow(
                            state = navState,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(NAV) { sec ->
                                PillChip(sec.title(str), sec == section, if (sec == Section.BREAKING) breakingCount else 0) {
                                    vm.section.value = sec
                                }
                            }
                        }
                    }
                    StatusBanners(vm, now)
                }
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
            AnimatedVisibility(
                visible = alert != null,
                enter = slideInVertically { -it }, exit = slideOutVertically { -it },
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                alert?.let { a ->
                    BreakingAlert(a, now, onRead = { vm.openStoryId.value = a.id; vm.alert.value = null },
                        onDismiss = { vm.alert.value = null })
                }
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
private fun TopRow(vm: MainViewModel, subPage: Boolean, searchOpen: Boolean, section: Section) {
    val str = LocalStr.current
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (subPage) {
            IconButton(onClick = { if (searchOpen) vm.searchOpen.value = false else vm.section.value = Section.HOME }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = str.back)
            }
            Text(if (searchOpen) str.search else section.title(str), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f))
        } else {
            Spacer(Modifier.width(12.dp))
            BrandMark()
            Spacer(Modifier.width(10.dp))
            Text(str.appName, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f), maxLines = 1)
        }
        if (!searchOpen) IconButton(onClick = { vm.searchOpen.value = true }) {
            Icon(Icons.Outlined.Search, contentDescription = str.search)
        }
        if (!subPage) {
            IconButton(onClick = { vm.section.value = Section.SAVED }) {
                Icon(Icons.Outlined.BookmarkBorder, contentDescription = str.saved)
            }
            IconButton(onClick = { vm.section.value = Section.SETTINGS }) {
                Icon(Icons.Outlined.Settings, contentDescription = str.settings)
            }
        }
    }
}

/** Text mark: the app's own initial on the brand red. */
@Composable
private fun BrandMark() {
    val c = LocalNewsColors.current
    Box(Modifier.size(32.dp).background(c.breaking, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
        Text("N", color = Color.White, fontWeight = FontWeight.Black, fontSize = 19.sp)
    }
}

@Composable
private fun StatusRow(vm: MainViewModel, now: Instant) {
    val repo by vm.repo.collectAsState()
    val str = LocalStr.current
    val c = LocalNewsColors.current
    val last = repo.lastSuccessMs.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (repo.error == null) LiveDot() else Box(Modifier.size(8.dp).background(c.faint, RoundedCornerShape(50)))
        Text(str.live, style = MaterialTheme.typography.labelSmall, color = if (repo.error == null) c.live else c.faint)
        Text(
            if (last == null) str.tagline else str.updatedAt(TimeFmt.short(last, now, str), TimeFmt.ago(last, now, str)),
            style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        FilledTonalButton(
            onClick = { vm.refresh("button") }, enabled = !repo.refreshing,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier = Modifier.padding(vertical = 2.dp),
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (repo.refreshing) str.updating else str.updateNow, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun BreakingAlert(s: Story, now: Instant, onRead: () -> Unit, onDismiss: () -> Unit) {
    val c = LocalNewsColors.current
    val str = LocalStr.current
    val lead = s.reports.firstOrNull()
    Surface(color = c.breaking, shape = RoundedCornerShape(16.dp), shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.clickable(onClick = onRead).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LiveDot(Color.White, 8.dp)
                Text(str.breakingAlert, style = MaterialTheme.typography.labelSmall, color = Color.White)
                if (lead != null) {
                    SourceIcon(lead.domain, lead.publisherName(), 18.dp)
                    Text(lead.publisherName(), style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
                Text("· " + TimeFmt.ago(FeedLogic.instant(s.firstSeenAt), now, str), style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f))
            }
            Text(s.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = Color.White,
                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(str.dismiss, color = Color.White.copy(alpha = 0.85f)) }
                TextButton(onClick = onRead) { Text(str.read, color = Color.White, fontWeight = FontWeight.Bold) }
            }
        }
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
