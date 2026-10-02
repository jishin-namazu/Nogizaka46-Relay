package com.nogirelay.app.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nogirelay.app.BuildConfig
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.IncomingCallStyle
import com.nogirelay.app.data.transfer.ExportKind
import com.nogirelay.app.push.PushRegistrar
import com.nogirelay.app.translation.AIProviderFactory
import com.nogirelay.app.translation.AIProviderType
import com.nogirelay.app.translation.BlogTranslationManager
import com.nogirelay.app.translation.TranslationManager
import com.nogirelay.app.ui.SyncGlyph
import com.nogirelay.app.ui.glass.GlassCapsuleButton
import com.nogirelay.app.ui.glass.GlassColors
import com.nogirelay.app.ui.glass.GlassDepths
import com.nogirelay.app.ui.glass.GlassDetailHeader
import com.nogirelay.app.ui.glass.GlassDialog
import com.nogirelay.app.ui.glass.GlassDialogText
import com.nogirelay.app.ui.glass.GlassDialogTitle
import com.nogirelay.app.ui.glass.GlassMetrics
import com.nogirelay.app.ui.glass.GlassPanel
import com.nogirelay.app.ui.glass.GlassPopover
import com.nogirelay.app.ui.glass.GlassPopoverItem
import com.nogirelay.app.ui.glass.GlassSegmentedTabs
import com.nogirelay.app.ui.glass.GlassShapes
import com.nogirelay.app.ui.glass.GlassSwitch
import com.nogirelay.app.ui.glass.GlassTextField
import com.nogirelay.app.ui.glass.GlassTone
import com.nogirelay.app.ui.glass.GlassType
import com.nogirelay.app.ui.glass.glassPopoverAnchor
import com.nogirelay.app.ui.glass.rememberGlassPopoverState
import kotlinx.coroutines.launch

/**
 * The four settings pages. Each page owns only its own drafts and saves them
 * itself; [SettingsScreen] hosts them and the group index.
 */

/** A settings page: fixed detail header over a scrolling column of cards. */
@Composable
internal fun SettingsPageScaffold(
    title: String,
    onBack: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        GlassDetailHeader(
            title = title,
            onBack = onBack,
            backContentDescription = "返回设置",
        )
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
        ) {
            content()
            item(key = "settings-navigation-inset", contentType = "inset") {
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
}

/** One glass card on a settings page, titled. */
@Composable
internal fun SettingsCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassPanel(
        shape = GlassShapes.Card,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    title,
                    style = GlassType.Headline,
                    fontWeight = FontWeight.Bold,
                    color = GlassColors.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                trailing?.invoke()
            }
            content()
        }
    }
}

// ---------------------------------------------------------------------------
// 连接: push service and nickname
// ---------------------------------------------------------------------------

@Composable
internal fun ConnectionSettingsPage(
    onBack: () -> Unit,
    onSettingsChanged: () -> Unit,
) {
    val context = LocalContext.current
    val initial = remember { AppGraph.settings.read() }
    var relayUrl by remember { mutableStateOf(initial.relayUrl) }
    var token by remember { mutableStateOf(initial.accessToken) }
    var userNickname by remember { mutableStateOf(initial.userNickname) }
    var pushStatusLabel by remember { mutableStateOf("") }
    var nicknameLabel by remember { mutableStateOf("") }

    fun saveNickname() {
        val trimmed = userNickname.trim()
        AppGraph.settings.save(AppGraph.settings.read().copy(userNickname = trimmed))
        userNickname = trimmed
        nicknameLabel = "昵称已保存"
        onSettingsChanged()
    }

    SettingsPageScaffold(title = "连接", onBack = onBack) {
        item(key = "push", contentType = "settings-card") {
            SettingsCard(title = "FCM 推送服务") {
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
                        AppGraph.settings.save(
                            AppGraph.settings.read().copy(
                                relayUrl = relayUrl,
                                accessToken = token,
                            ),
                        )
                        pushStatusLabel = "正在注册 FCM 设备..."

                        PushRegistrar.registerCurrentToken(context) { result ->
                            pushStatusLabel = result.fold(
                                onSuccess = { "设备已注册，系统推送已就绪" },
                                onFailure = { it.message?.takeIf(String::isNotBlank) ?: "FCM 设备注册失败" },
                            )
                            onSettingsChanged()
                        }
                    },
                    tone = GlassTone.Accent,
                    modifier = Modifier.fillMaxWidth(),
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
                )
            }
        }
        item(key = "nickname", contentType = "settings-card") {
            SettingsCard(title = "个性化昵称") {
                Text(
                    "成员消息里称呼你的占位会替换为这个昵称。",
                    style = GlassType.Footnote,
                    color = GlassColors.InkSecondary,
                )
                GlassTextField(
                    value = userNickname,
                    onValueChange = { userNickname = it },
                    label = "你的昵称",
                    modifier = Modifier.fillMaxWidth(),
                )
                GlassCapsuleButton(
                    onClick = ::saveNickname,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("保存昵称", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
                AnimatedStatusText(
                    text = nicknameLabel,
                    color = GlassColors.Success,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 翻译: AI provider, model and translation scope
// ---------------------------------------------------------------------------

@Composable
internal fun TranslationSettingsPage(
    onBack: () -> Unit,
    onSettingsChanged: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val initial = remember { AppGraph.settings.read() }
    var aiProvider by remember { mutableStateOf(initial.aiProvider) }
    var aiApiKey by remember { mutableStateOf(initial.aiApiKey) }
    var aiModel by remember { mutableStateOf(initial.aiModel) }
    var modelOptions by remember { mutableStateOf(initial.cachedAiModels) }
    var translationEnabled by remember { mutableStateOf(initial.translationEnabled) }
    var messageFullTranslation by remember { mutableStateOf(initial.messageFullTranslation) }
    var blogFullTranslation by remember { mutableStateOf(initial.blogFullTranslation) }
    val providerPopover = rememberGlassPopoverState()
    val modelPopover = rememberGlassPopoverState()
    var providerFieldWidthPx by remember { mutableIntStateOf(0) }
    var modelFieldWidthPx by remember { mutableIntStateOf(0) }
    var translationSavedLabel by remember { mutableStateOf("") }
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

    SettingsPageScaffold(title = "翻译", onBack = onBack) {
        item(key = "translation", contentType = "settings-card") {
            SettingsCard(
                title = "AI 翻译",
                trailing = {
                    GlassSwitch(
                        checked = translationEnabled,
                        label = "AI 翻译",
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
                },
            ) {
                if (!translationEnabled) {
                    Text(
                        "开启后，消息与博客会按所选模型自动翻译为中文。",
                        style = GlassType.Footnote,
                        color = GlassColors.InkSecondary,
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
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("保存配置", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            }
                            GlassCapsuleButton(
                                onClick = ::validateApiKey,
                                enabled = !validatingApiKey,
                                modifier = Modifier.weight(1f),
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
                                style = GlassType.Footnote,
                            )
                        }
                        AnimatedStatusText(text = translationSavedLabel, color = GlassColors.Success)
                    }
                }
            }
        }
        if (translationEnabled) {
            item(key = "translation-scope", contentType = "settings-card") {
                SettingsCard(title = "翻译范围") {
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
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("重新翻译全部", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    }
                    AnimatedStatusText(text = retranslateStatus, color = GlassColors.AccentInk)
                }
            }
        }
    }

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

// ---------------------------------------------------------------------------
// 来电: call page style, permissions and a test call
// ---------------------------------------------------------------------------

@Composable
internal fun CallSettingsPage(
    fullScreenGranted: Boolean,
    overlayGranted: Boolean,
    onOpenFullScreenSettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onTestCall: () -> Unit,
    onBack: () -> Unit,
    onSettingsChanged: () -> Unit,
) {
    var incomingCallStyle by remember { mutableStateOf(AppGraph.settings.read().incomingCallStyle) }

    SettingsPageScaffold(title = "来电", onBack = onBack) {
        item(key = "incoming-call-style", contentType = "settings-card") {
            SettingsCard(title = "来电页面") {
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
                    style = GlassType.Footnote,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        item(key = "call-permissions", contentType = "settings-card") {
            SettingsCard(title = "来电权限") {
                PermissionRow(
                    title = "全屏来电",
                    description = if (fullScreenGranted) "允许在锁屏上显示成员来电" else "Android 14 起需要开启特殊权限",
                    granted = fullScreenGranted,
                    onOpen = onOpenFullScreenSettings,
                )
                PermissionRow(
                    title = "后台弹出界面",
                    description = if (overlayGranted) "允许应用在后台直接弹出全屏来电" else "部分设备需要此权限才能弹出后台来电",
                    granted = overlayGranted,
                    onOpen = onOpenOverlaySettings,
                )
            }
        }
        if (!BuildConfig.SIMPLE_UI) {
            item(key = "test-call", contentType = "settings-card") {
                SettingsCard(title = "全屏来电测试") {
                    GlassCapsuleButton(
                        onClick = onTestCall,
                        tone = GlassTone.Accent,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("测试全屏来电", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    granted: Boolean,
    onOpen: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(
            imageVector = if (granted) Icons.Rounded.CheckCircle else Icons.AutoMirrored.Rounded.OpenInNew,
            contentDescription = null,
            tint = if (granted) GlassColors.Success else GlassColors.Danger,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = GlassType.Callout, fontWeight = FontWeight.SemiBold, color = GlassColors.Ink)
            Text(description, style = GlassType.Footnote, color = GlassColors.InkSecondary)
        }
        if (!granted) {
            Spacer(Modifier.size(10.dp))
            GlassCapsuleButton(
                onClick = onOpen,
                tone = GlassTone.Accent,
                height = GlassMetrics.CompactControlHeight,
                contentPadding = PaddingValues(horizontal = 14.dp),
            ) {
                Text("开启", style = GlassType.Footnote, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 数据: sync, import/export and media backfill
// ---------------------------------------------------------------------------

@Composable
internal fun DataSettingsPage(
    isSyncing: Boolean,
    syncLabel: String,
    onSync: () -> Unit,
    onOpenDataManagement: (ExportKind) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPageScaffold(title = "数据", onBack = onBack) {
        item(key = "sync", contentType = "settings-card") {
            SettingsCard(title = "同步") {
                Text(
                    "从同步服务拉取遗漏的消息和博客。新内容通常会通过推送自动到达。",
                    style = GlassType.Footnote,
                    color = GlassColors.InkSecondary,
                )
                GlassCapsuleButton(
                    onClick = onSync,
                    enabled = !isSyncing,
                    tone = GlassTone.Accent,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SyncGlyph(
                        isSyncing = isSyncing,
                        tint = LocalContentColor.current,
                        size = 18.dp,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(8.dp))
                    // The label crossfades with the glyph and re-centers smoothly.
                    AnimatedContent(
                        targetState = isSyncing,
                        transitionSpec = {
                            fadeIn(tween(180)).togetherWith(fadeOut(tween(120))).using(SizeTransform(clip = false))
                        },
                        label = "sync-label",
                    ) { syncing ->
                        Text(
                            if (syncing) "同步中…" else "立即同步",
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                AnimatedStatusText(text = syncLabel, color = GlassColors.InkSecondary)
            }
        }
        item(key = "data-management", contentType = "settings-card") {
            SettingsCard(title = "数据管理") {
                Text(
                    "按成员导出或导入归档，并补齐本机缺失的媒体。",
                    style = GlassType.Footnote,
                    color = GlassColors.InkSecondary,
                )
                DataEntryRow(
                    icon = Icons.Rounded.Forum,
                    title = "消息数据",
                    onClick = { onOpenDataManagement(ExportKind.MESSAGES) },
                )
                DataEntryRow(
                    icon = Icons.AutoMirrored.Rounded.Article,
                    title = "博客数据",
                    onClick = { onOpenDataManagement(ExportKind.BLOGS) },
                )
            }
        }
    }
}

@Composable
private fun DataEntryRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    GlassPanel(
        onClick = onClick,
        onClickLabel = title,
        shape = GlassShapes.Card,
        depth = GlassDepths.None,
        fillAlpha = 0.34f,
        blur = 14.dp,
        modifier = Modifier.fillMaxWidth().height(GlassMetrics.ControlHeight),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
        ) {
            Icon(icon, contentDescription = null, tint = GlassColors.Accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(10.dp))
            Text(
                text = title,
                style = GlassType.Callout,
                fontWeight = FontWeight.SemiBold,
                color = GlassColors.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = GlassColors.InkTertiary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------

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
                style = GlassType.Callout,
                fontWeight = FontWeight.SemiBold,
                color = GlassColors.Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                description,
                color = GlassColors.InkSecondary,
                style = GlassType.Footnote,
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
        style = GlassType.Caption,
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
    style: TextStyle = GlassType.Subhead,
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
                style = style,
                fontWeight = fontWeight,
            )
        }
    }
}
