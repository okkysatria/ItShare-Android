package com.itshare.client.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.itshare.client.core.transfer.TransferCancellationSignal

class TransferForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ItShare::TransferWakeLock")
            .apply { acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            TransferCancellationSignal.cancel()
            return START_NOT_STICKY
        }

        val fileName = intent?.getStringExtra(EXTRA_FILE_NAME) ?: "File"
        val totalBytes = intent?.getLongExtra(EXTRA_TOTAL_BYTES, 0L) ?: 0L
        val bytesTransferred = intent?.getLongExtra(EXTRA_BYTES_TRANSFERRED, 0L) ?: 0L
        val speedBytesPerSec = intent?.getLongExtra(EXTRA_SPEED_BYTES_PER_SEC, 0L) ?: 0L
        val progress = if (totalBytes > 0L) {
            ((bytesTransferred * 100L) / totalBytes).toInt().coerceIn(0, 100)
        } else {
            0
        }
        val notification = buildNotification(fileName, progress, speedBytesPerSec, totalBytes <= 0L)

        if (!foregroundStarted) {
            startForeground(NOTIFICATION_ID, notification)
            foregroundStarted = true
        } else if (intent?.action == ACTION_UPDATE_PROGRESS) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ItShare Transfer",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifikasi progres transfer file ItShare di latar belakang"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        fileName: String,
        progressPercent: Int,
        speedBytesPerSec: Long,
        indeterminate: Boolean
    ): Notification {
        val cancelIntent = Intent(this, TransferForegroundService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPendingIntent = PendingIntent.getService(
            this, 0, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mentransfer: $fileName")
            .setContentText("$progressPercent% • ${formatSpeed(speedBytesPerSec)}")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progressPercent, indeterminate)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Batal", cancelPendingIntent)
            .build()
    }

    private fun formatSpeed(bytesPerSec: Long): String = when {
        bytesPerSec >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0))
        bytesPerSec >= 1024 -> String.format("%.1f KB/s", bytesPerSec / 1024.0)
        else -> "$bytesPerSec B/s"
    }

    override fun onDestroy() {
        foregroundStarted = false
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "itshare_transfer_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_DOWNLOAD = "com.itshare.client.START_DOWNLOAD"
        const val ACTION_START_UPLOAD = "com.itshare.client.START_UPLOAD"
        const val ACTION_UPDATE_PROGRESS = "com.itshare.client.UPDATE_TRANSFER_PROGRESS"
        const val ACTION_CANCEL = "com.itshare.client.CANCEL_TRANSFER"
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_TOTAL_BYTES = "extra_total_bytes"
        const val EXTRA_BYTES_TRANSFERRED = "extra_bytes_transferred"
        const val EXTRA_SPEED_BYTES_PER_SEC = "extra_speed_bytes_per_sec"
    }
}
