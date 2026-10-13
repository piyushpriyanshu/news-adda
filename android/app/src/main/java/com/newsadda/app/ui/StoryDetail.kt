package com.newsadda.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newsadda.app.data.FeedLogic
import com.newsadda.app.data.TimeFmt
import com.newsadda.app.data.model.Report
import com.newsadda.app.data.model.Story
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StoryDetailSheet(s: Story, names: Names, saved: Boolean, onSave: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalNewsColors.current
    val str = LocalStr.current
    val now = Instant.now()
    val lead = s.reports.firstOrNull()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.card,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SourceLine(lead, now, isNew = false, recent = FeedLogic.isRecent(s, now))
            StoryTags(s, now, showStatementType = true)
            Text(s.title, style = MaterialTheme.typography.titleLarge)
            if (s.summary != null) {
                Text(s.summary, style = MaterialTheme.typography.bodyLarge)
                Text(when (s.summaryOrigin) { "ai" -> str.summaryAi; "page" -> str.summaryPage; else -> str.summaryPublisher },
                    style = MaterialTheme.typography.bodySmall, color = c.faint)
            } else {
                Text(str.headlineOnly, style = MaterialTheme.typography.bodyMedium, color = c.muted)
            }

            // Primary actions right under the story, where a journalist needs them.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { lead?.let { Actions.open(ctx, it.url, str) } }, contentPadding = PaddingValues(horizontal = 18.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(str.readMore)
                }
                FilledTonalIconButton(onClick = onSave) {
                    Icon(if (saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = if (saved) str.actSaved else str.actSave)
                }
                FilledTonalIconButton(onClick = { Actions.copy(ctx, s, str) }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = str.copyLinks)
                }
                FilledTonalIconButton(onClick = { Actions.share(ctx, s, str) }) {
                    Icon(Icons.Outlined.Share, contentDescription = str.shareStory)
                }
            }

            if (s.corroboration == "single") Callout(str.singleSource, c.caution, c.cautionBg)
            if ("allegation" in s.labels) Callout(str.allegationNote, c.allegation, c.allegationBg)
            if (s.legalStatus.isNotEmpty()) {
                Callout(str.legalMentioned + s.legalStatus.joinToString(", ") { str.legal(it) } + "\n" + str.legalNote,
                    MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.surfaceVariant)
            }

            val chips = names.people(s) + s.entities.parties.mapNotNull { names.parties[it] } +
                s.categories.filter { it != "other" }.map { str.category(it) }
            if (chips.isNotEmpty()) {
                Label(str.related)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    chips.distinct().forEach { Pill(it, MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceVariant) }
                }
            }
            if (s.reasons.isNotEmpty()) {
                Label(str.priorityReasons)
                Text(s.reasons.joinToString(" · ") { str.reason(it, names::any) }, style = MaterialTheme.typography.bodyMedium)
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Label(str.coverage)
                if (s.publishers().size > 1) StackedSources(s, max = 6, size = 20.dp)
                Text(str.sourcesHeader(s.reports.size, s.publisherCount), style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp)) {
                Column {
                    s.reports.forEachIndexed { i, r ->
                        if (i > 0) HorizontalDivider(color = c.divider)
                        ReportRow(r)
                    }
                }
            }
            Text(str.firstSeen(TimeFmt.full(FeedLogic.instant(s.firstSeenAt), str)),
                style = MaterialTheme.typography.bodySmall, color = c.faint)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Label(t: String) {
    Text(t.uppercase(), style = MaterialTheme.typography.labelSmall, color = LocalNewsColors.current.faint,
        modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun ReportRow(r: Report) {
    val ctx = LocalContext.current
    val c = LocalNewsColors.current
    val str = LocalStr.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SourceIcon(r.domain, r.publisherName(), 22.dp)
            val head = buildString {
                append(r.publisherName())
                if (r.handle != null && r.publisher?.contains("@") != true) append(" (@${r.handle})")
            }
            Text(head, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { Actions.open(ctx, r.url, str) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if (r.kind == "post") str.openPost else str.open)
            }
        }
        if (r.via != null) Text(str.via(r.via).removePrefix(" — "), style = MaterialTheme.typography.bodySmall, color = c.faint)
        Text(r.title, style = MaterialTheme.typography.bodyMedium)
        when (r.postContext) {
            "quote" -> Text(str.quotePost, style = MaterialTheme.typography.bodySmall, color = c.muted)
            "reply" -> Text(str.replyPost, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        val pub = FeedLogic.instant(r.publishedAt)
        val times = buildList {
            when {
                pub == null -> add(str.pubNotGiven)
                r.publishedSuspect -> add(str.pubSuspect(TimeFmt.full(pub, str)))
                r.tzAssumed -> add(str.pubAssumed(TimeFmt.full(pub, str)))
                else -> add(str.published(TimeFmt.full(pub, str)))
            }
            FeedLogic.instant(r.updatedAt)?.let { add(str.updated(TimeFmt.full(it, str))) }
            add(str.seenByApp(TimeFmt.full(FeedLogic.instant(r.firstSeenAt), str)))
        }
        times.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted) }
    }
}
