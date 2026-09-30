package com.nogirelay.app.data.transfer

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nogirelay.app.MainActivity
import com.nogirelay.app.R
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.notification.NotificationChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MediaBackfillService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        AppGraph.initialize(this)
        NotificationChannels.create(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        if (intent?.action == ACTION_CANCEL) {
            DataTransferManager.cancel()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification(0, 0))
        acquireWakeLock()
        observeTransfer()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int) {
        DataTransferManager.cancel()
        finish()
    }

    override fun onDestroy() {
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun observeTransfer() {
        serviceScope.launch {

            DataTransferManager.state.collect { snapshot ->
                if (!snapshot.running) {
                    finish()
                    return@collect
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(
                        this@MediaBackfillService,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    NotificationManagerCompat.from(this@MediaBackfillService)
                        .notify(NOTIFICATION_ID, buildNotification(snapshot.done, snapshot.total))
                }
                delay(NOTIFICATION_THROTTLE_MS)
            }
        }
    }

    private fun finish() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(done: Int, total: Int): Notification {
        val builder = NotificationCompat.Builder(this, NotificationChannels.MEDIA_BACKFILL)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentTitle("正在补齐媒体")
            .setContentText(if (total > 0) "$done / $total" else "准备中…")
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openAppIntent())
            .addAction(0, "取消", cancelIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
        if (total > 0) {
            builder.setProgress(total, done.coerceIn(0, total), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
        ),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancelIntent(): PendingIntent = PendingIntent.getService(
        this,
        1,
        Intent(this, MediaBackfillService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun acquireWakeLock() {
        val manager = getSystemService(PowerManager::class.java) ?: return
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)

            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
        wakeLock = null
    }

    companion object {
        private const val NOTIFICATION_ID = 9_998
        private const val WAKE_LOCK_TAG = "NogiRelay:MediaBackfill"
        private const val WAKE_LOCK_TIMEOUT_MS = 30L * 60L * 1000L
        private const val NOTIFICATION_THROTTLE_MS = 400L
        const val ACTION_CANCEL = "com.nogirelay.app.CANCEL_MEDIA_BACKFILL"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MediaBackfillService::class.java),
            )
        }
    }
}
