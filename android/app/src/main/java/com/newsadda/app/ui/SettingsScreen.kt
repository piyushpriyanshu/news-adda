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
import androidx.compose.ui.graphics.Color
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
fun SettingsScreen(vm: MainViewModel, names: Names) {
    val ctx = LocalContext.current
    val str = LocalStr.current
    val s by vm.settings.collectAsState()
    val repo by vm.repo.collectAsState()
    val up by vm.updates.collectAsState()
    val c = LocalNewsColors.current
    val cache = repo.cache
    var notifGranted by remember { mutableStateOf(Notifier.allowed(ctx)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifGranted = it }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        // ---- language
        Header(str.language)
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = s.language == "en", onClick = { vm.updateSettings { it.copy(language = "en") } }, label = { Text("English") })
            FilterChip(selected = s.language == "hi", onClick = { vm.updateSettings { it.copy(language = "hi") } }, label = { Text("हिंदी") })
        }
        Hint(str.languageNote)

        // ---- refresh
        Header(str.refreshInterval)
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Prefs.REFRESH_OPTIONS.forEach { m ->
                FilterChip(selected = s.refreshMinutes == m, onClick = { vm.updateSettings { it.copy(refreshMinutes = m) } },
                    label = { Text(str.minutes(m)) })
            }
        }
        Hint(str.refreshNote(s.refreshMinutes))
        ToggleRow(str.liveWireSetting, s.liveWire) { v -> vm.updateSettings { it.copy(liveWire = v) } }
        Hint(str.liveWireSettingNote)
        Hint(str.lastSuccess(
            TimeFmt.full(Instant.ofEpochMilli(repo.lastSuccessMs).takeIf { repo.lastSuccessMs > 0 }, str),
            TimeFmt.full(FeedLogic.instant(cache.generatedAt), str),
        ))

        // ---- notifications
        Header(str.notifications)
        ToggleRow(str.notificationsOn, s.newsNotifications) { v -> vm.updateSettings { it.copy(newsNotifications = v) } }
        ToggleRow(str.notifyAll, s.notifyAllBreaking) { v -> vm.updateSettings { it.copy(notifyAllBreaking = v) } }
        if (!notifGranted && Build.VERSION.SDK_INT >= 33) {
            OutlinedButton(onClick = { permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                modifier = Modifier.padding(horizontal = 16.dp)) { Text(str.allowNotifications) }
        }
        Hint(str.backgroundNote)

        // ---- app updates
        Header(str.appUpdates)
        Hint(str.currentVersion(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
        if (!UpdateManager.enabled) {
            Hint(str.debugNoUpdates)
        } else {
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Prefs.UPDATE_OPTIONS.forEach { h ->
                    FilterChip(selected = s.updateCheckHours == h, onClick = { vm.updateSettings { it.copy(updateCheckHours = h) } },
                        label = { Text(str.everyHours(h)) })
                }
            }
            ToggleRow(str.autoDownload, s.autoDownloadUpdates) { v -> vm.updateSettings { it.copy(autoDownloadUpdates = v) } }
            Hint(str.updatesNote)
            val result = when {
                up.lastCheckMs == 0L -> null
                up.available != null -> str.newVersionAvailable(up.available!!.versionName)
                up.error == null -> str.onLatest(BuildConfig.VERSION_NAME)
                else -> null
            }
            Hint(str.lastCheck(TimeFmt.full(Instant.ofEpochMilli(up.lastCheckMs).takeIf { up.lastCheckMs > 0 }, str)) +
                (result?.let { " · $it" } ?: ""))
            up.error?.let { Hint(it, c.breaking) }
            UpdateControls(vm)
            Row(Modifier.padding(horizontal = 16.dp)) {
                OutlinedButton(onClick = { vm.checkUpdates("manual") }, enabled = !up.checking) {
                    Text(if (up.checking) str.checking else str.checkNow)
                }
                TextButton(onClick = { Actions.open(ctx, BuildConfig.RELEASES_PAGE, str) }) { Text(str.allVersions) }
            }
        }

        // ---- leaders
        Header(str.followedLeaders)
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (cache.entities.leaders + cache.entities.figures).forEach { e ->
                FilterChip(selected = e.id in s.followedLeaders, onClick = { vm.toggleFollowLeader(e.id) }, label = { Text(names.of(e)) })
            }
        }
        if (cache.entities.leaders.isEmpty()) Hint(str.listAfterFirstUpdate)

        // ---- districts
        Header(str.favouriteDistricts)
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            cache.entities.districts.sortedWith(compareBy({ if (it.key) 0 else 1 }, { names.of(it) })).forEach { d ->
                FilterChip(selected = d.id in s.preferredDistricts, onClick = { vm.toggleDistrict(d.id) }, label = { Text(names.of(d)) })
            }
        }

        // ---- sources
        Header(str.newsSources(cache.sources.count { it.status == "ok" }))
        Hint(str.sourcesNote)
        cache.sources.forEach { src ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val label = (if (str.hindi) src.nameHi else src.name).ifBlank { src.id }
                SourceIcon(src.domain, label, 28.dp)
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                    val color = when (src.status) { "ok" -> c.multi; "error" -> c.breaking; else -> c.muted }
                    Text(
                        str.sourceStatus(src.status) +
                            (src.error?.let { " — " + str.sourceError(it) } ?: "") +
                            (src.lastOkAt?.let { " · " + str.lastOk(TimeFmt.short(FeedLogic.instant(it), Instant.now(), str)) } ?: "") +
                            if (src.totalItems > 0) " · " + str.itemCount(src.totalItems) else "",
                        style = MaterialTheme.typography.bodySmall, color = color,
                    )
                }
                Switch(checked = src.id !in s.hiddenSources, onCheckedChange = { vm.toggleSource(src.id) })
            }
            HorizontalDivider(color = c.divider, modifier = Modifier.padding(horizontal = 16.dp))
        }

        // ---- reading
        Header(str.textSize)
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Prefs.TEXT_SCALES.forEachIndexed { i, v ->
                FilterChip(selected = s.textScale == v, onClick = { vm.updateSettings { it.copy(textScale = v) } },
                    label = { Text(str.textSizes[i]) })
            }
        }

        // ---- backend
        Header(str.backendAddress)
        var url by remember(s.feedUrl) { mutableStateOf(s.feedUrl) }
        OutlinedTextField(url, { url = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.updateSettings { it.copy(feedUrl = url.trim()) } }, enabled = url.trim() != s.feedUrl) { Text(str.saveBtn) }
            TextButton(onClick = { vm.updateSettings { it.copy(feedUrl = BuildConfig.DEFAULT_FEED_URL) } }) { Text(str.defaultBtn) }
        }
        Hint(str.backendNote)

        Header(str.data)
        OutlinedButton(onClick = { vm.clearCache() }, modifier = Modifier.padding(horizontal = 16.dp)) { Text(str.clearCache) }
        Hint(str.clearCacheNote)

        Header(str.about)
        Hint(str.aboutText)
    }
}

@Composable
fun UpdateControls(vm: MainViewModel) {
    val up by vm.updates.collectAsState()
    val str = LocalStr.current
    val info = up.available ?: return
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(str.newVersionAvailable(info.versionName), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        info.notes?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalNewsColors.current.muted) }
        Spacer(Modifier.height(6.dp))
        when {
            up.readyFile != null -> Button(onClick = { vm.installUpdate() }) { Text(str.install) }
            up.downloading -> {
                LinearProgressIndicator(progress = { up.progress }, modifier = Modifier.fillMaxWidth())
                Text(str.downloadingPct((up.progress * 100).toInt()), style = MaterialTheme.typography.bodySmall)
            }
            else -> Button(onClick = { vm.downloadUpdate() }) { Text(str.download) }
        }
    }
}

@Composable
private fun Header(t: String) {
    Spacer(Modifier.height(18.dp))
    Text(t, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

@Composable
private fun Hint(t: String, color: Color = LocalNewsColors.current.muted) {
    Text(t, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp))
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
