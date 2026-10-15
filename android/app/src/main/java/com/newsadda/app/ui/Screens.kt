package com.newsadda.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newsadda.app.data.FeedLogic
import com.newsadda.app.data.LiveWire
import com.newsadda.app.data.TimeFmt
import com.newsadda.app.data.model.LiveItem
import com.newsadda.app.data.model.Story
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/** Everything a list screen needs, gathered once per recomposition in App.kt. */
class ListEnv(
    val names: Names,
    val newIds: Set<String>,
    val savedIds: Set<String>,
    val now: Instant,
    val refreshing: Boolean,
    val onRefresh: () -> Unit,
    val onOpen: (Story) -> Unit,
    val onSave: (Story) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryList(
    stories: List<Story>,
    env: ListEnv,
    emptyTitle: String,
    emptyBody: String? = null,
    showStatementType: Boolean = false,
    state: LazyListState = rememberLazyListState(),
    header: LazyListScope.() -> Unit = {},
) {
    PullToRefreshBox(isRefreshing = env.refreshing, onRefresh = env.onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            // Stable first item: sections that appear later (breaking carousel, live wire)
            // are inserted below it, so they stay in view instead of above the scroll position.
            item(key = "top") { Spacer(Modifier.height(4.dp)) }
            header()
            if (stories.isEmpty()) {
                item { EmptyState(emptyTitle, emptyBody) }
            }
            items(stories, key = { it.id }) { s ->
                StoryCard(
                    s, env.names, isNew = s.id in env.newIds, saved = s.id in env.savedIds, now = env.now,
                    onOpen = { env.onOpen(s) }, onSave = { env.onSave(s) }, showStatementType = showStatementType,
                )
            }
        }
    }
}

@Composable
fun HomeScreen(vm: MainViewModel, all: List<Story>, env: ListEnv) {
    val settings by vm.settings.collectAsState()
    val live by LiveWire.items.collectAsState()
    val str = LocalStr.current
    val c = LocalNewsColors.current
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var wireOpen by rememberSaveable { mutableStateOf(false) }
    val breaking = remember(all, env.now) { all.filter { FeedLogic.isBreakingNow(it, env.now) }.take(10) }
    // Stories already in the Breaking-now carousel are not repeated in the list below it.
    val inCarousel = remember(breaking) { breaking.map { it.id }.toSet() }
    val list = (if (settings.politicsOnlyHome) all.filter(FeedLogic::isPolitical) else all).filter { it.id !in inCarousel }
    val wire = remember(live, settings.politicsOnlyHome, env.names) {
        live.filter { FeedLogic.isUpHeadline(it.title, env.names.upHi, strict = it.viaGoogle) }
            .filter { !settings.politicsOnlyHome || FeedLogic.isPoliticalHeadline(it.title, env.names.politicalHi) }
    }
    val newCount = list.count { it.id in env.newIds }
    var jumping by remember { mutableStateOf(false) }

    StoryList(list, env, str.emptyHome, str.emptyHomeBody, state = state) {
        if (newCount > 0 && !jumping) {
            item(key = "new-banner") {
                NewStoriesBar(str.newStories(newCount), str.showNew) {
                    val first = list.indexOfFirst { it.id in env.newIds }
                    jumping = true
                    scope.launch {
                        if (first >= 0) {
                            withFrameNanos { }  // let the bar disappear first so item positions are final
                            // Stories are the last items of the list, after the header sections.
                            val headerItems = state.layoutInfo.totalItemsCount - list.size
                            state.animateScrollToItem((headerItems + first).coerceAtLeast(0))
                        }
                        // The new-story dots stay visible a moment so you can see which ones are new.
                        delay(6_000)
                        vm.acknowledgeNew()
                        jumping = false
                    }
                }
            }
        }
        if (breaking.isNotEmpty()) {
            item(key = "breaking-head") {
                SectionLabel(str.breakingNow, trailing = {
                    TextButton(onClick = { vm.section.value = Section.BREAKING }) { Text(str.seeAll) }
                })
            }
            item(key = "breaking-row") {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(breaking, key = { "b-" + it.id }) { s ->
                        BreakingCard(s, env.now, onOpen = { env.onOpen(s) }, modifier = Modifier.width(290.dp))
                    }
                }
            }
        }
        if (settings.liveWire && wire.isNotEmpty()) {
            item(key = "wire-head") {
                SectionLabel(str.liveWire, trailing = {
                    TextButton(onClick = { wireOpen = !wireOpen }) { Text(if (wireOpen) str.showLess else str.seeAll) }
                })
            }
            item(key = "wire-box") {
                LiveWireBox(wire.take(if (wireOpen) 30 else 4), env.now)
            }
        }
        item(key = "home-filter") {
            SectionLabel(str.latest)
            ChipRow(
                listOf("pol" to str.politicsOnly, "all" to str.allUp),
                if (settings.politicsOnlyHome) "pol" else "all",
            ) { id -> vm.updateSettings { it.copy(politicsOnlyHome = id == "pol") } }
        }
    }
}

@Composable
private fun LiveWireBox(items: List<LiveItem>, now: Instant) {
    val c = LocalNewsColors.current
    val str = LocalStr.current
    val ctx = LocalContext.current
    Surface(color = c.card, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(str.liveWireNote, style = MaterialTheme.typography.bodySmall, color = c.faint,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
            items.forEachIndexed { i, it ->
                if (i > 0) HorizontalDivider(color = c.divider, modifier = Modifier.padding(start = 46.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { Actions.open(ctx, it.url, str) }.padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SourceIcon(it.domain, it.publisher, 22.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(it.publisher, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            val t = FeedLogic.instant(it.publishedAt) ?: Instant.ofEpochMilli(it.seenAt)
                            Text(TimeFmt.ago(t, now, str), style = MaterialTheme.typography.bodySmall, color = c.newDot)
                            if (it.breakingHint) Pill(str.tagBreaking.uppercase(), c.onBreaking, c.breaking)
                            Pill(str.direct, c.muted, c.divider)
                        }
                        Text(it.title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
fun BreakingScreen(all: List<Story>, env: ListEnv) {
    val str = LocalStr.current
    val list = all.filter { it.breaking && Duration.between(FeedLogic.instant(it.firstSeenAt) ?: Instant.EPOCH, env.now).toHours() < 24 }
    StoryList(list, env, str.breakingEmpty, str.breakingEmptyBody)
}

@Composable
fun LeadersScreen(vm: MainViewModel, all: List<Story>, env: ListEnv) {
    val settings by vm.settings.collectAsState()
    val selected by vm.leaderFilter.collectAsState()
    val str = LocalStr.current
    val leaders = vm.repo.collectAsState().value.cache.entities.leaders
    val ordered = remember(leaders, settings.followedLeaders) {
        leaders.sortedBy { if (it.id in settings.followedLeaders) 0 else 1 }
    }
    val follows = settings.followedLeaders
    val list = all.filter { s ->
        when {
            selected != null -> selected in s.entities.leaders
            follows.isNotEmpty() -> s.entities.leaders.any { it in follows }
            else -> s.entities.leaders.isNotEmpty()
        }
    }
    StoryList(list, env, str.leaderEmpty) {
        item(key = "leader-head") {
            ChipRow(
                listOf<Pair<String?, String>>(null to if (follows.isEmpty()) str.allLeaders else str.following(follows.size)) +
                    ordered.map { it.id to (if (it.id in follows) "★ " else "") + env.names.of(it) },
                selected,
            ) { vm.leaderFilter.value = it }
            val sel = leaders.firstOrNull { it.id == selected }
            if (sel != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(env.names.of(sel), style = MaterialTheme.typography.titleMedium)
                        sel.roleHi?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalNewsColors.current.muted) }
                    }
                    OutlinedButton(onClick = { vm.toggleFollowLeader(sel.id) }) {
                        Text(if (sel.id in follows) str.unfollow else str.follow)
                    }
                }
            } else if (follows.isEmpty()) {
                Notice(str.followHint)
            }
        }
    }
}

@Composable
fun ControversyScreen(all: List<Story>, env: ListEnv) {
    val str = LocalStr.current
    StoryList(all.filter(FeedLogic::isControversy), env, str.controversyEmpty, showStatementType = true) {
        item(key = "contro-head") { Notice(str.controversyNote) }
    }
}

@Composable
fun SeatsScreen(vm: MainViewModel, all: List<Story>, env: ListEnv) {
    val selected by vm.seatFilter.collectAsState()
    val str = LocalStr.current
    val ent = vm.repo.collectAsState().value.cache.entities
    val chips = listOf<Pair<String?, String>>(null to str.all) +
        ent.figures.map { "figure:${it.id}" to env.names.of(it) } +
        ent.seats.map { "seat:${it.id}" to env.names.of(it) } +
        ent.districts.filter { it.key }.map { "district:${it.id}" to env.names.of(it) }
    val list = all.filter { s ->
        val sel = selected
        when {
            sel == null -> FeedLogic.isSeatsAndFigures(s)
            sel.startsWith("figure:") -> sel.removePrefix("figure:") in s.entities.figures
            sel.startsWith("seat:") -> sel.removePrefix("seat:") in s.entities.seats
            else -> sel.removePrefix("district:") in s.entities.districts && FeedLogic.isPolitical(s)
        }
    }
    StoryList(list, env, str.selectionEmpty) {
        item(key = "seats-head") {
            ChipRow(chips, selected) { vm.seatFilter.value = it }
            Notice(str.seatsNote)
        }
    }
}

@Composable
fun StatementsScreen(vm: MainViewModel, all: List<Story>, env: ListEnv) {
    val xAvailable = vm.repo.collectAsState().value.cache.xAvailable
    val str = LocalStr.current
    StoryList(all.filter { it.statement }, env, str.statementsEmpty, showStatementType = true) {
        item(key = "stmt-head") { if (!xAvailable) Notice(str.xUnavailable) }
    }
}

@Composable
fun DistrictsScreen(vm: MainViewModel, all: List<Story>, env: ListEnv) {
    val settings by vm.settings.collectAsState()
    val selected by vm.districtFilter.collectAsState()
    val str = LocalStr.current
    val c = LocalNewsColors.current
    val districts = vm.repo.collectAsState().value.cache.entities.districts
    val counts = remember(all) {
        val m = HashMap<String, Int>()
        all.forEach { s -> s.entities.districts.forEach { m[it] = (m[it] ?: 0) + 1 } }
        m
    }
    val ordered = remember(districts, settings.preferredDistricts, env.names) {
        districts.sortedWith(compareBy({ if (it.id in settings.preferredDistricts) 0 else if (it.key) 1 else 2 }, { env.names.of(it) }))
    }
    val sel = selected
    if (sel == null) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Notice(str.districtPick) }
            item {
                Surface(color = c.card, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Column {
                        ordered.forEachIndexed { i, d ->
                            if (i > 0) HorizontalDivider(color = c.divider)
                            Row(
                                Modifier.fillMaxWidth().clickable { vm.districtFilter.value = d.id }.padding(horizontal = 16.dp, vertical = 13.dp),
                                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text((if (d.id in settings.preferredDistricts) "★ " else "") + env.names.of(d), style = MaterialTheme.typography.bodyLarge)
                                val n = counts[d.id] ?: 0
                                Text(str.storyCount(n), style = MaterialTheme.typography.labelMedium,
                                    color = if (n > 0) MaterialTheme.colorScheme.primary else c.faint,
                                    modifier = Modifier.background(if (n > 0) MaterialTheme.colorScheme.primaryContainer else c.canvas, RoundedCornerShape(50))
                                        .padding(horizontal = 10.dp, vertical = 3.dp))
                            }
                        }
                    }
                }
            }
        }
        return
    }
    val d = districts.firstOrNull { it.id == sel }
    val dName = d?.let { env.names.of(it) } ?: ""
    StoryList(all.filter { sel in it.entities.districts }, env, str.districtEmpty(dName)) {
        item(key = "district-head") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.districtFilter.value = null }) { Text(str.allDistricts) }
                if (d != null) {
                    OutlinedButton(onClick = { vm.toggleDistrict(d.id) }) {
                        Text(if (d.id in settings.preferredDistricts) str.removeFavourite else str.makeFavourite)
                    }
                }
            }
            Text(dName, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
fun SavedScreen(vm: MainViewModel, env: ListEnv) {
    val saved = vm.repo.collectAsState().value.bookmarks
    val str = LocalStr.current
    StoryList(saved, env, str.savedEmpty, str.savedEmptyBody) {
        item(key = "saved-head") { SectionLabel(str.savedTitle) }
    }
}
