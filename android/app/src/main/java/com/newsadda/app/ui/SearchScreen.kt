package com.newsadda.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.newsadda.app.data.FeedLogic
import com.newsadda.app.data.TimeFmt
import com.newsadda.app.data.model.Entities
import com.newsadda.app.data.model.SourceStatus
import com.newsadda.app.data.model.Story
import java.time.Duration

private val CATEGORY_CODES = listOf("politics", "controversy", "election", "legal", "administration", "protest", "crime")

/** Search runs over stories already on the phone; it never triggers scraping. */
@Composable
fun SearchScreen(all: List<Story>, entities: Entities, sources: List<SourceStatus>, env: ListEnv) {
    val str = LocalStr.current
    var q by rememberSaveable { mutableStateOf("") }
    var party by rememberSaveable { mutableStateOf<String?>(null) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var period by rememberSaveable { mutableStateOf<String?>(null) }
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    var who by rememberSaveable { mutableStateOf<String?>(null) }

    // English entity names let "yogi" or "lucknow" find Hindi headlines.
    val extras = remember(all, entities) { all.associate { it.id to env.names.searchExtra(it, entities) } }
    val results = all.filter { s ->
        (q.isBlank() || FeedLogic.matchesQuery(s, q, extras[s.id] ?: "")) &&
            (party == null || party in s.entities.parties) &&
            (category == null || category in s.categories || (category == "controversy" && FeedLogic.isControversy(s))) &&
            (source == null || s.reports.any { it.sourceId == source }) &&
            (who == null || who in s.entities.leaders || who in s.entities.figures || who in s.entities.districts || who in s.entities.seats) &&
            (period == null || run {
                val t = FeedLogic.instant(s.sortAt) ?: return@run false
                val hours = Duration.between(t, env.now).toHours()
                when (period) {
                    "today" -> t.atZone(TimeFmt.IST).toLocalDate() == env.now.atZone(TimeFmt.IST).toLocalDate()
                    "24h" -> hours < 24
                    "3d" -> hours < 72
                    else -> true
                }
            })
    }
    val activeSources = sources.filter { it.status == "ok" || it.totalItems > 0 }
    val noFilter = q.isBlank() && listOf(party, category, period, source, who).all { it == null }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = q, onValueChange = { q = it }, singleLine = true,
            placeholder = { Text(str.searchPlaceholder) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )
        StoryList(results, env, if (noFilter) str.searchStart else str.noResults, str.searchScope) {
            item(key = "filters") {
                ChipRow(listOf<Pair<String?, String>>(null to str.anyTime, "today" to str.today, "24h" to str.last24h, "3d" to str.last3d), period) { period = it }
                ChipRow(listOf<Pair<String?, String>>(null to str.allParties) + entities.parties.map { it.id to env.names.of(it) }, party) { party = it }
                ChipRow(listOf<Pair<String?, String>>(null to str.allCategories) + CATEGORY_CODES.map { it to str.category(it) }, category) { category = it }
                ChipRow(listOf<Pair<String?, String>>(null to str.allPeoplePlaces) +
                    (entities.leaders + entities.figures + entities.seats + entities.districts).map { it.id to env.names.of(it) }, who) { who = it }
                ChipRow(listOf<Pair<String?, String>>(null to str.allSources) +
                    activeSources.map { it.id to (if (str.hindi) it.nameHi else it.name).ifBlank { it.id } }, source) { source = it }
                SectionLabel(str.results(results.size))
            }
        }
    }
}
