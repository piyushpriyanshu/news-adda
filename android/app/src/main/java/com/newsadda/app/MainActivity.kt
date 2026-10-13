package com.newsadda.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.newsadda.app.data.Prefs
import com.newsadda.app.ui.MainViewModel
import com.newsadda.app.ui.NewsAddaApp
import com.newsadda.app.ui.NewsAddaTheme
import com.newsadda.app.ui.Section
import com.newsadda.app.update.UpdateManager
import com.newsadda.app.work.Notifier

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by vm.settings.collectAsState()
            NewsAddaTheme(settings.textScale, hindi = settings.language == "hi") { NewsAddaApp(vm) }
        }

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            // Opening the app or returning to it: refresh now, then on the chosen interval.
            override fun onStart(owner: LifecycleOwner) {
                vm.startForegroundLoop()
                // App updates: checked on every start (throttled to once per 10 minutes).
                val last = UpdateManager.state.value.lastCheckMs
                if (UpdateManager.enabled && System.currentTimeMillis() - last > 10 * 60_000L) vm.checkUpdates("start")
            }

            override fun onStop(owner: LifecycleOwner) = vm.stopForegroundLoop()
        })

        if (Build.VERSION.SDK_INT >= 33 && !Notifier.allowed(this) && Prefs.getLong(this, "asked_notif") == 0L) {
            Prefs.putLong(this, "asked_notif", System.currentTimeMillis())
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(Notifier.EXTRA_STORY)?.let { vm.openStoryId.value = it }
        if (intent.getBooleanExtra(Notifier.EXTRA_INSTALL, false)) {
            vm.section.value = Section.SETTINGS
            if (UpdateManager.state.value.readyFile != null) vm.installUpdate() else vm.downloadUpdate()
        }
    }
}
