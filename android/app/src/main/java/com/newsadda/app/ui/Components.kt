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
import com.newsadda.app.data.model.Report
import com.newsadda.app.data.model.Story
import java.time.Instant

/** id -> Hindi name lookups, built once per feed. */
@Immutable
class Names(e: Entities, val categories: Map<String, String>) {
    val leaders = e.leaders.associate { it.id to it.nameHi }
    val figures = e.figures.associate { it.id to it.nameHi }
    val parties = e.parties.associate { it.id to it.nameHi }
    val districts = e.districts.associate { it.id to it.nameHi }
    val seats = e.seats.associate { it.id to it.nameHi }

    fun entityLine(s: Story): String {
        val parts = mutableListOf<String>()
        s.entities.leaders.take(3).mapNotNullTo(parts) { leaders[it] }
        s.entities.figures.take(2).mapNotNullTo(parts) { figures[it] }
        s.entities.districts.take(2).mapNotNullTo(parts) { districts[it] }
        s.entities.seats.take(1).mapNotNullTo(parts) { seats[it] }
        return parts.distinct().joinToString(" · ")
    }

    fun categoryLine(s: Story): String =
        (s.categoryNames.ifEmpty { s.categories.mapNotNull { categories[it] } }).filter { it != "अन्य" }.take(2).joinToString(", ")
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
    val tags = buildList {
        if (FeedLogic.isBreakingNow(s, now)) add(Triple("ब्रेकिंग", c.breaking, true))
        if (FeedLogic.isRecent(s, now)) add(Triple("नई खबर", c.newDot, false))
        if (s.kind == "post") add(Triple("X पोस्ट", c.muted, false))
        if (showStatementType && s.statementType != null) add(Triple(s.statementType, c.muted, false))
        for (l in s.labels) {
            when (l) {
                "आरोप", "पलटवार" -> add(Triple(l, c.allegation, false))
                "पुष्टि की प्रतीक्षा" -> add(Triple(l, c.caution, false))
                "एकाधिक स्रोतों से रिपोर्ट" -> add(Triple("${s.publisherCount} स्रोतों से रिपोर्ट", c.multi, false))
                else -> add(Triple(l, c.muted, false))
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
                if (lead.via != null) append(" (गूगल न्यूज़)")
                append(" · ")
                val t = lead.displayTime()
                if (lead.publishedAt == null || lead.publishedSuspect) append("देखा ")
                append(TimeFmt.short(t, now))
            }
            val others = s.reports.map { it.publisherName() }.distinct().size - 1
            if (others > 0) append(" · +$others स्रोत")
        }
        Text(meta, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val line = listOf(names.categoryLine(s), names.entityLine(s)).filter { it.isNotBlank() }.joinToString(" · ")
        if (line.isNotBlank()) {
            Text(line, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SmallAction("मूल खबर") { lead?.let { Actions.open(ctx, it.url) } }
            SmallAction(if (saved) "सहेजा गया" else "सहेजें", onSave)
            SmallAction("शेयर") { Actions.share(ctx, s) }
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
