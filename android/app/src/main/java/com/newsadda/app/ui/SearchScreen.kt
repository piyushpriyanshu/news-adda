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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.newsadda.app.data.FeedLogic
import com.newsadda.app.data.model.Entities
import com.newsadda.app.data.model.SourceStatus
import com.newsadda.app.data.model.Story
import java.time.Duration

/** Search runs over stories already on the phone; it never triggers scraping. */
@Composable
fun SearchScreen(all: List<Story>, entities: Entities, sources: List<SourceStatus>, categories: Map<String, String>, env: ListEnv) {
    var q by rememberSaveable { mutableStateOf("") }
    var party by rememberSaveable { mutableStateOf<String?>(null) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var period by rememberSaveable { mutableStateOf<String?>(null) }
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    var who by rememberSaveable { mutableStateOf<String?>(null) }

    val results = all.filter { s ->
        (q.isBlank() || FeedLogic.matchesQuery(s, q)) &&
            (party == null || party in s.entities.parties) &&
            (category == null || category in s.categories || (category == "controversy" && FeedLogic.isControversy(s))) &&
            (source == null || s.reports.any { it.sourceId == source }) &&
            (who == null || who in s.entities.leaders || who in s.entities.figures || who in s.entities.districts || who in s.entities.seats) &&
            (period == null || run {
                val t = FeedLogic.instant(s.sortAt) ?: return@run false
                val hours = Duration.between(t, env.now).toHours()
                when (period) {
                    "today" -> t.atZone(com.newsadda.app.data.TimeFmt.IST).toLocalDate() == env.now.atZone(com.newsadda.app.data.TimeFmt.IST).toLocalDate()
                    "24h" -> hours < 24
                    "3d" -> hours < 72
                    else -> true
                }
            })
    }
    val activeSources = sources.filter { it.status == "ok" || it.totalItems > 0 }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = q, onValueChange = { q = it }, singleLine = true,
            placeholder = { Text("नेता, दल, जिला, शब्द… (हिंदी या English)") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )
        StoryList(
            results, env,
            if (q.isBlank() && listOf(party, category, period, source, who).all { it == null }) "खोजने के लिए लिखें या फ़िल्टर चुनें" else "कोई परिणाम नहीं",
            "खोज फ़ोन पर सहेजी गई पिछले 3 दिनों की खबरों में होती है।",
        ) {
            item(key = "filters") {
                ChipRow(listOf<Pair<String?, String>>(null to "कभी भी", "today" to "आज", "24h" to "24 घंटे", "3d" to "3 दिन"), period) { period = it }
                ChipRow(listOf<Pair<String?, String>>(null to "सभी दल") + entities.parties.map { it.id to it.nameHi }, party) { party = it }
                ChipRow(listOf<Pair<String?, String>>(null to "सभी श्रेणियां") +
                    categories.filterKeys { it != "other" }.map { (k, v) -> k to v }, category) { category = it }
                ChipRow(listOf<Pair<String?, String>>(null to "सभी नेता/जिले") +
                    entities.leaders.map { it.id to it.nameHi } + entities.figures.map { it.id to it.nameHi } +
                    entities.seats.map { it.id to it.nameHi } + entities.districts.map { it.id to it.nameHi }, who) { who = it }
                ChipRow(listOf<Pair<String?, String>>(null to "सभी स्रोत") + activeSources.map { it.id to it.nameHi }, source) { source = it }
                SectionLabel("${results.size} परिणाम")
            }
        }
    }
}
