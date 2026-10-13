package com.newsadda.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.newsadda.app.data.FeedLogic
import com.newsadda.app.data.model.Story
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
        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
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
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val list = if (settings.politicsOnlyHome) all.filter(FeedLogic::isPolitical) else all
    val newCount = list.count { it.id in env.newIds }
    StoryList(list, env, "अभी कोई खबर नहीं", "ऊपर “अभी अपडेट करें” दबाएं या नीचे खींचें।", state = state) {
        item(key = "home-head") {
            ChipRow(
                listOf("all" to "सभी यूपी खबरें", "pol" to "केवल राजनीति"),
                if (settings.politicsOnlyHome) "pol" else "all",
            ) { id -> vm.updateSettings { it.copy(politicsOnlyHome = id == "pol") } }
            if (newCount > 0) {
                Notice("$newCount नई खबरें आई हैं", LocalNewsColors.current.newDot, "देख लिया" to {
                    scope.launch { state.animateScrollToItem(0) }
                    vm.acknowledgeNew()
                })
            }
        }
    }
}

@Composable
fun BreakingScreen(all: List<Story>, env: ListEnv) {
    val list = all.filter { it.breaking && Duration.between(FeedLogic.instant(it.firstSeenAt) ?: Instant.EPOCH, env.now).toHours() < 24 }
    StoryList(list, env, "पिछले 24 घंटों में कोई ब्रेकिंग खबर नहीं",
        "ब्रेकिंग का निशान इस्तीफा, गिरफ्तारी, दलबदल, टिकट, चुनाव आयोग, अदालत जैसे संकेतों और कई स्रोतों की एक साथ रिपोर्ट से तय होता है — हर खबर में कारण दिखते हैं।")
}

@Composable
fun LeadersScreen(vm: MainViewModel, all: List<Story>, env: ListEnv) {
    val settings by vm.settings.collectAsState()
    val selected by vm.leaderFilter.collectAsState()
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
    StoryList(list, env, "इस नेता की कोई हालिया खबर नहीं") {
        item(key = "leader-head") {
            ChipRow(
                listOf<Pair<String?, String>>(null to if (follows.isEmpty()) "सभी नेता" else "अनुसरित (${follows.size})") +
                    ordered.map { it.id to (if (it.id in follows) "★ " else "") + it.nameHi },
                selected,
            ) { vm.leaderFilter.value = it }
            val sel = leaders.firstOrNull { it.id == selected }
            if (sel != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(listOfNotNull(sel.nameHi, sel.roleHi).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { vm.toggleFollowLeader(sel.id) }) {
                        Text(if (sel.id in follows) "अनुसरण हटाएं" else "अनुसरण करें")
                    }
                }
            } else if (follows.isEmpty()) {
                Notice("किसी नेता को चुनकर “अनुसरण करें” दबाएं — फिर उनकी खबरें यहां एक साथ और सूचनाओं में आएंगी।")
            }
        }
    }
}

@Composable
fun ControversyScreen(all: List<Story>, env: ListEnv) {
    StoryList(all.filter(FeedLogic::isControversy), env, "कोई हालिया राजनीतिक विवाद नहीं", showStatementType = true) {
        item(key = "contro-head") {
            Notice("आरोप और बयान संबंधित व्यक्ति/दल के दावे हैं, सिद्ध तथ्य नहीं। हर खबर में स्रोत और पुष्टि की स्थिति देखें।")
        }
    }
}

@Composable
fun SeatsScreen(vm: MainViewModel, all: List<Story>, env: ListEnv) {
    val selected by vm.seatFilter.collectAsState()
    val ent = vm.repo.collectAsState().value.cache.entities
    val chips = listOf<Pair<String?, String>>(null to "सभी") +
        ent.figures.map { "figure:${it.id}" to it.nameHi } +
        ent.seats.map { "seat:${it.id}" to it.nameHi } +
        ent.districts.filter { it.key }.map { "district:${it.id}" to it.nameHi }
    val list = all.filter { s ->
        val sel = selected
        when {
            sel == null -> FeedLogic.isSeatsAndFigures(s)
            sel.startsWith("figure:") -> sel.removePrefix("figure:") in s.entities.figures
            sel.startsWith("seat:") -> sel.removePrefix("seat:") in s.entities.seats
            else -> sel.removePrefix("district:") in s.entities.districts && FeedLogic.isPolitical(s)
        }
    }
    StoryList(list, env, "इस चयन की कोई हालिया खबर नहीं") {
        item(key = "seats-head") {
            ChipRow(chips, selected) { vm.seatFilter.value = it }
            Notice("कानूनी स्थिति केवल रिपोर्ट के शब्दों के अनुसार दिखाई जाती है। आरोप या मुकदमा दोषसिद्धि नहीं है।")
        }
    }
}

@Composable
fun StatementsScreen(vm: MainViewModel, all: List<Story>, env: ListEnv) {
    val xAvailable = vm.repo.collectAsState().value.cache.xAvailable
    StoryList(all.filter { it.statement }, env, "कोई हालिया बयान नहीं", showStatementType = true) {
        item(key = "stmt-head") {
            if (!xAvailable) {
                Notice("X (ट्विटर) पोस्ट अभी उपलब्ध नहीं हैं — बैकएंड में X API कुंजी सेट नहीं है। नीचे समाचारों में छपे नेताओं के बयान हैं।")
            }
        }
    }
}

@Composable
fun DistrictsScreen(vm: MainViewModel, all: List<Story>, env: ListEnv) {
    val settings by vm.settings.collectAsState()
    val selected by vm.districtFilter.collectAsState()
    val districts = vm.repo.collectAsState().value.cache.entities.districts
    val counts = remember(all) {
        val m = HashMap<String, Int>()
        all.forEach { s -> s.entities.districts.forEach { m[it] = (m[it] ?: 0) + 1 } }
        m
    }
    val ordered = remember(districts, settings.preferredDistricts) {
        districts.sortedWith(compareBy({ if (it.id in settings.preferredDistricts) 0 else if (it.key) 1 else 2 }, { it.nameHi }))
    }
    val sel = selected
    if (sel == null) {
        // District index: pick one.
        val state = rememberLazyListState()
        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
            item {
                Notice("जिला चुनें। ★ = आपके पसंदीदा जिले (सेटिंग्स या नीचे से बदलें)।")
            }
            items(ordered, key = { it.id }) { d ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.districtFilter.value = d.id }.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text((if (d.id in settings.preferredDistricts) "★ " else "") + d.nameHi, style = MaterialTheme.typography.bodyLarge)
                    Text("${counts[d.id] ?: 0} खबरें", style = MaterialTheme.typography.bodySmall, color = LocalNewsColors.current.muted)
                }
                HorizontalDivider(color = LocalNewsColors.current.divider)
            }
        }
        return
    }
    val d = districts.firstOrNull { it.id == sel }
    StoryList(all.filter { sel in it.entities.districts }, env, "${d?.nameHi ?: ""} की कोई हालिया खबर नहीं") {
        item(key = "district-head") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.TextButton(onClick = { vm.districtFilter.value = null }) { Text("← सभी जिले") }
                if (d != null) {
                    OutlinedButton(onClick = { vm.toggleDistrict(d.id) }) {
                        Text(if (d.id in settings.preferredDistricts) "पसंदीदा से हटाएं" else "पसंदीदा बनाएं")
                    }
                }
            }
            Text(d?.nameHi ?: "", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}

@Composable
fun SavedScreen(vm: MainViewModel, env: ListEnv) {
    val saved = vm.repo.collectAsState().value.bookmarks
    StoryList(saved, env, "कोई सहेजी गई खबर नहीं", "किसी खबर पर “सहेजें” दबाएं; वह यहां हमेशा रहेगी।")
}
