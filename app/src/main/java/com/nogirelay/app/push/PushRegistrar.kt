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

    private const val REGISTRATION_TIMEOUT_MS = 45_000L

    fun isConfigured(context: Context): Boolean = FirebaseApp.getApps(context).isNotEmpty()

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

        @Suppress("DEPRECATION")
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
            AppGraph.settings.markPushRegistrationConfirmed(token, baseUrl, accessToken)
        } finally {
            connection.disconnect()
        }
    }
}
