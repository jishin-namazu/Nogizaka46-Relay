package com.nogirelay.app.data

import com.nogirelay.app.translation.AIProviderType
import com.nogirelay.app.translation.AIModel

data class AppSettings(
    val relayUrl: String = "",
    val accessToken: String = "",
    val aiProvider: AIProviderType = AIProviderType.OPENAI,
    val aiApiKey: String = "",
    val aiModel: String = "",
    val cachedAiModels: List<AIModel> = emptyList(),
    val translationEnabled: Boolean = false,
    /**
     * 打开后自动把历史积压的消息也翻掉；关闭（默认）时只自动翻译新收到的消息。
     * 手动操作（单条重新翻译、"重新翻译全部"）不受此开关限制。
     */
    val messageFullTranslation: Boolean = false,
    /** 博客版的全量开关，语义同 [messageFullTranslation]。 */
    val blogFullTranslation: Boolean = false,
    val userNickname: String = "",
)
