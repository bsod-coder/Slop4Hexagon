package com.slop4hexagon.domain.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

class InferenceForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel("inference", "Inference", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = Notification.Builder(this, "inference")
            .setContentTitle("Slop4Hexagon")
            .setContentText("Running inference...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(1, notif)
        // Real work is driven via ViewModel coroutine; service just keeps CPU awake
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
