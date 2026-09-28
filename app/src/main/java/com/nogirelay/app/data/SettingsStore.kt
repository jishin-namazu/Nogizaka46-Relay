package com.nogirelay.app.data

import android.content.Context
import androidx.core.content.edit
import com.nogirelay.app.data.api.ApiConfig
import com.nogirelay.app.translation.AIModel
import com.nogirelay.app.translation.AIProviderType
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 将设置持久化到 SharedPreferences。API key、模型和缓存的模型列表按提供方
 * 分别存储，因此切换当前提供方时会保留每个提供方各自的配置，而不是覆盖
 * 单一的共享槽位。
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val modelCatalogs = mutableMapOf<AIProviderType, Pair<String, List<AIModel>>>()
    private var sortedCatalog: Pair<List<AIModel>, List<AIModel>>? = null

    init {
        migrateLegacyProviderSlots()
    }

    fun read(): AppSettings {
        val provider = readProvider()
        return AppSettings(
            relayUrl = prefs.getString(KEY_RELAY_URL, "").orEmpty(),
            accessToken = prefs.getString(KEY_ACCESS_TOKEN, "").orEmpty(),
            aiProvider = provider,
            aiApiKey = apiKeyFor(provider),
            aiModel = modelFor(provider),
            cachedAiModels = cachedModelsFor(provider),
            translationEnabled = prefs.getBoolean(KEY_TRANSLATION_ENABLED, false),
            messageFullTranslation = prefs.getBoolean(KEY_MESSAGE_FULL_TRANSLATION, false),
            blogFullTranslation = prefs.getBoolean(KEY_BLOG_FULL_TRANSLATION, false),
            userNickname = prefs.getString(KEY_USER_NICKNAME, "").orEmpty(),
            incomingCallStyle = readIncomingCallStyle(),
        )
    }

    fun save(settings: AppSettings) {
        val relayUrl = settings.relayUrl.trim().trimEnd('/')
        val accessToken = settings.accessToken.trim()
        val relayConfigChanged =
            prefs.getString(KEY_RELAY_URL, "").orEmpty() != relayUrl ||
                prefs.getString(KEY_ACCESS_TOKEN, "").orEmpty() != accessToken

        prefs.edit {
            putString(KEY_RELAY_URL, relayUrl)
            putString(KEY_ACCESS_TOKEN, accessToken)
            putString(KEY_AI_PROVIDER, settings.aiProvider.name)
            putString(apiKeyKey(settings.aiProvider), settings.aiApiKey.trim())
            putString(modelKey(settings.aiProvider), settings.aiModel.trim())
            putString(modelsKey(settings.aiProvider), serializeModels(settings.cachedAiModels))
            putBoolean(KEY_TRANSLATION_ENABLED, settings.translationEnabled)
            putBoolean(KEY_MESSAGE_FULL_TRANSLATION, settings.messageFullTranslation)
            putBoolean(KEY_BLOG_FULL_TRANSLATION, settings.blogFullTranslation)
            putString(KEY_USER_NICKNAME, settings.userNickname.trim())
            putString(KEY_INCOMING_CALL_STYLE, settings.incomingCallStyle.name)
        }

        // A successful device registration belongs to one relay URL + access token.
        if (relayConfigChanged) AppGraph.notifyDataChanged(DataChange.SETTINGS)
    }

    /** 为 [provider] 保存的 API Key，即使当前激活的是另一个提供方。 */
    fun apiKeyFor(provider: AIProviderType): String = prefs.getString(apiKeyKey(provider), "").orEmpty()

    /** 为 [provider] 保存的翻译模型。 */
    fun modelFor(provider: AIProviderType): String = prefs.getString(modelKey(provider), "").orEmpty()

    /** 最近一次校验成功后为 [provider] 缓存的模型列表。 */
    @Synchronized
    fun cachedModelsFor(provider: AIProviderType): List<AIModel> {
        val serialized = prefs.getString(modelsKey(provider), "[]").orEmpty()
        modelCatalogs[provider]?.takeIf { it.first == serialized }?.let { return it.second }
        return readCachedModels(serialized).also { modelCatalogs[provider] = serialized to it }
    }

    @Synchronized
    fun sortedModels(models: List<AIModel>): List<AIModel> {
        sortedCatalog?.takeIf { it.first == models }?.let { return it.second }
        return models.sortedBy { it.displayName.lowercase(java.util.Locale.ROOT) }
            .also { sortedCatalog = models.toList() to it }
    }

    fun pushToken(): String = prefs.getString(KEY_PUSH_TOKEN, "").orEmpty()

    fun savePushToken(token: String) {
        if (pushToken() == token) return
        prefs.edit {
            putString(KEY_PUSH_TOKEN, token)
            remove(KEY_PUSH_REGISTRATION_FINGERPRINT)
        }
        AppGraph.notifyDataChanged(DataChange.SETTINGS)
    }

    /** True only when the current token and relay configuration were confirmed by the server. */
    fun isPushRegistrationConfirmed(): Boolean {
        val current = currentPushRegistrationFingerprint() ?: return false
        return prefs.getString(KEY_PUSH_REGISTRATION_FINGERPRINT, null) == current
    }

    fun markPushRegistrationConfirmed(token: String, relayUrl: String, accessToken: String) {
        if (pushToken() != token) return
        val current = currentPushRegistrationFingerprint() ?: return
        if (current != pushRegistrationFingerprint(token, relayUrl, accessToken)) return
        if (prefs.getString(KEY_PUSH_REGISTRATION_FINGERPRINT, null) == current) return
        prefs.edit { putString(KEY_PUSH_REGISTRATION_FINGERPRINT, current) }
        AppGraph.notifyDataChanged(DataChange.SETTINGS)
    }

    private fun currentPushRegistrationFingerprint(): String? {
        val token = pushToken().takeIf(String::isNotBlank) ?: return null
        val relayUrl = prefs.getString(KEY_RELAY_URL, "").orEmpty()
            .ifEmpty { ApiConfig.BASE_URL }
            .trimEnd('/')
        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, "").orEmpty()
            .ifEmpty { ApiConfig.ACCESS_TOKEN }
        if (relayUrl.isBlank() || accessToken.isBlank()) return null
        return pushRegistrationFingerprint(token, relayUrl, accessToken)
    }

    private fun pushRegistrationFingerprint(
        token: String,
        relayUrl: String,
        accessToken: String,
    ): String {
        val material = "$token\n${relayUrl.trimEnd('/')}\n$accessToken"
        return MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun readProvider(): AIProviderType {
        val stored = prefs.getString(KEY_AI_PROVIDER, null) ?: return AIProviderType.OPENAI
        return runCatching { AIProviderType.valueOf(stored) }.getOrDefault(AIProviderType.OPENAI)
    }

    private fun readIncomingCallStyle(): IncomingCallStyle {
        val stored = prefs.getString(KEY_INCOMING_CALL_STYLE, null) ?: return IncomingCallStyle.CLASSIC
        return runCatching { IncomingCallStyle.valueOf(stored) }.getOrDefault(IncomingCallStyle.CLASSIC)
    }

    /**
     * 旧版本为当前激活的提供方保留一份共享的 key/模型/模型列表。把这些值
     * 一次性迁移到对应提供方的槽位，这样升级就不会丢失当前设置，而其他
     * 提供方则从空槽位开始，不会继承这些值。
     */
    private fun migrateLegacyProviderSlots() {
        if (prefs.getBoolean(KEY_PROVIDER_SLOTS_MIGRATED, false)) return
        val provider = readProvider()
        val hasApiKey = prefs.contains(apiKeyKey(provider))
        val hasModel = prefs.contains(modelKey(provider))
        val hasModels = prefs.contains(modelsKey(provider))
        val legacyApiKey = prefs.getString(KEY_LEGACY_AI_API_KEY, null)
        val legacyModel = prefs.getString(KEY_LEGACY_AI_MODEL, null)
        val legacyModels = prefs.getString(KEY_LEGACY_CACHED_AI_MODELS, null)
        prefs.edit {
            if (!hasApiKey) {
                legacyApiKey?.let { putString(apiKeyKey(provider), it) }
            }
            if (!hasModel) {
                legacyModel?.let { putString(modelKey(provider), it) }
            }
            if (!hasModels) {
                legacyModels?.let { putString(modelsKey(provider), it) }
            }
            remove(KEY_LEGACY_AI_API_KEY)
            remove(KEY_LEGACY_AI_MODEL)
            remove(KEY_LEGACY_CACHED_AI_MODELS)
            putBoolean(KEY_PROVIDER_SLOTS_MIGRATED, true)
        }
    }

    private fun readCachedModels(serialized: String?): List<AIModel> = runCatching {
        val models = JSONArray(serialized ?: "[]")
        (0 until models.length()).mapNotNull { index ->
            models.optJSONObject(index)?.let { model ->
                val id = model.optString("id")
                id.takeIf { it.isNotBlank() }?.let {
                    AIModel(it, model.optString("displayName", it))
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun serializeModels(models: List<AIModel>): String = JSONArray().apply {
        models.forEach { model ->
            put(JSONObject().apply {
                put("id", model.id)
                put("displayName", model.displayName)
            })
        }
    }.toString()

    private fun apiKeyKey(provider: AIProviderType) = "ai_" + provider.name.lowercase() + "_api_key"

    private fun modelKey(provider: AIProviderType) = "ai_" + provider.name.lowercase() + "_model"

    private fun modelsKey(provider: AIProviderType) = "ai_" + provider.name.lowercase() + "_models"

    private companion object {
        const val PREFS_NAME = "settings"
        const val KEY_RELAY_URL = "relay_url"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_AI_PROVIDER = "ai_provider"
        const val KEY_TRANSLATION_ENABLED = "translation_enabled"
        const val KEY_MESSAGE_FULL_TRANSLATION = "message_full_translation"
        const val KEY_BLOG_FULL_TRANSLATION = "blog_full_translation"
        const val KEY_USER_NICKNAME = "user_nickname"
        const val KEY_INCOMING_CALL_STYLE = "incoming_call_style"
        const val KEY_PUSH_TOKEN = "push_token"
        const val KEY_PUSH_REGISTRATION_FINGERPRINT = "push_registration_fingerprint"
        const val KEY_LEGACY_AI_API_KEY = "ai_api_key"
        const val KEY_LEGACY_AI_MODEL = "ai_model"
        const val KEY_LEGACY_CACHED_AI_MODELS = "cached_ai_models"
        const val KEY_PROVIDER_SLOTS_MIGRATED = "ai_provider_slots_migrated"
    }
}
