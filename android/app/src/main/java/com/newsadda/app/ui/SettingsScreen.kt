package com.newsadda.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newsadda.app.BuildConfig
import com.newsadda.app.data.FeedLogic
import com.newsadda.app.data.Prefs
import com.newsadda.app.data.TimeFmt
import com.newsadda.app.update.UpdateManager
import com.newsadda.app.work.Notifier
import java.time.Instant

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val s by vm.settings.collectAsState()
    val repo by vm.repo.collectAsState()
    val up by vm.updates.collectAsState()
    val c = LocalNewsColors.current
    val cache = repo.cache
    var notifGranted by remember { mutableStateOf(Notifier.allowed(ctx)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifGranted = it }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        // ---- refresh
        Header("खबरें अपडेट करने का अंतराल")
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Prefs.REFRESH_OPTIONS.forEach { m ->
                FilterChip(selected = s.refreshMinutes == m, onClick = { vm.updateSettings { it.copy(refreshMinutes = m) } },
                    label = { Text("$m मिनट") })
            }
        }
        Hint("ऐप खुला रहने पर हर ${s.refreshMinutes} मिनट में, ऐप खोलने और वापस आने पर तुरंत अपडेट होता है। " +
            "खबरें सर्वर (GitHub Actions) पर लगभग हर 5–15 मिनट में इकट्ठा होती हैं, इसलिए 1 मिनट चुनने पर भी नई खबरें उसी गति से आएंगी।")
        Hint("अंतिम सफल अपडेट: ${TimeFmt.full(Instant.ofEpochMilli(repo.lastSuccessMs).takeIf { repo.lastSuccessMs > 0 })} · " +
            "सर्वर फ़ीड बनी: ${TimeFmt.full(FeedLogic.instant(cache.generatedAt))}")

        // ---- notifications
        Header("ब्रेकिंग न्यूज़ सूचनाएं")
        ToggleRow("सूचनाएं चालू", s.newsNotifications) { v -> vm.updateSettings { it.copy(newsNotifications = v) } }
        ToggleRow("सभी ब्रेकिंग खबरें (बंद = केवल अनुसरित नेता/पसंदीदा जिले)", s.notifyAllBreaking) { v ->
            vm.updateSettings { it.copy(notifyAllBreaking = v) }
        }
        if (!notifGranted && Build.VERSION.SDK_INT >= 33) {
            OutlinedButton(onClick = { permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                modifier = Modifier.padding(horizontal = 16.dp)) { Text("सूचना की अनुमति दें") }
        }
        Hint("बैकग्राउंड जांच Android के नियमों के कारण लगभग हर 15 मिनट या उससे देर से होती है; बैटरी बचत में फ़ोन इसे और टाल सकता है।")

        // ---- app updates
        Header("ऐप अपडेट")
        Hint("वर्तमान संस्करण: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        if (!UpdateManager.enabled) {
            Hint("यह डीबग बिल्ड है — अपडेट जांच बंद है।")
        } else {
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Prefs.UPDATE_OPTIONS.forEach { h ->
                    FilterChip(selected = s.updateCheckHours == h, onClick = { vm.updateSettings { it.copy(updateCheckHours = h) } },
                        label = { Text("हर $h घंटे") })
                }
            }
            ToggleRow("नया संस्करण अपने-आप डाउनलोड करें", s.autoDownloadUpdates) { v -> vm.updateSettings { it.copy(autoDownloadUpdates = v) } }
            Hint("ऐप खुलते ही और चुने गए अंतराल पर बैकग्राउंड में जांच होती है। इंस्टॉल के लिए Android हमेशा आपकी पुष्टि मांगता है।")
            Hint("अंतिम जांच: ${TimeFmt.full(Instant.ofEpochMilli(up.lastCheckMs).takeIf { up.lastCheckMs > 0 })}" +
                (up.lastResult?.let { " · $it" } ?: ""))
            up.error?.let { Hint(it, c.breaking) }
            UpdateControls(vm)
            Row(Modifier.padding(horizontal = 16.dp)) {
                OutlinedButton(onClick = { vm.checkUpdates("manual") }, enabled = !up.checking) {
                    Text(if (up.checking) "जांच हो रही है…" else "अभी अपडेट जांचें")
                }
                TextButton(onClick = { Actions.open(ctx, BuildConfig.RELEASES_PAGE) }) { Text("सभी संस्करण") }
            }
        }

        // ---- leaders
        Header("अनुसरित नेता")
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (cache.entities.leaders + cache.entities.figures).forEach { e ->
                FilterChip(selected = e.id in s.followedLeaders, onClick = { vm.toggleFollowLeader(e.id) }, label = { Text(e.nameHi) })
            }
        }
        if (cache.entities.leaders.isEmpty()) Hint("सूची पहली बार अपडेट होने पर दिखेगी।")

        // ---- districts
        Header("पसंदीदा जिले")
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            cache.entities.districts.sortedBy { if (it.key) 0 else 1 }.forEach { d ->
                FilterChip(selected = d.id in s.preferredDistricts, onClick = { vm.toggleDistrict(d.id) }, label = { Text(d.nameHi) })
            }
        }

        // ---- sources
        Header("समाचार स्रोत (${cache.sources.count { it.status == "ok" }} काम कर रहे)")
        Hint("स्थिति सर्वर की पिछली वास्तविक जांच से आती है। किसी स्रोत को बंद करने पर उसकी खबरें इस फ़ोन पर छिप जाती हैं।")
        cache.sources.forEach { src ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(src.nameHi, style = MaterialTheme.typography.bodyLarge)
                    val color = when (src.status) { "ok" -> c.multi; "error" -> c.breaking; else -> c.muted }
                    Text(
                        src.statusHi + (src.errorHi?.let { " — $it" } ?: "") +
                            (src.lastOkAt?.let { " · अंतिम सफल: ${TimeFmt.short(FeedLogic.instant(it))}" } ?: "") +
                            if (src.totalItems > 0) " · ${src.totalItems} खबरें" else "",
                        style = MaterialTheme.typography.bodySmall, color = color,
                    )
                }
                Switch(checked = src.id !in s.hiddenSources, onCheckedChange = { vm.toggleSource(src.id) })
            }
            HorizontalDivider(color = c.divider, modifier = Modifier.padding(horizontal = 16.dp))
        }

        // ---- reading
        Header("पढ़ने का अक्षर आकार")
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Prefs.TEXT_SCALES.forEach { (v, label) ->
                FilterChip(selected = s.textScale == v, onClick = { vm.updateSettings { it.copy(textScale = v) } }, label = { Text(label) })
            }
        }

        // ---- backend
        Header("बैकएंड (फ़ीड) पता")
        var url by remember(s.feedUrl) { mutableStateOf(s.feedUrl) }
        OutlinedTextField(url, { url = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.updateSettings { it.copy(feedUrl = url.trim()) } }, enabled = url.trim() != s.feedUrl) { Text("सहेजें") }
            TextButton(onClick = { vm.updateSettings { it.copy(feedUrl = BuildConfig.DEFAULT_FEED_URL) } }) { Text("डिफ़ॉल्ट") }
        }
        Hint("अपना सर्वर चलाने पर यहां https://आपका-सर्वर/feed.json डालें — तब 1 मिनट तक की ताज़गी संभव है।")

        Header("डेटा")
        OutlinedButton(onClick = { vm.clearCache() }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("सहेजा गया कैश साफ़ करें") }
        Hint("सहेजी गई खबरें (बुकमार्क) नहीं हटेंगी।")

        Header("न्यूज़ अड्डा के बारे में")
        Hint("केवल शीर्षक, संक्षिप्त सारांश और मूल प्रकाशक का लिंक दिखाया जाता है; पूरी खबर प्रकाशक की साइट पर पढ़ें। " +
            "कोई खबर, समय या बयान गढ़ा नहीं जाता — जो स्रोत नहीं देता, वह \"उपलब्ध नहीं\" दिखता है। " +
            "\"ब्रेकिंग\", \"आरोप\" आदि निशान शब्द-आधारित नियमों से लगते हैं और हर खबर में उनके कारण दिखते हैं; ये गलत भी हो सकते हैं।")
    }
}

@Composable
fun UpdateControls(vm: MainViewModel) {
    val up by vm.updates.collectAsState()
    val info = up.available ?: return
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text("नया संस्करण ${info.versionName} उपलब्ध है", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        info.notes?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalNewsColors.current.muted) }
        Spacer(Modifier.height(6.dp))
        when {
            up.readyFile != null -> Button(onClick = { vm.installUpdate() }) { Text("इंस्टॉल करें") }
            up.downloading -> {
                LinearProgressIndicator(progress = { up.progress }, modifier = Modifier.fillMaxWidth())
                Text("डाउनलोड हो रहा है… ${(up.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            }
            else -> Button(onClick = { vm.downloadUpdate() }) { Text("डाउनलोड करें") }
        }
    }
}

@Composable
private fun Header(t: String) {
    Spacer(Modifier.height(18.dp))
    Text(t, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

@Composable
private fun Hint(t: String, color: androidx.compose.ui.graphics.Color = LocalNewsColors.current.muted) {
    Text(t, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp))
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
