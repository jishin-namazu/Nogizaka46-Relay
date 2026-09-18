package com.nogirelay.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nogirelay.app.BuildConfig
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.push.PushRegistrar
import com.nogirelay.app.translation.AIProviderFactory
import com.nogirelay.app.translation.AIProviderType
import com.nogirelay.app.translation.BlogTranslationManager
import com.nogirelay.app.translation.TranslationManager
import androidx.compose.ui.graphics.Color
import com.nogirelay.app.ui.BrandPurple
import com.nogirelay.app.ui.BrandPurpleBorder
import com.nogirelay.app.ui.BrandPurpleContainer
import com.nogirelay.app.ui.BrandPurpleDark
import com.nogirelay.app.ui.BrandPurpleLight
import com.nogirelay.app.ui.RelayCardShape
import com.nogirelay.app.ui.RelayControlShape
import com.nogirelay.app.ui.SignalGreen
import com.nogirelay.app.ui.navigation.RelayIconButton
import kotlinx.coroutines.launch

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SettingsSection(
    onSettingsChanged: () -> Unit,
    onTestCall: (() -> Unit)? = null,
) {
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
    var pushStatusLabel by remember { mutableStateOf("") }
    var translationSavedLabel by remember { mutableStateOf("") }
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
        translationSavedLabel = "翻译设置已保存"
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

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = BrandPurple,
        focusedLabelColor = BrandPurple,
        cursorColor = BrandPurple,
    )
    val cardShape = RelayCardShape
    val cardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    val cardBorder = BorderStroke(1.dp, BrandPurple.copy(alpha = 0.12f))
    val translationVisibilitySpring = spring<IntSize>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )
    val translationVisibilityFade = tween<Float>(durationMillis = 200)

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Card 1: FCM 推送服务
        Card(
            shape = cardShape,
            colors = cardColors,
            border = cardBorder,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("FCM 推送服务", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = relayUrl,
                    onValueChange = { relayUrl = it },
                    label = { Text("同步服务地址") },
                    placeholder = { Text("https://relay.example.com") },
                    singleLine = true,
                    shape = RelayControlShape,
                    colors = textFieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("访问令牌") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RelayControlShape,
                    colors = textFieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        AppGraph.settings.save(currentSettings())
                        pushStatusLabel = "正在注册 FCM 设备..."
                        PushRegistrar.registerCurrentToken(context) { result ->
                            (context as? android.app.Activity)?.runOnUiThread {
                                pushStatusLabel = result.fold(
                                    onSuccess = { "设备已注册，系统推送已就绪" },
                                    onFailure = { it.message ?: "FCM 设备注册失败" },
                                )
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandPurple, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                ) {
                    Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("保存并注册推送", fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                if (pushStatusLabel.isNotBlank()) {
                    Text(
                        text = pushStatusLabel,
                        color = if (pushStatusLabel.contains("失败")) MaterialTheme.colorScheme.error else SignalGreen,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // Card 2: 昵称设置
        Card(
            shape = cardShape,
            colors = cardColors,
            border = cardBorder,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("个性化昵称", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = userNickname,
                    onValueChange = { userNickname = it },
                    label = { Text("你的昵称") },
                    placeholder = { Text("例如：小明") },
                    singleLine = true,
                    shape = RelayControlShape,
                    colors = textFieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = ::saveNickname,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrandPurple,
                        contentColor = Color.White,
                    ),
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                ) {
                    Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("保存昵称", fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                if (nicknameLabel.isNotBlank()) {
                    Text(nicknameLabel, color = SignalGreen, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        // Card 3: AI翻译
        Card(
            shape = cardShape,
            colors = cardColors,
            border = cardBorder,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "AI翻译",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
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
                            onSettingsChanged()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = BrandPurple,
                        ),
                    )
                }

                AnimatedVisibility(
                    visible = translationEnabled,
                    enter = expandVertically(
                        expandFrom = Alignment.Top,
                        animationSpec = translationVisibilitySpring,
                    ) + fadeIn(
                        animationSpec = translationVisibilityFade,
                    ),
                    exit = shrinkVertically(
                        shrinkTowards = Alignment.Top,
                        animationSpec = translationVisibilitySpring,
                    ) + fadeOut(
                        animationSpec = translationVisibilityFade,
                    ),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.padding(top = 14.dp),
                    ) {
                        Box {
                            OutlinedTextField(
                                value = aiProvider.displayName,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("AI 供应商") },
                                trailingIcon = {
                                    Icon(Icons.Rounded.ArrowDropDown, contentDescription = "选择供应商")
                                },
                                shape = RelayControlShape,
                                colors = textFieldColors,
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
                        placeholder = { Text("sk-... 或对应 API Key") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        shape = RelayControlShape,
                        colors = textFieldColors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Button(
                            onClick = ::saveTranslationSettings,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BrandPurple, contentColor = Color.White),
                            modifier = Modifier.weight(1f).height(44.dp),
                        ) {
                            Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("保存配置", fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                        FilledTonalButton(
                            onClick = ::validateApiKey,
                            enabled = !validatingApiKey,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = BrandPurpleContainer,
                                contentColor = BrandPurpleDark,
                            ),
                            modifier = Modifier.weight(1f).height(44.dp),
                        ) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(if (validatingApiKey) "校验中…" else "校验模型", fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                    if (modelStatus.isNotBlank()) {
                        Text(modelStatus, color = BrandPurpleDark, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
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
                            shape = RelayControlShape,
                            colors = textFieldColors,
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
                                        translationSavedLabel = "翻译模型已保存"
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
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    if (modelOptions.isEmpty() && aiApiKey.isNotBlank()) {
                        Text(
                            "请点击\"校验模型\"获取可用模型列表",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    if (translationSavedLabel.isNotBlank()) {
                        Text(translationSavedLabel, color = SignalGreen, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                    }
                    }
                }
            }
        }

        // Card 4: 全屏来电测试
        if (onTestCall != null && !BuildConfig.SIMPLE_UI) {
            Card(
                shape = cardShape,
                colors = cardColors,
                border = cardBorder,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("全屏来电测试", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = onTestCall,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandPurple, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                    ) {
                        Icon(Icons.Rounded.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("测试全屏来电", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
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
