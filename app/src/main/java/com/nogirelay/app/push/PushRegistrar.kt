package com.nogirelay.app.push

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.api.ApiConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

private val pushMainHandler = Handler(Looper.getMainLooper())

/**
 * 注册结果的交付闸门：无论 token 获取与 HTTP 注册发生在哪个线程，
 * 结果都只交付一次，并且统一回到主线程，这样调用方可以直接更新 UI 状态。
 */
private class ResultDelivery(
    private val onComplete: (Result<Unit>) -> Unit,
) {
    private val delivered = AtomicBoolean(false)

    fun deliver(result: Result<Unit>) {
        if (!delivered.compareAndSet(false, true)) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onComplete(result)
        } else {
            pushMainHandler.post { onComplete(result) }
        }
    }
}

object PushRegistrar {
    /**
     * 兜底超时：设备长时间没有网络或 FCM 服务不回调 token 时，
     * 界面也必须能拿到一个结果，而不是一直停在"正在注册"。
     * 取值大于 HTTP 注册自身的连接+读取超时上限。
     */
    private const val REGISTRATION_TIMEOUT_MS = 45_000L

    fun isConfigured(context: Context): Boolean = FirebaseApp.getApps(context).isNotEmpty()

    /**
     * 获取 FCM token 并注册到同步服务。[onComplete] 保证只在主线程回调一次，
     * 即使 FCM 的 token 任务或网络请求迟迟不返回，也会有超时兜底结果。
     */
    fun registerCurrentToken(
        context: Context,
        onComplete: (Result<Unit>) -> Unit = {},
    ) {
        val appContext = context.applicationContext
        AppGraph.initialize(appContext)
        if (!isConfigured(appContext)) {
            onComplete(Result.failure(IllegalStateException("Firebase 尚未配置")))
            return
        }

        val delivery = ResultDelivery(onComplete)
        val timeout = Runnable {
            delivery.deliver(Result.failure(IllegalStateException("注册超时，请检查网络后重试")))
        }
        pushMainHandler.postDelayed(timeout, REGISTRATION_TIMEOUT_MS)
        val finish = { result: Result<Unit> ->
            pushMainHandler.removeCallbacks(timeout)
            delivery.deliver(result)
        }

        val tokenTask = runCatching { FirebaseMessaging.getInstance().token }
        tokenTask.getOrElse { error ->
            finish(Result.failure(error))
            return
        }.addOnCompleteListener { task ->
            val token = runCatching {
                if (!task.isSuccessful) throw task.exception ?: IllegalStateException("无法获取 FCM Token")
                task.result?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("无法获取 FCM Token")
            }
            token.fold(
                onSuccess = { value ->
                    runCatching { AppGraph.settings.savePushToken(value) }
                    registerTokenInBackground(appContext, value, finish)
                },
                onFailure = { error -> finish(Result.failure(error)) },
            )
        }
    }

    fun registerTokenInBackground(
        context: Context,
        token: String,
        onComplete: (Result<Unit>) -> Unit = {},
    ) {
        Thread({
            val result = runCatching { registerToken(context, token) }
            onComplete(result)
        }, "push-token-registration").start()
    }

    private fun registerToken(context: Context, token: String) {
        AppGraph.initialize(context)
        val settings = AppGraph.settings.read()

        // 优先使用 settings 中的配置，如果为空则使用 ApiConfig 默认值
        val baseUrl = settings.relayUrl.ifEmpty { ApiConfig.BASE_URL }
        val accessToken = settings.accessToken.ifEmpty { ApiConfig.ACCESS_TOKEN }

        require(baseUrl.startsWith("https://")) { "同步服务地址必须使用 HTTPS" }
        require(accessToken.isNotBlank()) { "请先填写访问令牌" }

        val payload = JSONObject()
            .put("token", token)
            .put("platform", "android")
            .put("label", "${Build.MANUFACTURER} ${Build.MODEL}")
            .toString()
            .toByteArray()

        val connection = (URL("${baseUrl.trimEnd('/')}/v1/devices").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setFixedLengthStreamingMode(payload.size)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $accessToken")
        }
        try {
            connection.outputStream.use { it.write(payload) }
            val status = connection.responseCode
            if (status !in 200..299) error("设备注册失败：HTTP $status")
            connection.inputStream.close()
        } finally {
            connection.disconnect()
        }
    }
}
