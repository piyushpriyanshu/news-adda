package com.newsadda.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newsadda.app.data.FeedLogic
import com.newsadda.app.data.TimeFmt
import com.newsadda.app.data.model.Report
import com.newsadda.app.data.model.Story
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryDetailSheet(s: Story, names: Names, saved: Boolean, onSave: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalNewsColors.current
    val str = LocalStr.current
    val now = Instant.now()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            StoryTags(s, now, showStatementType = true)
            Text(s.title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            if (s.summary != null) {
                Text(s.summary, style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (s.summaryOrigin == "ai") str.summaryAi else str.summaryPublisher,
                    style = MaterialTheme.typography.bodySmall, color = c.muted,
                )
            } else {
                Text(str.headlineOnly, style = MaterialTheme.typography.bodyMedium, color = c.muted)
            }

            if (s.corroboration == "single") {
                Spacer(Modifier.height(10.dp))
                Text(
                    str.singleSource,
                    style = MaterialTheme.typography.bodyMedium, color = c.caution,
                )
            }
            if ("allegation" in s.labels) {
                Text(
                    str.allegationNote,
                    style = MaterialTheme.typography.bodyMedium, color = c.allegation,
                )
            }
            if (s.legalStatus.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    str.legalMentioned + s.legalStatus.joinToString(", ") { str.legal(it) },
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                )
                Text(
                    str.legalNote,
                    style = MaterialTheme.typography.bodySmall, color = c.muted,
                )
            }

            val who = names.entityLine(s)
            if (who.isNotBlank() || s.entities.parties.isNotEmpty()) {
                SectionLabelInline(str.related)
                val parties = s.entities.parties.mapNotNull { names.parties[it] }.joinToString(", ")
                Text(listOf(who, parties, names.categoryLine(s)).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (s.reasons.isNotEmpty()) {
                SectionLabelInline(str.priorityReasons)
                Text(s.reasons.joinToString(" · ") { str.reason(it, names::any) }, style = MaterialTheme.typography.bodyMedium)
            }

            SectionLabelInline(str.sourcesHeader(s.reports.size, s.publisherCount))
            s.reports.forEach { r -> ReportRow(r) }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { s.reports.firstOrNull()?.let { Actions.open(ctx, it.url, str) } }) { Text(str.openOriginal) }
                OutlinedButton(onClick = onSave) { Text(if (saved) str.actSaved else str.actSave) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { Actions.copy(ctx, s, str) }) { Text(str.copyLinks) }
                TextButton(onClick = { Actions.share(ctx, s, str) }) { Text(str.shareStory) }
            }
            Text(
                str.firstSeen(TimeFmt.full(FeedLogic.instant(s.firstSeenAt), str)),
                style = MaterialTheme.typography.bodySmall, color = c.muted,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SectionLabelInline(t: String) {
    Spacer(Modifier.height(14.dp))
    Text(t, style = MaterialTheme.typography.labelLarge, color = LocalNewsColors.current.muted)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun ReportRow(r: Report) {
    val ctx = LocalContext.current
    val c = LocalNewsColors.current
    val str = LocalStr.current
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        val head = buildString {
            append(r.publisherName())
            if (r.handle != null && r.publisher?.contains("@") != true) append(" (@${r.handle})")
            if (r.via != null) append(str.via(r.via))
        }
        Text(head, style = MaterialTheme.typography.labelLarge)
        if (r.kind == "post") {
            Text(r.title, style = MaterialTheme.typography.bodyMedium)
            when (r.postContext) {
                "quote" -> Text(str.quotePost, style = MaterialTheme.typography.bodySmall, color = c.muted)
                "reply" -> Text(str.replyPost, style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
        } else {
            Text(r.title, style = MaterialTheme.typography.bodyMedium)
        }
        val times = buildList {
            val pub = FeedLogic.instant(r.publishedAt)
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
        TextButton(onClick = { Actions.open(ctx, r.url, str) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text(if (r.kind == "post") str.openPost else str.openReport)
        }
        HorizontalDivider(color = c.divider)
    }
}
