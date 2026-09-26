package com.nogirelay.app.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.MutableTransitionState
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
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
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
    var messageFullTranslation by remember { mutableStateOf(initial.messageFullTranslation) }
    var blogFullTranslation by remember { mutableStateOf(initial.blogFullTranslation) }
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
    var showRetranslateAllDialog by remember { mutableStateOf(false) }
    var retranslateStatus by remember { mutableStateOf("") }
    // 列表里的支持标记与模型输入框下方读的是请求构造器同一个查询，
    // 标签始终与实际发出的请求一致。
    val selectedProvider = remember(aiProvider) { AIProviderFactory.getProvider(aiProvider) }
    val selectedModelSupport = remember(selectedProvider, aiModel) {
        selectedProvider.jsonOutputSupport(aiModel)
    }
    // 模型列表按接口返回的原始顺序展示会很乱，选择器统一按名称排序。
    val sortedModelOptions = remember(modelOptions) {
        modelOptions.sortedBy { it.displayName.lowercase() }
    }

    // 除昵称外正在编辑的全部设置：昵称有自己的输入框与保存按钮，
    // 这里保存其它设置时不会带上半途输入的昵称。
    fun currentSettings() = AppGraph.settings.read().copy(
        relayUrl = relayUrl,
        accessToken = token,
        aiProvider = aiProvider,
        aiApiKey = aiApiKey,
        aiModel = aiModel,
        cachedAiModels = modelOptions,
        translationEnabled = translationEnabled,
        messageFullTranslation = messageFullTranslation,
        blogFullTranslation = blogFullTranslation,
    )

    /**
     * 重新翻译所有已存消息与 BLOG。译文不再把设备昵称写死之后需要执行一次：
     * 旧数据里存的是字面昵称，重新翻译后才会换成占位符形式。
     */
    fun retranslateAll() {
        retranslateStatus = "已标记全部内容，正在后台按当前模型重新翻译…"
        TranslationManager.retranslateEverything(context)
        onSettingsChanged()
    }

    fun saveNickname() {
        val trimmed = userNickname.trim()
        AppGraph.settings.save(AppGraph.settings.read().copy(userNickname = trimmed))
        userNickname = trimmed
        // 译文保留「%%%」占位符、渲染时才套用昵称，所以改昵称只需刷新，
        // 已翻译的内容不会过期。
        nicknameLabel = "昵称已保存"
        onSettingsChanged()
    }

    /**
     * 保存 AI 翻译相关设置。[showSavedLabel] 为 false 时不弹出"翻译设置已保存"，
     * 供全量翻译开关使用：开关本身有明确的状态反馈，不需要额外的保存提示。
     */
    fun saveTranslationSettings(showSavedLabel: Boolean = true) {
        AppGraph.settings.save(currentSettings())
        TranslationManager.resetRetries()
        BlogTranslationManager.resetRetries()
        TranslationManager.enqueue(context)
        BlogTranslationManager.enqueuePending(context)
        if (showSavedLabel) translationSavedLabel = "翻译设置已保存"
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
                        // PushRegistrar 保证结果在主线程且只回调一次（含超时兜底），
                        // 这里不再依赖 Activity 类型转换，避免窗口 Context 变化后结果丢失。
                        PushRegistrar.registerCurrentToken(context) { result ->
                            pushStatusLabel = result.fold(
                                onSuccess = { "设备已注册，系统推送已就绪" },
                                onFailure = { it.message?.takeIf(String::isNotBlank) ?: "FCM 设备注册失败" },
                            )
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
                AnimatedStatusText(
                    text = pushStatusLabel,
                    color = if (pushStatusLabel.contains("失败")) MaterialTheme.colorScheme.error else SignalGreen,
                    fontSize = 13.sp,
                )
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
                AnimatedStatusText(
                    text = nicknameLabel,
                    color = SignalGreen,
                    fontSize = 13.sp,
                )
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
                    AnimatedStatusText(text = modelStatus, color = BrandPurpleDark)
                    Box {
                        OutlinedTextField(
                            value = aiModel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("翻译模型") },
                            placeholder = { Text("请先校验 API Key 并选择模型") },
                            trailingIcon = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    // 与选择器条目右侧的标签保持一致：选中的模型直接继承显示。
                                    if (selectedModelSupport.isSupported) {
                                        SupportBadge(selectedModelSupport.label)
                                    }
                                    Icon(
                                        imageVector = Icons.Rounded.ArrowDropDown,
                                        contentDescription = "选择翻译模型",
                                        tint = if (modelOptions.isNotEmpty()) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                        },
                                    )
                                }
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
                            sortedModelOptions.forEach { model ->
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
                    if (modelOptions.isEmpty() && aiApiKey.isNotBlank()) {
                        AnimatedStatusText(
                            text = "请点击\"校验模型\"获取可用模型列表",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    AnimatedStatusText(text = translationSavedLabel, color = SignalGreen)
                    FullTranslationToggle(
                        title = "消息全量翻译",
                        description = "开启后自动翻译所有历史未翻译的消息；关闭时只自动翻译新收到的消息，查看历史消息需要手动点击翻译。",
                        checked = messageFullTranslation,
                        onCheckedChange = {
                            messageFullTranslation = it
                            saveTranslationSettings(showSavedLabel = false)
                        },
                    )
                    FullTranslationToggle(
                        title = "博客全量翻译",
                        description = "开启后自动翻译所有历史未翻译的博客；关闭时只自动翻译新发布的博客和点开阅读的博客。",
                        checked = blogFullTranslation,
                        onCheckedChange = {
                            blogFullTranslation = it
                            saveTranslationSettings(showSavedLabel = false)
                        },
                    )
                    OutlinedButton(
                        onClick = { showRetranslateAllDialog = true },
                        enabled = aiModel.isNotBlank() && aiApiKey.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = BrandPurple),
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("重新翻译全部", fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                    AnimatedStatusText(text = retranslateStatus, color = BrandPurpleDark)
                    if (showRetranslateAllDialog) {
                        AlertDialog(
                            onDismissRequest = { showRetranslateAllDialog = false },
                            shape = RoundedCornerShape(20.dp),
                            title = { Text("重新翻译全部", fontWeight = FontWeight.Bold) },
                            text = {
                                Text("将清空本机所有消息与博客的译文并重新翻译。内容较多时耗时较久并消耗 API 额度，确定继续？")
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showRetranslateAllDialog = false
                                        retranslateAll()
                                    },
                                    colors = ButtonDefaults.textButtonColors(contentColor = BrandPurple),
                                ) { Text("开始", fontWeight = FontWeight.Bold) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showRetranslateAllDialog = false }) { Text("取消") }
                            },
                        )
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

/**
 * 全量翻译开关的一行：开关关闭（默认）时自动流程只处理新到内容，打开后才翻历史积压。
 * 手动"重新翻译全部"不受这里影响；博客打开详情页时的按需翻译同样不受影响
 * （见 BlogScreen 详情页的 `BlogTranslationManager.enqueue` 调用），因此博客侧的说明要写明这一点。
 */
@Composable
private fun FullTranslationToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
        Spacer(Modifier.size(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = BrandPurple,
            ),
        )
    }
}

/** 表示厂商或模型会发出 API 级结构化输出请求的小绿标。 */
@Composable
fun SupportBadge(label: String) {
    Text(
        text = label,
        color = SignalGreen,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

/** 状态文字展开用的弹簧：高度变化不能过冲，否则文字块会顶出去再弹回来。 */
private val statusVisibilitySpring = spring<IntSize>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium,
)

/** 状态文字切换用的弹簧：不抖动、跟上节奏即可。 */
private val statusTextSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/** 状态文字切换时的位移弹簧：与高度动画一致，不做回弹，避免文字被裁切抖动。 */
private val statusSlideSpring = spring<IntOffset>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium,
)

private val statusFadeIn = tween<Float>(durationMillis = 180)
private val statusFadeOut = tween<Float>(durationMillis = 140)

/**
 * 设置抽屉里所有弹出文字的公共外壳：
 * 出现时用弹簧展开并淡入，文案变化（例如"正在注册"变成结果）也用弹簧淡入切换，
 * 避免文字突然出现或突然跳变；文字为空时不占位，直接移除。
 */
@Composable
private fun AnimatedStatusText(
    text: String,
    color: Color,
    fontSize: TextUnit = 12.5.sp,
    fontWeight: FontWeight = FontWeight.Medium,
) {
    // 隐藏时整块移除：否则 Column 的 spacedBy 会在空元素处留下多余间距。
    if (text.isBlank()) return
    // 首次出现时从收起状态弹出，弹簧展开 + 淡入。
    val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = visibleState,
        enter = expandVertically(
            expandFrom = Alignment.Top,
            animationSpec = statusVisibilitySpring,
        ) + fadeIn(animationSpec = statusFadeIn),
    ) {
        AnimatedContent(
            targetState = text,
            transitionSpec = {
                (
                    fadeIn(animationSpec = statusTextSpring) +
                        slideInVertically(animationSpec = statusSlideSpring) { height -> height / 2 }
                    ).togetherWith(fadeOut(animationSpec = statusFadeOut))
            },
            label = "settings_status_text",
        ) { value ->
            Text(
                text = value,
                color = color,
                fontSize = fontSize,
                fontWeight = fontWeight,
            )
        }
    }
}
