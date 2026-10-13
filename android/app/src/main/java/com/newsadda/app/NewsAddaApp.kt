package com.newsadda.app

import android.app.Application
import com.newsadda.app.update.UpdateManager
import com.newsadda.app.work.Notifier
import com.newsadda.app.work.Schedules

class NewsAddaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        UpdateManager.restore(this)
        Schedules.apply(this)
    }
}
