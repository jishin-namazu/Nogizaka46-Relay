package com.nogirelay.app.push

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.content.Intent
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.nogirelay.app.call.IncomingCallNotifier
import com.nogirelay.app.call.IncomingCallPreparationService
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.MessageReadTracker
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.media.MediaDownloader
import com.nogirelay.app.notification.NotificationChannels
import com.nogirelay.app.translation.TranslationManager
import com.nogirelay.app.translation.BlogTranslationManager
import com.nogirelay.app.blog.BlogNotifier
import com.nogirelay.app.blog.BlogMediaDownloader
import com.nogirelay.app.data.BlogPost
import com.nogirelay.app.data.BlogReadTracker
import org.json.JSONObject

class NogiFirebaseMessagingService : FirebaseMessagingService() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.initialize(this)
        NotificationChannels.create(this)
    }

    override fun onNewToken(token: String) {
        AppGraph.initialize(this)
        AppGraph.settings.savePushToken(token)
        PushRegistrar.registerTokenInBackground(this, token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        if (remoteMessage.data["type"] == "blog") {
            val previewBlog = runCatching { AppGraph.blogClient.fromPush(remoteMessage.data) }
                .onFailure { Log.w("NogiRelay", "Invalid BLOG push payload", it) }
                .getOrNull() ?: return
            if (AppGraph.database.upsertBlog(previewBlog, isUnread = !BlogReadTracker.isViewing(previewBlog.id))) {
                BlogNotifier.show(this, previewBlog)
            }
            AppGraph.notifyDataChanged()
            fetchAndPrepareBlogInBackground(previewBlog)
            return
        }
        val result = runCatching { resolveMessage(remoteMessage.data) }
        val message = result.getOrNull() ?: return
        val isNew = AppGraph.database.insert(
            message = message,
            isUnread = !MessageReadTracker.isViewing(message.memberKey),
        )
        if (!isNew) return
        AppGraph.notifyDataChanged()

        if (message.shouldRing) {
            startCallPreparation(message)
        } else {
            IncomingCallNotifier.showMessage(this, message)
            Thread({
                runCatching { MediaDownloader.enqueueIfNeeded(this, message) }
                    .onFailure { error -> Log.w("NogiRelay", "Media prefetch failed for ${message.id}", error) }
            }, "media-prefetch").start()
        }
        // 刚到的这条一定要翻；历史积压是否顺带处理由"消息全量翻译"开关决定。
        TranslationManager.enqueueAfterSync(this, listOf(message.id))
    }

    private fun startCallPreparation(message: RelayMessage) {
        val intent = Intent(this, IncomingCallPreparationService::class.java).apply {
            putExtra(IncomingCallNotifier.EXTRA_MESSAGE_ID, message.id)
        }
        runCatching { ContextCompat.startForegroundService(this, intent) }
            .onFailure { error ->
                Log.w("NogiRelay", "Unable to start call preparation service", error)
                // 高优先级 FCM 通常允许前台服务；
                // 针对 OEM 限制保留一个尽力而为的兜底方案。
                Thread({
                    val downloaded = runCatching { MediaDownloader.enqueueIfNeeded(this, message) }.getOrNull()
                    Handler(Looper.getMainLooper()).post {
                        if (downloaded != null) IncomingCallNotifier.show(this, message)
                        else IncomingCallNotifier.showUnavailable(this, message, "语音下载失败，请点击通知重试")
                    }
                }, "call-media-preparation-fallback").start()
            }
    }

    private fun fetchAndPrepareBlogInBackground(previewBlog: BlogPost) {
        Thread({
            runCatching {
                Log.d("NogiRelay", "Auto-fetching full blog JSONP for ${previewBlog.id} in background...")
                val fullBlog = AppGraph.blogClient.fetchBlogPost(previewBlog.id, previewBlog.memberId)
                if (fullBlog != null && fullBlog.bodyHtml.isNotBlank()) {
                    Log.d("NogiRelay", "Successfully fetched full blog ${fullBlog.id}, updating database and cache")
                    AppGraph.database.upsertBlog(fullBlog, isUnread = !BlogReadTracker.isViewing(fullBlog.id))
                    BlogMediaDownloader.enqueue(this, fullBlog)
                    BlogTranslationManager.enqueue(this, fullBlog.id, force = true)
                } else {
                    Log.w("NogiRelay", "Could not fetch full blog body for ${previewBlog.id}; enqueueing pending")
                    BlogTranslationManager.enqueuePending(this)
                }
            }.onFailure { error ->
                Log.w("NogiRelay", "Background blog pre-fetch and translation failed for ${previewBlog.id}", error)
                BlogTranslationManager.enqueuePending(this)
            }
        }, "blog-prefetch-translate").start()
    }

    private fun resolveMessage(data: Map<String, String>): RelayMessage {
        data["payload"]?.takeIf { it.isNotBlank() }?.let {
            return AppGraph.relayClient.parseMessage(JSONObject(it))
        }
        val messageId = data["message_id"] ?: error("FCM data message has no message_id")
        return AppGraph.relayClient.fetchMessage(AppGraph.settings.read(), messageId)
    }
}

