package com.carcompanion.companion.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService

internal object NotificationHelper {
    const val CHANNEL_ID = "car_companion_service"
    const val FOREGROUND_NOTIFICATION_ID = 1001

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService<NotificationManager>() ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "CarCompanion service",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Background bridge between car OBD2 and companion robot"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }
}
