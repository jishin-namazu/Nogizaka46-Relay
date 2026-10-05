package com.nogirelay.app.data

import android.net.Uri
import com.nogirelay.app.data.api.ApiConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class RelayClient {
    fun fetchMessages(settings: AppSettings, limit: Int = 200, offset: Int = 0): List<RelayMessage> {
        val endpoint = resolveEndpoint(settings)
        require(limit in 1..500) { "同步数量必须在 1 到 500 之间" }
        require(offset >= 0) { "同步偏移量不能为负数" }

        return get(endpoint, "/v1/messages?limit=$limit&offset=$offset", readTimeout = 30_000, errorLabel = "消息同步服务") { payload ->
            val messages = JSONObject(payload).optJSONArray("messages") ?: JSONArray()
            buildList(messages.length()) {
                for (index in 0 until messages.length()) {
                    messages.optJSONObject(index)?.let { add(parseMessage(it)) }
                }
            }
        }
    }

    fun fetchMessageCount(settings: AppSettings): Int {
        val endpoint = resolveEndpoint(settings)
        return get(endpoint, "/v1/messages/stats/summary", readTimeout = 20_000, errorLabel = "消息统计服务") { payload ->
            JSONObject(payload).optJSONObject("stats")?.optInt("total", -1)?.takeIf { it >= 0 }
                ?: error("消息统计服务未返回总条数")
        }
    }

    fun fetchMessage(settings: AppSettings, messageId: String): RelayMessage {
        val endpoint = resolveEndpoint(settings)
        val encodedId = Uri.encode(messageId)
        return get(endpoint, "/v1/messages/$encodedId", readTimeout = 20_000, errorLabel = "消息服务") { payload ->
            parseMessage(JSONObject(payload))
        }
    }

    fun parseMessage(json: JSONObject): RelayMessage {
        fun string(vararg keys: String): String? {
            for (key in keys) {
                val value = json.optString(key, "").trim()
                if (value.isNotEmpty() && value != "null") return value
            }
            return null
        }

        return RelayMessage(
            id = string("id", "message_id") ?: error("消息缺少 id"),
            memberId = string("member_id", "memberId").orEmpty(),
            memberName = string("member_name", "memberName", "incoming_call_from") ?: "乃木坂46",
            memberAvatarUrl = string("member_avatar_url", "memberAvatarUrl", "thumbnail"),
            phoneImageUrl = string("phone_image_url", "phoneImageUrl", "phone_image"),
            type = MessageType.fromWire(string("type", "content_kind", "media_type").orEmpty()),
            text = string("text", "text_content", "original_text"),
            mediaUrl = string("media_url", "mediaUrl", "url"),
            thumbnailUrl = string("thumbnail_url", "thumbnailUrl"),
            durationSeconds = json.optInt("duration_seconds", -1).takeIf { it >= 0 },
            sentAt = string("sent_at", "date_sent", "created_at") ?: "",
            incomingCallFrom = string("incoming_call_from", "incomingCallFrom"),
            ringtoneUrl = string("ringtone_url", "ringtoneUrl", "notification_sound_android"),
            isPlayed = json.optBoolean("is_played", json.optBoolean("isPlayed", false)),
        )
    }

    private class Endpoint(val baseUrl: String, val token: String)

    private fun resolveEndpoint(settings: AppSettings): Endpoint {
        val baseUrl = settings.relayUrl.ifEmpty { ApiConfig.BASE_URL }
        val token = settings.accessToken.ifEmpty { ApiConfig.ACCESS_TOKEN }
        require(baseUrl.startsWith("https://")) { "同步地址必须使用 HTTPS" }
        return Endpoint(baseUrl, token)
    }

    private fun <T> get(
        endpoint: Endpoint,
        path: String,
        readTimeout: Int,
        errorLabel: String,
        parse: (String) -> T,
    ): T {
        val connection = (URL("${endpoint.baseUrl.trimEnd('/')}$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            this.readTimeout = readTimeout
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer ${endpoint.token}")
        }
        return try {
            val status = connection.responseCode
            if (status !in 200..299) throw HttpStatusException(status, "$errorLabel 返回 $status")
            parse(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }
}
