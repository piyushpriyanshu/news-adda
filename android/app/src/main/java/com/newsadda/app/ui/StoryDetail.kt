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
                    if (s.summaryOrigin == "ai") "सारांश: प्रकाशक के विवरण से AI द्वारा हिंदी में संक्षेप"
                    else "सारांश: प्रकाशक के फ़ीड विवरण से",
                    style = MaterialTheme.typography.bodySmall, color = c.muted,
                )
            } else {
                Text("केवल शीर्षक उपलब्ध — पूरी जानकारी के लिए मूल खबर खोलें।", style = MaterialTheme.typography.bodyMedium, color = c.muted)
            }

            if (s.corroboration == "single") {
                Spacer(Modifier.height(10.dp))
                Text(
                    "अभी केवल एक स्रोत ने रिपोर्ट किया है; स्वतंत्र पुष्टि उपलब्ध नहीं।",
                    style = MaterialTheme.typography.bodyMedium, color = c.caution,
                )
            }
            if ("आरोप" in s.labels) {
                Text(
                    "इसमें आरोप शामिल हैं। आरोप सिद्ध तथ्य नहीं है; बयान उसी व्यक्ति/संगठन का है जिसने आरोप लगाया।",
                    style = MaterialTheme.typography.bodyMedium, color = c.allegation,
                )
            }
            if (s.legalStatus.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "रिपोर्ट में उल्लेख: " + s.legalStatus.joinToString(", "),
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                )
                Text(
                    "यह स्रोत के शब्दों पर आधारित है। आरोप, एफआईआर, गिरफ्तारी या जांच दोषसिद्धि नहीं है; अंतिम स्थिति अदालत तय करती है।",
                    style = MaterialTheme.typography.bodySmall, color = c.muted,
                )
            }

            val who = names.entityLine(s)
            if (who.isNotBlank() || s.entities.parties.isNotEmpty()) {
                SectionLabelInline("संबंधित")
                val parties = s.entities.parties.mapNotNull { names.parties[it] }.joinToString(", ")
                Text(listOf(who, parties, names.categoryLine(s)).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (s.reasons.isNotEmpty()) {
                SectionLabelInline("प्राथमिकता के कारण")
                Text(s.reasons.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            }

            SectionLabelInline("स्रोत (${s.reports.size} रिपोर्ट, ${s.publisherCount} प्रकाशक)")
            s.reports.forEach { r -> ReportRow(r) }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { s.reports.firstOrNull()?.let { Actions.open(ctx, it.url) } }) { Text("मूल खबर खोलें") }
                OutlinedButton(onClick = onSave) { Text(if (saved) "सहेजा गया" else "सहेजें") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { Actions.copy(ctx, s) }) { Text("शीर्षक व लिंक कॉपी करें") }
                TextButton(onClick = { Actions.share(ctx, s) }) { Text("शेयर करें") }
            }
            Text(
                "पहली बार देखा: ${TimeFmt.full(FeedLogic.instant(s.firstSeenAt))}",
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
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        val head = buildString {
            append(r.publisherName())
            if (r.handle != null && r.publisher?.contains("@") != true) append(" (@${r.handle})")
            if (r.via != null) append(" — ${r.via} के माध्यम से")
        }
        Text(head, style = MaterialTheme.typography.labelLarge)
        if (r.kind == "post") {
            Text(r.title, style = MaterialTheme.typography.bodyMedium)
            when (r.postContext) {
                "quote" -> Text("यह पोस्ट किसी और की पोस्ट को उद्धृत करती है", style = MaterialTheme.typography.bodySmall, color = c.muted)
                "reply" -> Text("यह किसी पोस्ट का जवाब है", style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
        } else {
            Text(r.title, style = MaterialTheme.typography.bodyMedium)
        }
        val times = buildList {
            val pub = FeedLogic.instant(r.publishedAt)
            when {
                pub == null -> add("प्रकाशन समय: स्रोत ने नहीं दिया")
                r.publishedSuspect -> add("प्रकाशन समय (स्रोत के अनुसार, संदिग्ध): ${TimeFmt.full(pub)}")
                r.tzAssumed -> add("प्रकाशित: ${TimeFmt.full(pub)} (समय-क्षेत्र IST माना गया)")
                else -> add("प्रकाशित: ${TimeFmt.full(pub)}")
            }
            FeedLogic.instant(r.updatedAt)?.let { add("अपडेट: ${TimeFmt.full(it)}") }
            add("न्यूज़ अड्डा ने देखा: ${TimeFmt.full(FeedLogic.instant(r.firstSeenAt))}")
        }
        times.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted) }
        TextButton(onClick = { Actions.open(ctx, r.url) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text(if (r.kind == "post") "पोस्ट खोलें" else "यह रिपोर्ट खोलें")
        }
        HorizontalDivider(color = c.divider)
    }
}
