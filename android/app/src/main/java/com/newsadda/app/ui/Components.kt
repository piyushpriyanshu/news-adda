package com.newsadda.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newsadda.app.data.FeedLogic
import com.newsadda.app.data.TimeFmt
import com.newsadda.app.data.model.Entities
import com.newsadda.app.data.model.Entity
import com.newsadda.app.data.model.Report
import com.newsadda.app.data.model.Story
import java.time.Instant

/** id -> display name lookups in the interface language, built once per feed. */
@Immutable
class Names(e: Entities, val str: Str) {
    private fun Entity.label() = if (str.hindi) nameHi else (name ?: nameHi)
    val leaders = e.leaders.associate { it.id to it.label() }
    val figures = e.figures.associate { it.id to it.label() }
    val parties = e.parties.associate { it.id to it.label() }
    val districts = e.districts.associate { it.id to it.label() }
    val seats = e.seats.associate { it.id to it.label() }

    fun any(id: String): String = leaders[id] ?: figures[id] ?: districts[id] ?: seats[id] ?: parties[id] ?: id
    fun of(e: Entity) = e.label()

    fun entityLine(s: Story): String {
        val parts = mutableListOf<String>()
        s.entities.leaders.take(3).mapNotNullTo(parts) { leaders[it] }
        s.entities.figures.take(2).mapNotNullTo(parts) { figures[it] }
        s.entities.districts.take(2).mapNotNullTo(parts) { districts[it] }
        s.entities.seats.take(1).mapNotNullTo(parts) { seats[it] }
        return parts.distinct().joinToString(" · ")
    }

    fun categoryLine(s: Story): String =
        s.categories.filter { it != "other" }.take(2).joinToString(", ") { str.category(it) }

    /** Words that let English searches find Hindi stories ("yogi", "lucknow"). */
    fun searchExtra(s: Story, e: Entities): String {
        val ids = s.entities.leaders + s.entities.figures + s.entities.districts + s.entities.seats + s.entities.parties
        val all = e.leaders + e.figures + e.districts + e.seats + e.parties
        return all.filter { it.id in ids }.joinToString(" ") { "${it.name ?: ""} ${it.nameHi}" }
    }
}

fun Report.displayTime(): Instant? =
    if (publishedSuspect) FeedLogic.instant(firstSeenAt) else FeedLogic.instant(publishedAt) ?: FeedLogic.instant(firstSeenAt)

fun Report.publisherName(): String = publisher ?: sourceName

@Composable
fun Tag(text: String, color: Color, filled: Boolean = false) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .then(if (filled) Modifier.background(color, shape) else Modifier.border(BorderStroke(1.dp, color.copy(alpha = 0.7f)), shape))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = if (filled) LocalNewsColors.current.onBreaking else color)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StoryTags(s: Story, now: Instant, showStatementType: Boolean = false) {
    val c = LocalNewsColors.current
    val str = LocalStr.current
    val tags = buildList {
        if (FeedLogic.isBreakingNow(s, now)) add(Triple(str.tagBreaking, c.breaking, true))
        if (FeedLogic.isRecent(s, now)) add(Triple(str.tagNew, c.newDot, false))
        if (s.kind == "post") add(Triple(str.tagXPost, c.muted, false))
        if (showStatementType && s.statementType != null) add(Triple(str.statementType(s.statementType), c.muted, false))
        for (l in s.labels) {
            when (l) {
                "allegation", "counter" -> add(Triple(str.label(l), c.allegation, false))
                "awaiting_confirmation" -> add(Triple(str.label(l), c.caution, false))
                "multi_source" -> add(Triple(str.multiSource(s.publisherCount), c.multi, false))
                else -> add(Triple(str.label(l), c.muted, false))
            }
        }
    }
    if (tags.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        tags.forEach { (t, col, filled) -> Tag(t, col, filled) }
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
fun StoryCard(
    s: Story,
    names: Names,
    isNew: Boolean,
    saved: Boolean,
    now: Instant,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    showStatementType: Boolean = false,
) {
    val ctx = LocalContext.current
    val c = LocalNewsColors.current
    val str = LocalStr.current
    val lead = s.reports.firstOrNull()
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp)
    ) {
        StoryTags(s, now, showStatementType)
        Row(verticalAlignment = Alignment.Top) {
            if (isNew) {
                Box(Modifier.padding(top = 9.dp, end = 6.dp).size(7.dp).background(c.newDot, CircleShape))
            }
            Text(s.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        if (s.summary != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                s.summary, style = MaterialTheme.typography.bodyMedium, color = c.muted,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
        val meta = buildString {
            if (lead != null) {
                append(lead.publisherName())
                if (lead.via != null) append(str.viaGoogle)
                append(" · ")
                val t = lead.displayTime()
                if (lead.publishedAt == null || lead.publishedSuspect) append(str.seenPrefix)
                append(TimeFmt.short(t, now, str))
            }
            val others = s.reports.map { it.publisherName() }.distinct().size - 1
            if (others > 0) append(" · " + str.moreSources(others))
        }
        Text(meta, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val line = listOf(names.categoryLine(s), names.entityLine(s)).filter { it.isNotBlank() }.joinToString(" · ")
        if (line.isNotBlank()) {
            Text(line, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SmallAction(str.actOriginal) { lead?.let { Actions.open(ctx, it.url, str) } }
            SmallAction(if (saved) str.actSaved else str.actSave, onSave)
            SmallAction(str.actShare) { Actions.share(ctx, s, str) }
        }
        HorizontalDivider(color = c.divider)
    }
}

@Composable
fun SmallAction(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun ChipRow(items: List<Pair<String?, String>>, selected: String?, onSelect: (String?) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { (id, label) ->
            FilterChip(selected = selected == id, onClick = { onSelect(id) }, label = { Text(label) })
        }
    }
}

@Composable
fun Notice(text: String, color: Color = LocalNewsColors.current.muted, action: Pair<String, () -> Unit>? = null) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.weight(1f))
            if (action != null) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = action.second) { Text(action.first) }
            }
        }
    }
}

@Composable
fun EmptyState(title: String, body: String? = null) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (body != null) {
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = LocalNewsColors.current.muted)
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = LocalNewsColors.current.muted,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
    )
}
