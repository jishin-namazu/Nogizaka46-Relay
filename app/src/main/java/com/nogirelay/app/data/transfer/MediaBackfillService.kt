package com.nogirelay.app.data.transfer

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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

/**
 * 「补齐媒体」的前台外壳。
 *
 * 之前补齐媒体跑在 [DataTransferManager] 的普通协程里：退到后台进程就降级成 cached process，
 * 系统随时可以回收，息屏进 Doze 后网络也会被挂起，能不能下完全看运气。前台服务是 Android
 * 唯一"我要在后台干活"的正规身份——代价是必须挂一条常驻通知，换来进程不被回收、后台网络不被掐。
 *
 * 真正的下载仍然由 [DataTransferManager] 驱动（同一个进程、同一个 [DataTransferManager.state]），
 * 这里只做三件事：进入前台、按进度刷通知、下载期间持 partial wake lock。因此在抽屉里点「取消」
 * 依然走 [DataTransferManager.cancel]，状态天然一致。
 */
class MediaBackfillService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        AppGraph.initialize(this)
        NotificationChannels.create(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通知里的「取消」：服务已经在跑，不需要再进一次前台。
        if (intent?.action == ACTION_CANCEL) {
            DataTransferManager.cancel()
            return START_NOT_STICKY
        }
        // 通过 startForegroundService 启动后必须尽快调用，否则系统抛
        // ForegroundServiceDidNotStartInTimeException。
        startForeground(NOTIFICATION_ID, buildNotification(0, 0))
        acquireWakeLock()
        observeTransfer()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Android 14 起前台服务超时（Android 15 起 dataSync 型有 24 小时累计上限）会回调这里；
     * 与其被系统掐掉，不如把下载停干净。
     */
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
            // StateFlow 本身就会合并中间值，配合 delay 把通知刷新节流到每 400ms 一次，
            // 不会为了每一条媒体去 notify 一次。
            DataTransferManager.state.collect { snapshot ->
                if (!snapshot.running) {
                    finish()
                    return@collect
                }
                NotificationManagerCompat.from(this@MediaBackfillService)
                    .notify(NOTIFICATION_ID, buildNotification(snapshot.done, snapshot.total))
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
            // 兜底超时：正常收尾在 onDestroy 里释放，这里只是防止服务被异常留下。
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

        /** 用户此刻在前台点击「补齐缺失媒体」，允许启动前台服务。 */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MediaBackfillService::class.java),
            )
        }
    }
}
