package com.nogirelay.app.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nogirelay.app.BuildConfig
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.IncomingCallStyle
import com.nogirelay.app.push.PushRegistrar
import com.nogirelay.app.translation.AIProviderFactory
import com.nogirelay.app.translation.AIProviderType
import com.nogirelay.app.translation.BlogTranslationManager
import com.nogirelay.app.translation.TranslationManager
import com.nogirelay.app.ui.AiTranslateIcon
import com.nogirelay.app.ui.glass.GlassCapsuleButton
import com.nogirelay.app.ui.glass.GlassColors
import com.nogirelay.app.ui.glass.GlassDialog
import com.nogirelay.app.ui.glass.GlassDialogText
import com.nogirelay.app.ui.glass.GlassDialogTitle
import com.nogirelay.app.ui.glass.GlassPanel
import com.nogirelay.app.ui.glass.GlassPopover
import com.nogirelay.app.ui.glass.GlassPopoverItem
import com.nogirelay.app.ui.glass.GlassSegmentedTabs
import com.nogirelay.app.ui.glass.GlassShapes
import com.nogirelay.app.ui.glass.GlassSwitch
import com.nogirelay.app.ui.glass.GlassTextField
import com.nogirelay.app.ui.glass.GlassTone
import com.nogirelay.app.ui.glass.glassPopoverAnchor
import com.nogirelay.app.ui.glass.rememberGlassPopoverState
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color

/**
 * Settings, rebuilt on the glass system: grouped glass panels, capsule
 * fields, and provider/model pickers that morph out of their anchor field.
 */
@Composable
fun SettingsSection(
    onSettingsChanged: () -> Unit,
    onTestCall: (() -> Unit)? = null,
    header: @Composable () -> Unit,
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
    var incomingCallStyle by remember { mutableStateOf(initial.incomingCallStyle) }
    val providerPopover = rememberGlassPopoverState()
    val modelPopover = rememberGlassPopoverState()
    var providerFieldWidthPx by remember { mutableIntStateOf(0) }
    var modelFieldWidthPx by remember { mutableIntStateOf(0) }
    var pushStatusLabel by remember { mutableStateOf("") }
    var translationSavedLabel by remember { mutableStateOf("") }
    var nicknameLabel by remember { mutableStateOf("") }
    var modelStatus by remember { mutableStateOf("") }
    var validatingApiKey by remember { mutableStateOf(false) }
    var showRetranslateAllDialog by remember { mutableStateOf(false) }
    var retranslateStatus by remember { mutableStateOf("") }

    val selectedProvider = remember(aiProvider) { AIProviderFactory.getProvider(aiProvider) }
    val selectedModelSupport = remember(selectedProvider, aiModel) {
        selectedProvider.jsonOutputSupport(aiModel)
    }

    val sortedModelOptions = remember(modelOptions, modelPopover.expanded) {
        if (modelPopover.expanded) AppGraph.settings.sortedModels(modelOptions) else emptyList()
    }

    fun translationSettingsDraft() = AppGraph.settings.read().copy(
        aiProvider = aiProvider,
        aiApiKey = aiApiKey,
        aiModel = aiModel,
        cachedAiModels = modelOptions,
        translationEnabled = translationEnabled,
        messageFullTranslation = messageFullTranslation,
        blogFullTranslation = blogFullTranslation,
    )

    fun retranslateAll() {
        retranslateStatus = "已标记全部内容，正在后台按当前模型重新翻译…"
        TranslationManager.retranslateEverything(context)
        onSettingsChanged()
    }

    fun saveNickname() {
        val trimmed = userNickname.trim()
        AppGraph.settings.save(AppGraph.settings.read().copy(userNickname = trimmed))
        userNickname = trimmed

        nicknameLabel = "昵称已保存"
        onSettingsChanged()
    }

    fun refreshTranslationWorkers() {
        TranslationManager.resetRetries()
        BlogTranslationManager.resetRetries()
        TranslationManager.enqueue(context)
        BlogTranslationManager.enqueuePending(context)
        onSettingsChanged()
    }

    fun saveTranslationSettings() {
        AppGraph.settings.save(translationSettingsDraft())
        translationSavedLabel = "翻译设置已保存"
        refreshTranslationWorkers()
    }

    fun validateApiKey() {
        val key = aiApiKey.trim()
        if (key.isEmpty()) {
            modelStatus = "请先填写 API Key"
            return
        }
        val provider = aiProvider
        scope.launch {
            validatingApiKey = true
            modelStatus = "正在验证并加载模型..."
            val result = TranslationManager.fetchAvailableModels(provider, key)
            validatingApiKey = false
            // A response for a previous draft must not replace the current picker.
            if (aiProvider != provider || aiApiKey.trim() != key) return@launch
            result.onSuccess { models ->
                modelOptions = models
                if (aiModel.isNotBlank() && models.none { it.id == aiModel }) {
                    aiModel = ""
                }
                translationSavedLabel = ""
                modelStatus = "API Key 有效，已加载 ${models.size} 个可用模型"
            }.onFailure { error ->
                modelStatus = error.message ?: "API Key 无效或模型加载失败"
            }
        }
    }

    val translationVisibilitySpring = spring<IntSize>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )
    val translationVisibilityFade = tween<Float>(durationMillis = 200)

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        overscrollEffect = null,
    ) {
        item(key = "settings-header", contentType = "header") { header() }
        item(key = "push", contentType = "settings-card") {
            GlassPanel(
                shape = GlassShapes.Card,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "FCM 推送服务",
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = GlassColors.Ink,
                    )
                    GlassTextField(
                        value = relayUrl,
                        onValueChange = { relayUrl = it },
                        label = "同步服务地址",
                        placeholder = "https://relay.example.com",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    GlassTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = "访问令牌",
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    GlassCapsuleButton(
                        onClick = {
                            AppGraph.settings.save(AppGraph.settings.read().copy(
                                relayUrl = relayUrl,
                                accessToken = token,
                            ))
                            pushStatusLabel = "正在注册 FCM 设备..."

                            PushRegistrar.registerCurrentToken(context) { result ->
                                pushStatusLabel = result.fold(
                                    onSuccess = { "设备已注册，系统推送已就绪" },
                                    onFailure = { it.message?.takeIf(String::isNotBlank) ?: "FCM 设备注册失败" },
                                )
                            }
                        },
                        tone = GlassTone.Accent,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "保存并注册推送",
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    AnimatedStatusText(
                        text = pushStatusLabel,
                        color = if (pushStatusLabel.contains("失败")) GlassColors.Danger else GlassColors.Success,
                        fontSize = 13.sp,
                    )
                }
            }
        }
        item(key = "nickname", contentType = "settings-card") {
            GlassPanel(
                shape = GlassShapes.Card,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "个性化昵称",
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = GlassColors.Ink,
                    )
                    GlassTextField(
                        value = userNickname,
                        onValueChange = { userNickname = it },
                        label = "你的昵称",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    GlassCapsuleButton(
                        onClick = ::saveNickname,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("保存昵称", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    }
                    AnimatedStatusText(
                        text = nicknameLabel,
                        color = GlassColors.Success,
                        fontSize = 13.sp,
                    )
                }
            }
        }
        item(key = "translation", contentType = "settings-card") {
            GlassPanel(
                shape = GlassShapes.Card,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "AI翻译",
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = GlassColors.Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        GlassSwitch(
                            checked = translationEnabled,
                            label = "AI翻译",
                            onCheckedChange = {
                                translationEnabled = it
                                AppGraph.settings.save(AppGraph.settings.read().copy(translationEnabled = it))
                                TranslationManager.resetRetries()
                                BlogTranslationManager.resetRetries()
                                if (it) {
                                    TranslationManager.enqueue(context)
                                    BlogTranslationManager.enqueuePending(context)
                                }
                                onSettingsChanged()
                            },
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
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(top = 10.dp),
                        ) {
                            Box {
                                GlassTextField(
                                    value = aiProvider.displayName,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = "AI 供应商",
                                    trailingContent = {
                                        Icon(
                                            Icons.Rounded.ArrowDropDown,
                                            contentDescription = "选择供应商",
                                            tint = GlassColors.InkSecondary,
                                        )
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onSizeChanged { providerFieldWidthPx = it.width }
                                        .glassPopoverAnchor(providerPopover),
                                )
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .clickable { providerPopover.open() },
                                )
                                GlassPopover(
                                    state = providerPopover,
                                    width = if (providerFieldWidthPx > 0) {
                                        with(density) { providerFieldWidthPx.toDp() }
                                    } else {
                                        280.dp
                                    },
                                ) {
                                    AIProviderType.values().forEach { provider ->
                                        GlassPopoverItem(
                                            label = provider.displayName +
                                                if (provider.supportsStructuredOutput) "  · 结构化输出" else "",
                                            onClick = {
                                                aiProvider = provider
                                                aiApiKey = AppGraph.settings.apiKeyFor(provider)
                                                aiModel = AppGraph.settings.modelFor(provider)
                                                modelOptions = AppGraph.settings.cachedModelsFor(provider)
                                                translationSavedLabel = ""
                                                modelStatus = ""
                                                providerPopover.dismiss(scope)
                                            },
                                        )
                                    }
                                }
                            }
                            GlassTextField(
                                value = aiApiKey,
                                onValueChange = {
                                    aiApiKey = it
                                    translationSavedLabel = ""
                                    modelStatus = ""
                                },
                                label = "${aiProvider.displayName} API Key",
                                placeholder = "sk-... 或对应 API Key",
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                GlassCapsuleButton(
                                    onClick = { saveTranslationSettings() },
                                    tone = GlassTone.Accent,
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                ) {
                                    Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.size(6.dp))
                                    Text("保存配置", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                }
                                GlassCapsuleButton(
                                    onClick = ::validateApiKey,
                                    enabled = !validatingApiKey,
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                ) {
                                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.size(6.dp))
                                    Text(
                                        if (validatingApiKey) "校验中…" else "校验模型",
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                }
                            }
                            AnimatedStatusText(text = modelStatus, color = GlassColors.AccentInk)
                            Box {
                                GlassTextField(
                                    value = aiModel,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = "翻译模型",
                                    placeholder = "请先校验 API Key 并选择模型",
                                    trailingContent = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            if (selectedModelSupport.isSupported) {
                                                SupportBadge(selectedModelSupport.label)
                                            }
                                            Icon(
                                                imageVector = Icons.Rounded.ArrowDropDown,
                                                contentDescription = "选择翻译模型",
                                                tint = if (modelOptions.isNotEmpty()) {
                                                    GlassColors.InkSecondary
                                                } else {
                                                    GlassColors.InkTertiary
                                                },
                                            )
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onSizeChanged { modelFieldWidthPx = it.width }
                                        .glassPopoverAnchor(modelPopover),
                                )
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .clickable(enabled = modelOptions.isNotEmpty()) {
                                            modelPopover.open()
                                        },
                                )
                                GlassPopover(
                                    state = modelPopover,
                                    width = if (modelFieldWidthPx > 0) {
                                        with(density) { modelFieldWidthPx.toDp() }
                                    } else {
                                        280.dp
                                    },
                                ) {
                                    sortedModelOptions.forEach { model ->
                                        val support = selectedProvider.jsonOutputSupport(model.id)
                                        GlassPopoverItem(
                                            label = model.displayName,
                                            // Match the field's 16dp start and 8dp end insets;
                                            // the popup itself already contributes 6dp.
                                            contentPadding = PaddingValues(start = 10.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
                                            trailingContent = {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                ) {
                                                    if (support.isSupported) SupportBadge(support.label)
                                                    // Reserve the same space as the field's arrow.
                                                    Spacer(Modifier.size(24.dp))
                                                }
                                            },
                                            onClick = {
                                                aiModel = model.id
                                                translationSavedLabel = ""
                                                modelPopover.dismiss(scope)
                                            },
                                        )
                                    }
                                }
                            }
                            if (modelOptions.isEmpty() && aiApiKey.isNotBlank()) {
                                AnimatedStatusText(
                                    text = "请点击\"校验模型\"获取可用模型列表",
                                    color = GlassColors.InkSecondary,
                                    fontSize = 12.sp,
                                )
                            }
                            AnimatedStatusText(text = translationSavedLabel, color = GlassColors.Success)
                            FullTranslationToggle(
                                title = "消息全量翻译",
                                description = "开启后自动翻译所有历史未翻译的消息；关闭时只自动翻译新收到的消息，查看历史消息需要手动点击翻译。",
                                checked = messageFullTranslation,
                                onCheckedChange = {
                                    messageFullTranslation = it
                                    AppGraph.settings.save(AppGraph.settings.read().copy(messageFullTranslation = it))
                                    refreshTranslationWorkers()
                                },
                            )
                            FullTranslationToggle(
                                title = "博客全量翻译",
                                description = "开启后自动翻译所有历史未翻译的博客；关闭时只自动翻译新发布的博客和点开阅读的博客。",
                                checked = blogFullTranslation,
                                onCheckedChange = {
                                    blogFullTranslation = it
                                    AppGraph.settings.save(AppGraph.settings.read().copy(blogFullTranslation = it))
                                    refreshTranslationWorkers()
                                },
                            )
                            GlassCapsuleButton(
                                onClick = { showRetranslateAllDialog = true },
                                enabled = aiModel.isNotBlank() && aiApiKey.isNotBlank(),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("重新翻译全部", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            }
                            AnimatedStatusText(text = retranslateStatus, color = GlassColors.AccentInk)
                            if (showRetranslateAllDialog) {
                                GlassDialog(
                                    onDismissRequest = { showRetranslateAllDialog = false },
                                ) {
                                    GlassDialogTitle("重新翻译全部")
                                    Spacer(Modifier.height(8.dp))
                                    GlassDialogText("将清空本机所有消息与博客的译文并重新翻译。内容较多时耗时较久并消耗 API 额度，确定继续？")
                                    Spacer(Modifier.height(18.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        GlassCapsuleButton(
                                            onClick = { showRetranslateAllDialog = false },
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Text("取消", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                        }
                                        GlassCapsuleButton(
                                            onClick = {
                                                showRetranslateAllDialog = false
                                                retranslateAll()
                                            },
                                            tone = GlassTone.Accent,
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Text("开始", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        item(key = "incoming-call-style", contentType = "settings-card") {
            GlassPanel(
                shape = GlassShapes.Card,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "来电页面",
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = GlassColors.Ink,
                    )

                    GlassSegmentedTabs(
                        labels = listOf("经典", "液态玻璃"),
                        selectedIndex = if (incomingCallStyle == IncomingCallStyle.CLASSIC) 0 else 1,
                        onSelected = { index ->
                            incomingCallStyle = if (index == 0) IncomingCallStyle.CLASSIC else IncomingCallStyle.LIQUID_GLASS
                            AppGraph.settings.save(AppGraph.settings.read().copy(incomingCallStyle = incomingCallStyle))
                            onSettingsChanged()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        if (incomingCallStyle == IncomingCallStyle.LIQUID_GLASS) {
                            "全屏写真与悬浮玻璃按钮，下次来电时生效"
                        } else {
                            "经典写真布局与来电控件，下次来电时生效"
                        },
                        color = GlassColors.InkSecondary,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (onTestCall != null && !BuildConfig.SIMPLE_UI) {
            item(key = "test-call", contentType = "settings-card") {
                GlassPanel(
                    shape = GlassShapes.Card,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "全屏来电测试",
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = GlassColors.Ink,
                        )
                        GlassCapsuleButton(
                            onClick = onTestCall,
                            tone = GlassTone.Accent,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Rounded.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("测试全屏来电", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        }
                    }
                }
            }
        }
        item(key = "settings-navigation-inset", contentType = "inset") {
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

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
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = GlassColors.Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                description,
                color = GlassColors.InkSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
        Spacer(Modifier.size(10.dp))
        GlassSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            label = title,
        )
    }
}

@Composable
fun SupportBadge(label: String) {
    Text(
        text = label,
        color = GlassColors.Success,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        softWrap = false,
    )
}

private val statusVisibilitySpring = spring<IntSize>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium,
)

private val statusTextSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

private val statusSlideSpring = spring<IntOffset>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium,
)

private val statusFadeIn = tween<Float>(durationMillis = 180)
private val statusFadeOut = tween<Float>(durationMillis = 140)

@Composable
private fun AnimatedStatusText(
    text: String,
    color: Color,
    fontSize: TextUnit = 12.5.sp,
    fontWeight: FontWeight = FontWeight.Medium,
) {
    if (text.isBlank()) return

    val visibleState = remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
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


