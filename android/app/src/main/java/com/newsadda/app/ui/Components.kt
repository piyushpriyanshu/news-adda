package com.newsadda.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
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
    /** Hindi names of leaders, regional figures and parties (to spot political raw headlines). */
    val politicalHi: List<String> = (e.leaders + e.figures + e.parties).map { it.nameHi }

    fun any(id: String): String = leaders[id] ?: figures[id] ?: districts[id] ?: seats[id] ?: parties[id] ?: id
    fun of(e: Entity) = e.label()

    /** Leaders, figures, districts, seats named in the story (for chips). */
    fun people(s: Story): List<String> {
        val parts = mutableListOf<String>()
        s.entities.leaders.take(2).mapNotNullTo(parts) { leaders[it] }
        s.entities.figures.take(1).mapNotNullTo(parts) { figures[it] }
        s.entities.districts.take(1).mapNotNullTo(parts) { districts[it] }
        if (parts.size < 3) s.entities.seats.take(1).mapNotNullTo(parts) { seats[it] }
        return parts.distinct()
    }

    fun entityLine(s: Story): String = people(s).joinToString(" · ")

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

/** One entry per publisher, in order of first report. */
fun Story.publishers(): List<Report> = reports.distinctBy { it.publisherName().lowercase() }

// ---------------------------------------------------------------- source icons

private val MONO_COLORS = listOf(
    Color(0xFF8E2C48), Color(0xFF1F5F8B), Color(0xFF2F6B3B), Color(0xFF7A4E12),
    Color(0xFF4B3C8F), Color(0xFF0F6E6E), Color(0xFF8A3B12), Color(0xFF4A5560),
)

/**
 * The publisher's own site icon (favicon), so a source is recognisable at a
 * glance. While it loads, or if it cannot be fetched, a lettered badge shows.
 */
@Composable
fun SourceIcon(domain: String?, name: String, size: Dp = 20.dp, ring: Color? = null) {
    val ctx = LocalContext.current
    var failed by remember(domain) { mutableStateOf(domain.isNullOrBlank()) }
    val shape = CircleShape
    Box(
        Modifier
            .size(size)
            .then(if (ring != null) Modifier.border(1.5.dp, ring, shape) else Modifier)
            .clip(shape)
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        val letters = name.trim().split(' ', '-').filter { it.isNotBlank() }.take(2)
            .joinToString("") { it.take(1) }.uppercase().ifBlank { "•" }
        val bg = MONO_COLORS[(name.lowercase().hashCode() and 0x7fffffff) % MONO_COLORS.size]
        Box(Modifier.size(size).background(bg), contentAlignment = Alignment.Center) {
            Text(letters, color = Color.White, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        if (!failed) {
            AsyncImage(
                model = ImageRequest.Builder(ctx)
                    .data("https://www.google.com/s2/favicons?domain=$domain&sz=64")
                    .crossfade(false)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier.size(size).background(Color.White).padding(size * 0.08f),
            )
        }
    }
}

/** Overlapping icons of the publishers that reported a story. */
@Composable
fun StackedSources(story: Story, max: Int = 4, size: Dp = 20.dp) {
    val pubs = story.publishers()
    val ring = LocalNewsColors.current.card
    Row(verticalAlignment = Alignment.CenterVertically) {
        pubs.take(max).forEachIndexed { i, r ->
            Box(Modifier.offset(x = (-6 * i).dp)) { SourceIcon(r.domain, r.publisherName(), size, ring) }
        }
    }
}

// ---------------------------------------------------------------- pills & labels

@Composable
fun Pill(text: String, fg: Color, bg: Color, modifier: Modifier = Modifier) {
    Box(modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1)
    }
}

@Composable
fun LiveDot(color: Color = LocalNewsColors.current.live, size: Dp = 8.dp) {
    val t = rememberInfiniteTransition(label = "live")
    val a by t.animateFloat(1f, 0.25f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(size).alpha(a).background(color, CircleShape))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StoryTags(s: Story, now: Instant, showStatementType: Boolean = false) {
    val c = LocalNewsColors.current
    val str = LocalStr.current
    val pills = buildList<@Composable () -> Unit> {
        if (FeedLogic.isBreakingNow(s, now)) add { Pill(str.tagBreaking.uppercase(), c.onBreaking, c.breaking) }
        if (s.kind == "post") add { Pill(str.tagXPost, c.muted, c.divider) }
        val st = s.statementType
        // Skip the statement type when it would only repeat the Allegation/Counter-attack label.
        val repeats = (st == "allegation" && "allegation" in s.labels) || (st == "response" && "counter" in s.labels)
        if (showStatementType && st != null && !repeats) add { Pill(str.statementType(st), c.muted, c.divider) }
        for (l in s.labels) when (l) {
            "allegation", "counter" -> add { Pill(str.label(l), c.allegation, c.allegationBg) }
            "awaiting_confirmation" -> add { Pill(str.label(l), c.caution, c.cautionBg) }
            "official" -> add { Pill(str.label(l), c.official, c.officialBg) }
            "multi_source" -> {}
            else -> add { Pill(str.label(l), c.muted, c.divider) }
        }
    }
    if (pills.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        pills.forEach { it() }
    }
}

// ---------------------------------------------------------------- story card

@Composable
fun SourceLine(lead: Report?, now: Instant, isNew: Boolean, recent: Boolean) {
    val c = LocalNewsColors.current
    val str = LocalStr.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (lead != null) SourceIcon(lead.domain, lead.publisherName(), 22.dp)
        Text(
            lead?.publisherName() ?: "", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        val t = lead?.displayTime()
        Text("· " + TimeFmt.ago(t, now, str), style = MaterialTheme.typography.bodySmall,
            color = if (recent) c.newDot else c.muted, maxLines = 1)
        if (isNew) Box(Modifier.size(7.dp).background(c.newDot, CircleShape))
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
    val breakingNow = FeedLogic.isBreakingNow(s, now)
    Surface(
        color = c.card,
        shape = RoundedCornerShape(16.dp),
        border = if (breakingNow) BorderStroke(1.dp, c.breaking.copy(alpha = 0.45f)) else null,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Column(Modifier.clickable(onClick = onOpen).padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 4.dp)) {
            Box(Modifier.padding(end = 8.dp)) { SourceLine(lead, now, isNew, FeedLogic.isRecent(s, now)) }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StoryTags(s, now, showStatementType)
                Text(s.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                if (s.summary != null) {
                    Text(s.summary, style = MaterialTheme.typography.bodyMedium, color = c.muted,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                val chips = names.people(s)
                if (chips.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        chips.forEach { Pill(it, MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceVariant) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val pubs = s.publishers()
                if (pubs.size > 1) {
                    StackedSources(s, size = 18.dp)
                    Text(str.sources(pubs.size), style = MaterialTheme.typography.labelMedium, color = c.multi,
                        modifier = Modifier.padding(start = 2.dp))
                } else {
                    Text(names.categoryLine(s), style = MaterialTheme.typography.bodySmall, color = c.faint, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { lead?.let { Actions.open(ctx, it.url, str) } }) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = str.actOriginal, tint = c.muted, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onSave) {
                    Icon(if (saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = if (saved) str.actSaved else str.actSave,
                        tint = if (saved) MaterialTheme.colorScheme.primary else c.muted, modifier = Modifier.size(21.dp))
                }
                IconButton(onClick = { Actions.share(ctx, s, str) }) {
                    Icon(Icons.Outlined.Share, contentDescription = str.actShare, tint = c.muted, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** Red card used in the "Breaking now" carousel. */
@Composable
fun BreakingCard(s: Story, now: Instant, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalNewsColors.current
    val str = LocalStr.current
    val lead = s.reports.firstOrNull()
    Surface(color = c.breaking, shape = RoundedCornerShape(18.dp), modifier = modifier) {
        Column(Modifier.clickable(onClick = onOpen).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LiveDot(Color.White, 7.dp)
                Text(str.breakingAlert, style = MaterialTheme.typography.labelSmall, color = Color.White)
                Text("· " + TimeFmt.ago(FeedLogic.instant(s.firstSeenAt), now, str), style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f))
            }
            Text(s.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = Color.White,
                maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(88.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StackedSources(s, size = 18.dp)
                val n = s.publishers().size
                Text(if (n > 1) str.sources(n) else (lead?.publisherName() ?: ""), style = MaterialTheme.typography.labelMedium,
                    color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------------------------------------------------------- shared bits

@Composable
fun SmallAction(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

/** Horizontal pill selector used for section navigation and filters. */
@Composable
fun ChipRow(items: List<Pair<String?, String>>, selected: String?, onSelect: (String?) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { (id, label) -> PillChip(label, selected == id) { onSelect(id) } }
    }
}

@Composable
fun PillChip(label: String, selected: Boolean, badge: Int = 0, onClick: () -> Unit) {
    val c = LocalNewsColors.current
    val bg = if (selected) MaterialTheme.colorScheme.onSurface else c.card
    val fg = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .then(if (selected) Modifier else Modifier.border(1.dp, c.divider, RoundedCornerShape(50)))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
        if (badge > 0) {
            Box(Modifier.background(c.breaking, RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 1.dp)) {
                Text("$badge", style = MaterialTheme.typography.labelSmall, color = c.onBreaking)
            }
        }
    }
}

@Composable
fun Notice(text: String, color: Color = LocalNewsColors.current.muted, action: Pair<String, () -> Unit>? = null) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.weight(1f))
            if (action != null) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = action.second) { Text(action.first) }
            }
        }
    }
}

/**
 * "N new stories" bar. The whole bar is one tap target: it jumps to the new
 * stories and then goes away by itself (no separate "Mark seen" button).
 */
@Composable
fun NewStoriesBar(text: String, action: String, onClick: () -> Unit) {
    val c = LocalNewsColors.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = action, onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(c.newDot, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = c.newDot,
                modifier = Modifier.weight(1f))
            Text(action, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = c.newDot)
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = c.newDot, modifier = Modifier.size(20.dp))
        }
    }
}

/** A soft callout box inside a list or sheet. */
@Composable
fun Callout(text: String, fg: Color, bg: Color) {
    Box(Modifier.fillMaxWidth().background(bg, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = fg)
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
fun SectionLabel(text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) { SectionTag(text) }
        trailing?.invoke()
    }
}

/** Slanted (rhombus-sided) shape for section tags, like a TV news strap. */
private val SlantShape = GenericShape { size, _ ->
    val slant = size.height * 0.32f
    moveTo(slant, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width - slant, size.height)
    lineTo(0f, size.height)
    close()
}

/** A section title in white on the app's red, in a slanted tag exactly as wide as the text. */
@Composable
fun SectionTag(text: String) {
    val c = LocalNewsColors.current
    Box(
        Modifier
            .background(c.breaking, SlantShape)
            .padding(horizontal = 16.dp, vertical = 5.dp),
    ) {
        Text(text.uppercase(), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp),
            color = c.onBreaking, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlowChips(content: @Composable () -> Unit) {
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}
