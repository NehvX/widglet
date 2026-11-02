package com.widglet.app

import android.app.Application
import com.widglet.app.cutout.CleanupWorker
import com.widglet.app.widget.ScreenEvents

class WidgletApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ScreenEvents.register(this)
        // Also keeps WorkManager's internal receivers enabled, which avoids a known widget update loop.
        CleanupWorker.schedule(this)
    }
}
