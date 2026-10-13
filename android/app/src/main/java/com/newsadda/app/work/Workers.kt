package com.newsadda.app.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.newsadda.app.MainActivity
import com.newsadda.app.R
import com.newsadda.app.data.NewsRepository
import com.newsadda.app.data.Prefs
import com.newsadda.app.data.model.Story
import com.newsadda.app.ui.Str
import com.newsadda.app.update.UpdateManager
import java.util.concurrent.TimeUnit

object Notifier {
    const val CH_BREAKING = "breaking"
    const val CH_UPDATES = "updates"
    const val EXTRA_STORY = "story_id"
    const val EXTRA_INSTALL = "install_update"

    /** (Re)creates channels; names follow the interface language. */
    fun createChannels(ctx: Context) {
        val str = Str.of(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_BREAKING, str.chBreaking, NotificationManager.IMPORTANCE_HIGH).apply {
                description = str.chBreakingDesc
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_UPDATES, str.chUpdates, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = str.chUpdatesDesc
            }
        )
    }

    fun allowed(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(ctx: Context, code: Int, extras: Intent.() -> Unit): PendingIntent =
        PendingIntent.getActivity(
            ctx, code,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP).apply(extras),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    @Suppress("MissingPermission")
    fun story(ctx: Context, s: Story) {
        if (!allowed(ctx)) return
        val src = s.reports.firstOrNull()?.let { it.publisher ?: it.sourceName } ?: ""
        val n = NotificationCompat.Builder(ctx, CH_BREAKING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (s.breaking) Str.of(ctx).nBreaking else Str.of(ctx).nNew)
            .setContentText(s.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${s.title}\n— $src"))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, s.id.hashCode()) { putExtra(EXTRA_STORY, s.id) })
            .build()
        NotificationManagerCompat.from(ctx).notify(s.id.hashCode(), n)
    }

    @Suppress("MissingPermission")
    fun summary(ctx: Context, count: Int) {
        if (!allowed(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_BREAKING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(Str.of(ctx).nSummary(count))
            .setContentText(Str.of(ctx).nOpenApp)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, 1) {})
            .build()
        NotificationManagerCompat.from(ctx).notify(1, n)
    }

    @Suppress("MissingPermission")
    fun update(ctx: Context, versionName: String, ready: Boolean) {
        if (!allowed(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(Str.of(ctx).nUpdateTitle(versionName))
            .setContentText(if (ready) Str.of(ctx).nUpdateReady else Str.of(ctx).nUpdateTap)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, 2) { putExtra(EXTRA_INSTALL, true) })
            .build()
        NotificationManagerCompat.from(ctx).notify(2, n)
    }
}

/** Background breaking-news check. Android runs it at most every ~15 minutes and may delay it. */
class NewsWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val s = Prefs.get(ctx)
        if (!s.newsNotifications) return Result.success()
        val fresh = NewsRepository.refresh(ctx, "background")
        if (fresh.isEmpty()) return Result.success()
        val notified = Prefs.getSet(ctx, "notified")
        val follows = s.followedLeaders.isNotEmpty() || s.preferredDistricts.isNotEmpty()
        val hits = NewsRepository.state.value.cache.stories.filter { st ->
            st.id in fresh && st.id !in notified && st.breaking &&
                (s.notifyAllBreaking || !follows ||
                    st.entities.leaders.any { it in s.followedLeaders } ||
                    st.entities.figures.any { it in s.followedLeaders } ||
                    st.entities.districts.any { it in s.preferredDistricts })
        }
        if (hits.size > 3) Notifier.summary(ctx, hits.size) else hits.forEach { Notifier.story(ctx, it) }
        Prefs.putSet(ctx, "notified", (notified + hits.map { it.id }).toList().takeLast(500).toSet())
        return Result.success()
    }
}

/** Background app-update check at the interval chosen in Settings. */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!UpdateManager.enabled) return Result.success()
        UpdateManager.restore(ctx)
        val info = UpdateManager.checkNow(ctx, "background") ?: return Result.success()
        val ready = UpdateManager.state.value.readyFile != null
        val key = "${info.versionCode}:$ready"
        if (Prefs.getString(ctx, "update_notified") != key) {
            Notifier.update(ctx, info.versionName, ready)
            Prefs.putString(ctx, "update_notified", key)
        }
        return Result.success()
    }
}

object Schedules {
    fun apply(ctx: Context) {
        val s = Prefs.get(ctx)
        val wm = WorkManager.getInstance(ctx)
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        if (s.newsNotifications) {
            wm.enqueueUniquePeriodicWork(
                "news-check", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<NewsWorker>(15, TimeUnit.MINUTES).setConstraints(net).build(),
            )
        } else {
            wm.cancelUniqueWork("news-check")
        }
        if (UpdateManager.enabled) {
            val hours = s.updateCheckHours.toLong()
            val key = "update_hours_scheduled"
            val policy = if (Prefs.getLong(ctx, key) == hours) ExistingPeriodicWorkPolicy.KEEP
            else ExistingPeriodicWorkPolicy.UPDATE
            wm.enqueueUniquePeriodicWork(
                "update-check", policy,
                PeriodicWorkRequestBuilder<UpdateWorker>(hours, TimeUnit.HOURS).setConstraints(net).build(),
            )
            Prefs.putLong(ctx, key, hours)
        }
    }
}
