package com.nogirelay.app.blog

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.BlogPost
import com.nogirelay.app.media.MediaDownloader
import com.nogirelay.app.ui.BrandPurple
import com.nogirelay.app.ui.BrandPurpleLight
import com.nogirelay.app.ui.NogiRelayTheme
import com.nogirelay.app.ui.RelayControlShape
import com.nogirelay.app.ui.RemoteImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 下载按钮的三种状态，用来驱动图标与文案之间的平滑过渡。 */
private enum class BlogDownloadButtonState { IDLE, DOWNLOADING, DONE }

/** 下载快到一瞬间完成时，进度动画至少显示这么久，避免一闪而过。 */
private const val MIN_DOWNLOAD_FEEDBACK_MILLIS = 650L

/** 保存成功后的短暂反馈时长。 */
private const val DOWNLOAD_DONE_FEEDBACK_MILLIS = 1600L

class BlogImageDownloadActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_BLOG_ID = "blog_image_download_blog_id"

        fun intent(context: Context, blogId: String): Intent =
            Intent(context, BlogImageDownloadActivity::class.java).putExtra(EXTRA_BLOG_ID, blogId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 顶栏/底栏背景延伸到系统栏下面，图标保持深色，避免标题压到状态栏。
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        AppGraph.initialize(this)
        val blog = intent.getStringExtra(EXTRA_BLOG_ID)?.let(AppGraph.database::findBlog)
        if (blog == null) {
            finish()
            return
        }
        setContent {
            NogiRelayTheme {
                BlogImageDownloadScreen(blog = blog, onBack = ::finish)
            }
        }
    }
}

@Composable
private fun BlogImageDownloadScreen(blog: BlogPost, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val urls = remember(blog.id, blog.bodyHtml, blog.imageUrl) { BlogMediaDownloader.imageUrls(blog) }
    var selectedUrls by remember(urls) { mutableStateOf(emptySet<String>()) }
    var downloading by remember { mutableStateOf(false) }
    var downloadCompleted by remember { mutableStateOf(false) }
    var waitingForPermission by remember { mutableStateOf(false) }

    fun downloadSelected() {
        val requested = urls.filter(selectedUrls::contains)
        if (requested.isEmpty() || downloading) return
        downloading = true
        downloadCompleted = false
        scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            val results = withContext(Dispatchers.IO) {
                requested.map { url ->
                    val imageNumber = urls.indexOf(url) + 1
                    runCatching {
                        MediaDownloader.saveImageUrlToDownloads(
                            context = context,
                            url = url,
                            baseName = "${blog.memberName}_${blog.id}_$imageNumber",
                        )
                    }
                }
            }
            // 图片往往一瞬间就下载完：让进度状态至少完整显示一小段时间，
            // 否则按钮会从"下载"直接跳到结果，中间的进度一闪而过。
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (elapsed < MIN_DOWNLOAD_FEEDBACK_MILLIS) {
                delay(MIN_DOWNLOAD_FEEDBACK_MILLIS - elapsed)
            }
            downloading = false
            val savedCount = results.count(Result<*>::isSuccess)
            val failedCount = results.size - savedCount
            val message = when {
                failedCount == 0 -> "已保存 $savedCount 张图片到 Download"
                savedCount == 0 -> results.firstNotNullOfOrNull { it.exceptionOrNull()?.message } ?: "图片下载失败"
                else -> "已保存 $savedCount 张图片，$failedCount 张失败"
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            if (savedCount > 0) {
                // 与媒体查看器的下载按钮一致：成功状态短暂停留后再回到"下载"。
                downloadCompleted = true
                delay(DOWNLOAD_DONE_FEEDBACK_MILLIS)
                downloadCompleted = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val shouldDownload = waitingForPermission
        waitingForPermission = false
        if (granted && shouldDownload) {
            downloadSelected()
        } else if (!granted) {
            Toast.makeText(context, "需要存储权限才能保存到 Download 文件夹", Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Surface(
            tonalElevation = 1.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                IconButton(onClick = onBack, enabled = !downloading) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回博客", tint = MaterialTheme.colorScheme.onSurface)
                }
                Column(Modifier.weight(1f)) {
                    Text("选择要下载的图片", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        blog.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        if (urls.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("这篇博客没有可下载的图片", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(144.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            ) {
                itemsIndexed(urls, key = { _, url -> url }) { index, url ->
                    val selected = url in selectedUrls
                    Surface(
                        onClick = {
                            if (!downloading) {
                                selectedUrls = if (selected) selectedUrls - url else selectedUrls + url
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) BrandPurpleLight else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = if (selected) {
                            BorderStroke(2.dp, BrandPurple)
                        } else {
                            BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        },
                    ) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(4.dp)) {
                            RemoteImage(
                                url = url,
                                contentDescription = "第 ${index + 1} 张博客图片",
                                contentScale = ContentScale.Fit,
                                loadCachedImmediately = true,
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                            )
                        }
                    }
                }
            }
        }

        Surface(
            tonalElevation = 3.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                IconButton(onClick = { selectedUrls = urls.toSet() }, enabled = urls.isNotEmpty() && !downloading) {
                    Icon(Icons.Rounded.DoneAll, contentDescription = "全部选择", tint = BrandPurple)
                }
                IconButton(onClick = { selectedUrls = emptySet() }, enabled = selectedUrls.isNotEmpty() && !downloading) {
                    Icon(Icons.Rounded.ClearAll, contentDescription = "全部清除", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "已选 ${selectedUrls.size} / ${urls.size}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        if (MediaDownloader.needsLegacyWritePermission(context)) {
                            waitingForPermission = true
                            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        } else {
                            downloadSelected()
                        }
                    },
                    enabled = selectedUrls.isNotEmpty() && !downloading,
                    shape = RelayControlShape,
                    colors = ButtonDefaults.buttonColors(containerColor = BrandPurple),
                ) {
                    AnimatedContent(
                        targetState = when {
                            downloading -> BlogDownloadButtonState.DOWNLOADING
                            downloadCompleted -> BlogDownloadButtonState.DONE
                            else -> BlogDownloadButtonState.IDLE
                        },
                        transitionSpec = {
                            (
                                fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) +
                                    scaleIn(
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMediumLow,
                                        ),
                                        initialScale = 0.8f,
                                    )
                                ).togetherWith(
                                fadeOut(animationSpec = tween(120)) +
                                    scaleOut(targetScale = 0.8f, animationSpec = tween(120)),
                            )
                        },
                        contentAlignment = Alignment.Center,
                        label = "blog_download_button",
                    ) { state ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            when (state) {
                                BlogDownloadButtonState.DOWNLOADING -> CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White,
                                )
                                BlogDownloadButtonState.DONE -> Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                BlogDownloadButtonState.IDLE -> Icon(
                                    Icons.Rounded.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Spacer(Modifier.size(6.dp))
                            Text(
                                text = when (state) {
                                    BlogDownloadButtonState.DOWNLOADING -> "下载中"
                                    BlogDownloadButtonState.DONE -> "已保存"
                                    BlogDownloadButtonState.IDLE -> "下载"
                                },
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}
