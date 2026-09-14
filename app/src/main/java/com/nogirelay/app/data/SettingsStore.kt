package com.nogirelay.app.data

import android.content.Context
import com.nogirelay.app.translation.AIProviderType
import com.nogirelay.app.translation.AIModel
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists settings in SharedPreferences. The API key, model and cached model list are stored per
 * provider, so switching the active provider keeps every provider's own configuration instead of
 * overwriting the single shared slot.
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
            userNickname = prefs.getString(KEY_USER_NICKNAME, "").orEmpty(),
        )
    }

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_RELAY_URL, settings.relayUrl.trim().trimEnd('/'))
            .putString(KEY_ACCESS_TOKEN, settings.accessToken.trim())
            .putString(KEY_AI_PROVIDER, settings.aiProvider.name)
            .putString(apiKeyKey(settings.aiProvider), settings.aiApiKey.trim())
            .putString(modelKey(settings.aiProvider), settings.aiModel.trim())
            .putString(modelsKey(settings.aiProvider), serializeModels(settings.cachedAiModels))
            .putBoolean(KEY_TRANSLATION_ENABLED, settings.translationEnabled)
            .putString(KEY_USER_NICKNAME, settings.userNickname.trim())
            .apply()
    }

    /** API Key saved for [provider], even while another provider is active. */
    fun apiKeyFor(provider: AIProviderType): String = prefs.getString(apiKeyKey(provider), "").orEmpty()

    /** Translation model saved for [provider]. */
    fun modelFor(provider: AIProviderType): String = prefs.getString(modelKey(provider), "").orEmpty()

    /** Model list cached for [provider] by the last successful validation. */
    fun cachedModelsFor(provider: AIProviderType): List<AIModel> =
        readCachedModels(prefs.getString(modelsKey(provider), "[]"))

    fun pushToken(): String = prefs.getString(KEY_PUSH_TOKEN, "").orEmpty()

    fun savePushToken(token: String) {
        prefs.edit().putString(KEY_PUSH_TOKEN, token).apply()
    }

    private fun readProvider(): AIProviderType {
        val stored = prefs.getString(KEY_AI_PROVIDER, null) ?: return AIProviderType.OPENAI
        return runCatching { AIProviderType.valueOf(stored) }.getOrDefault(AIProviderType.OPENAI)
    }

    /**
     * Older builds kept one shared key/model/model list for whichever provider was active. Move those
     * values into the provider-specific slot once, so upgrading does not lose the current settings,
     * while other providers start from an empty slot instead of inheriting them.
     */
    private fun migrateLegacyProviderSlots() {
        if (prefs.getBoolean(KEY_PROVIDER_SLOTS_MIGRATED, false)) return
        val provider = readProvider()
        val editor = prefs.edit()
        if (!prefs.contains(apiKeyKey(provider))) {
            prefs.getString(KEY_LEGACY_AI_API_KEY, null)?.let { editor.putString(apiKeyKey(provider), it) }
        }
        if (!prefs.contains(modelKey(provider))) {
            prefs.getString(KEY_LEGACY_AI_MODEL, null)?.let { editor.putString(modelKey(provider), it) }
        }
        if (!prefs.contains(modelsKey(provider))) {
            prefs.getString(KEY_LEGACY_CACHED_AI_MODELS, null)?.let { editor.putString(modelsKey(provider), it) }
        }
        editor.remove(KEY_LEGACY_AI_API_KEY)
            .remove(KEY_LEGACY_AI_MODEL)
            .remove(KEY_LEGACY_CACHED_AI_MODELS)
            .putBoolean(KEY_PROVIDER_SLOTS_MIGRATED, true)
            .apply()
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
        const val KEY_USER_NICKNAME = "user_nickname"
        const val KEY_PUSH_TOKEN = "push_token"
        const val KEY_LEGACY_AI_API_KEY = "ai_api_key"
        const val KEY_LEGACY_AI_MODEL = "ai_model"
        const val KEY_LEGACY_CACHED_AI_MODELS = "cached_ai_models"
        const val KEY_PROVIDER_SLOTS_MIGRATED = "ai_provider_slots_migrated"
    }
}
