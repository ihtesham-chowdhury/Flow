package com.flowbangla.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.flowbangla.app.data.HistoryManager
import com.flowbangla.app.data.PreferencesManager

class FlowBanglaApp : Application() {

    lateinit var preferencesManager: PreferencesManager
        private set

    lateinit var historyManager: HistoryManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        preferencesManager = PreferencesManager(this)
        historyManager = HistoryManager(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "flowbangla_dictation_channel"
        lateinit var instance: FlowBanglaApp
            private set
    }
}
