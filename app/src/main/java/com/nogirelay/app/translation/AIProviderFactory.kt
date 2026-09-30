package com.nogirelay.app.translation

import com.nogirelay.app.translation.providers.ClaudeProvider
import com.nogirelay.app.translation.providers.DeepSeekProvider
import com.nogirelay.app.translation.providers.GLMProvider
import com.nogirelay.app.translation.providers.GeminiProvider
import com.nogirelay.app.translation.providers.GrokProvider
import com.nogirelay.app.translation.providers.HunYuanProvider
import com.nogirelay.app.translation.providers.KimiProvider
import com.nogirelay.app.translation.providers.MiMoProvider
import com.nogirelay.app.translation.providers.MiniMaxProvider
import com.nogirelay.app.translation.providers.OpenAIProvider
import com.nogirelay.app.translation.providers.QwenProvider

object AIProviderFactory {
    fun getProvider(type: AIProviderType): AIProvider {
        return when (type) {
            AIProviderType.OPENAI -> OpenAIProvider()
            AIProviderType.KIMI -> KimiProvider()
            AIProviderType.CLAUDE -> ClaudeProvider()
            AIProviderType.DEEPSEEK -> DeepSeekProvider()
            AIProviderType.GLM -> GLMProvider()
            AIProviderType.GEMINI -> GeminiProvider()
            AIProviderType.QWEN -> QwenProvider()
            AIProviderType.GROK -> GrokProvider()
            AIProviderType.MINIMAX -> MiniMaxProvider()
            AIProviderType.MIMO -> MiMoProvider()
            AIProviderType.HUNYUAN -> HunYuanProvider()
        }
    }
}
