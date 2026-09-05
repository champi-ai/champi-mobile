package ai.champi.app

import ai.champi.context.clearShareCache
import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ChampiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        clearShareCache(this)
        ProactiveNotificationHelper.registerChannels(this)
    }
}
