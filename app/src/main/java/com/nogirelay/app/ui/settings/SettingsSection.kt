package com.nogirelay.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nogirelay.app.BuildConfig
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.push.PushRegistrar
import com.nogirelay.app.translation.AIProviderFactory
import com.nogirelay.app.translation.AIProviderType
import com.nogirelay.app.translation.BlogTranslationManager
import com.nogirelay.app.translation.TranslationManager
import com.nogirelay.app.ui.RelayControlShape
import com.nogirelay.app.ui.SignalGreen
import com.nogirelay.app.ui.navigation.RelayIconButton
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SettingsSection(onSettingsChanged: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val initial = remember { AppGraph.settings.read() }
    var relayUrl by remember { mutableStateOf(initial.relayUrl) }
    var token by remember { mutableStateOf(initial.accessToken) }
    var aiProvider by remember { mutableStateOf(initial.aiProvider) }
    var aiApiKey by remember { mutableStateOf(initial.aiApiKey) }
    var aiModel by remember { mutableStateOf(initial.aiModel) }
    var modelOptions by remember { mutableStateOf(initial.cachedAiModels) }
    var translationEnabled by remember { mutableStateOf(initial.translationEnabled) }
    var userNickname by remember { mutableStateOf(initial.userNickname) }
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var providerFieldWidthPx by remember { mutableIntStateOf(0) }
    var modelFieldWidthPx by remember { mutableIntStateOf(0) }
    var savedLabel by remember { mutableStateOf("") }
    var nicknameLabel by remember { mutableStateOf("") }
    var modelStatus by remember { mutableStateOf("") }
    var validatingApiKey by remember { mutableStateOf(false) }
    // The support badge in the lists and under the model field all read the same query the
    // request builder uses, so the label always matches what is actually sent.
    val selectedProvider = remember(aiProvider) { AIProviderFactory.getProvider(aiProvider) }
    val selectedModelSupport = remember(selectedProvider, aiModel) {
        selectedProvider.jsonOutputSupport(aiModel)
    }

    // Everything being edited except the nickname: it has its own field and save button, so saving
    // any other setting here must not pick up a half-typed nickname.
    fun currentSettings() = AppGraph.settings.read().copy(
        relayUrl = relayUrl,
        accessToken = token,
        aiProvider = aiProvider,
        aiApiKey = aiApiKey,
        aiModel = aiModel,
        cachedAiModels = modelOptions,
        translationEnabled = translationEnabled,
    )

    fun saveNickname() {
        val previous = AppGraph.settings.read().userNickname
        val trimmed = userNickname.trim()
        AppGraph.settings.save(AppGraph.settings.read().copy(userNickname = trimmed))
        userNickname = trimmed
        // The nickname is substituted into the text handed to the translator, so anything translated
        // with the old name is stale once it changes. enqueue() is a no-op while 翻译 is switched off.
        if (previous != trimmed) {
            TranslationManager.resetRetries()
            BlogTranslationManager.resetRetries()
            TranslationManager.enqueue(context)
            BlogTranslationManager.enqueuePending(context)
        }
        nicknameLabel = "昵称已保存"
        onSettingsChanged()
    }

    fun saveTranslationSettings() {
        AppGraph.settings.save(currentSettings())
        TranslationManager.resetRetries()
        BlogTranslationManager.resetRetries()
        TranslationManager.enqueue(context)
        BlogTranslationManager.enqueuePending(context)
        savedLabel = "翻译设置已保存"
        onSettingsChanged()
    }

    fun validateApiKey() {
        val key = aiApiKey.trim()
        if (key.isEmpty()) {
            modelStatus = "请先填写 API Key"
            return
        }
        scope.launch {
            validatingApiKey = true
            modelStatus = "正在验证并加载模型..."
            val result = TranslationManager.fetchAvailableModels(aiProvider, key)
            validatingApiKey = false
            result.onSuccess { models ->
                modelOptions = models
                if (aiModel.isNotBlank() && models.none { it.id == aiModel }) {
                    aiModel = ""
                }
                AppGraph.settings.save(currentSettings().copy(cachedAiModels = models))
                modelStatus = "API Key 有效，已加载 ${models.size} 个可用模型"
            }.onFailure { error ->
                modelStatus = error.message ?: "API Key 无效或模型加载失败"
            }
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("FCM 推送服务", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (!BuildConfig.SIMPLE_UI) {
            OutlinedTextField(
                value = relayUrl,
                onValueChange = { relayUrl = it },
                label = { Text("同步服务地址") },
                placeholder = { Text("https://relay.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("访问令牌") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Button(
            onClick = {
                AppGraph.settings.save(currentSettings())
                savedLabel = "正在注册 FCM 设备..."
                PushRegistrar.registerCurrentToken(context) { result ->
                    (context as? android.app.Activity)?.runOnUiThread {
                        savedLabel = result.fold(
                            onSuccess = { "设备已注册，系统推送已就绪" },
                            onFailure = { it.message ?: "FCM 设备注册失败" },
                        )
                    }
                }
            },
            shape = RelayControlShape,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.Save, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(if (BuildConfig.SIMPLE_UI) "注册推送" else "保存并注册推送", maxLines = 1)
        }
        Text("昵称", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = userNickname,
            onValueChange = { userNickname = it },
            label = { Text("你的昵称") },
            placeholder = { Text("用于替换消息中的 %%%") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        FilledTonalButton(
            onClick = ::saveNickname,
            shape = RelayControlShape,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.Save, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("保存", maxLines = 1)
        }
        if (nicknameLabel.isNotBlank()) {
            Text(nicknameLabel, color = SignalGreen, fontSize = 13.sp)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.weight(1f)) {
                Text("翻译", fontWeight = FontWeight.Medium)
            }
            Switch(
                checked = translationEnabled,
                onCheckedChange = {
                    translationEnabled = it
                    AppGraph.settings.save(currentSettings())
                    TranslationManager.resetRetries()
                    BlogTranslationManager.resetRetries()
                    if (it) {
                        TranslationManager.enqueue(context)
                        BlogTranslationManager.enqueuePending(context)
                    }
                    // The list and detail screens cache the setting behind refreshKey, so bump it to
                    // hide or restore the BLOG translations immediately instead of within the next poll.
                    onSettingsChanged()
                },
            )
        }
        Box {
            OutlinedTextField(
                value = aiProvider.displayName,
                onValueChange = {},
                readOnly = true,
                label = { Text("AI 供应商") },
                trailingIcon = {
                    Icon(Icons.Rounded.ArrowDropDown, contentDescription = "选择供应商")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { providerFieldWidthPx = it.width },
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable { providerMenuExpanded = true },
            )
            DropdownMenu(
                expanded = providerMenuExpanded,
                onDismissRequest = { providerMenuExpanded = false },
                modifier = if (providerFieldWidthPx > 0) {
                    Modifier.width(with(density) { providerFieldWidthPx.toDp() })
                } else {
                    Modifier
                },
            ) {
                AIProviderType.values().forEach { provider ->
                    DropdownMenuItem(
                        text = { Text(provider.displayName) },
                        trailingIcon = {
                            if (provider.supportsStructuredOutput) SupportBadge("结构化输出")
                        },
                        onClick = {
                            // Every provider keeps its own API Key, model and cached model list, so
                            // switching back and forth restores what was configured before.
                            aiProvider = provider
                            aiApiKey = AppGraph.settings.apiKeyFor(provider)
                            aiModel = AppGraph.settings.modelFor(provider)
                            modelOptions = AppGraph.settings.cachedModelsFor(provider)
                            AppGraph.settings.save(currentSettings())
                            modelStatus = ""
                            providerMenuExpanded = false
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            value = aiApiKey,
            onValueChange = { aiApiKey = it },
            label = { Text("${aiProvider.displayName} API Key") },
            placeholder = { Text("sk-... 或对应供应商的 API Key") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Button(
                onClick = ::saveTranslationSettings,
                shape = RelayControlShape,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Rounded.Save, contentDescription = null)
                Spacer(Modifier.size(5.dp))
                Text("保存", maxLines = 1)
            }
            OutlinedButton(
                onClick = ::validateApiKey,
                enabled = !validatingApiKey,
                shape = RelayControlShape,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                Spacer(Modifier.size(5.dp))
                Text(if (validatingApiKey) "校验中..." else "校验有效性", maxLines = 1)
            }
        }
        if (modelStatus.isNotBlank()) {
            Text(modelStatus, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        Box {
            OutlinedTextField(
                value = aiModel,
                onValueChange = {},
                readOnly = true,
                label = { Text("翻译模型") },
                placeholder = { Text("请先校验 API Key 并选择模型") },
                trailingIcon = {
                    RelayIconButton(
                        onClick = { modelMenuExpanded = true },
                        enabled = modelOptions.isNotEmpty(),
                        imageVector = Icons.Rounded.ArrowDropDown,
                        contentDescription = "选择翻译模型",
                    )
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { modelFieldWidthPx = it.width },
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(enabled = modelOptions.isNotEmpty()) {
                        modelMenuExpanded = true
                    },
            )
            DropdownMenu(
                expanded = modelMenuExpanded,
                onDismissRequest = { modelMenuExpanded = false },
                modifier = if (modelFieldWidthPx > 0) {
                    Modifier.width(with(density) { modelFieldWidthPx.toDp() })
                } else {
                    Modifier
                },
            ) {
                modelOptions.forEach { model ->
                    val support = selectedProvider.jsonOutputSupport(model.id)
                    DropdownMenuItem(
                        text = { Text(model.displayName) },
                        trailingIcon = { if (support.isSupported) SupportBadge(support.label) },
                        onClick = {
                            aiModel = model.id
                            AppGraph.settings.save(currentSettings().copy(aiModel = model.id))
                            TranslationManager.resetRetries()
                            BlogTranslationManager.resetRetries()
                            TranslationManager.enqueue(context)
                            BlogTranslationManager.enqueuePending(context)
                            savedLabel = "翻译模型已保存"
                            modelMenuExpanded = false
                        },
                    )
                }
            }
        }
        if (aiModel.isNotBlank()) {
            Text(
                text = if (selectedModelSupport.isSupported) {
                    "结构化输出：${selectedModelSupport.label}"
                } else {
                    "结构化输出：不支持，仅用提示词约束"
                },
                color = if (selectedModelSupport.isSupported) SignalGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
        }
        if (modelOptions.isEmpty() && aiApiKey.isNotBlank()) {
            Text(
                "请点击\"校验有效性\"加载可用模型",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
        if (savedLabel.isNotBlank()) {
            Text(savedLabel, color = SignalGreen, fontSize = 13.sp)
        }
    }
}

/** Small green marker for a provider or model that sends an API-level structured-output request. */
@Composable
fun SupportBadge(label: String) {
    Text(
        text = label,
        color = SignalGreen,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
    )
}
