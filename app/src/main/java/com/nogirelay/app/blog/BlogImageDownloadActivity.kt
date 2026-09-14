package com.nogirelay.app.blog

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Button
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
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.BlogPost
import com.nogirelay.app.media.MediaDownloader
import com.nogirelay.app.ui.NogiRelayTheme
import com.nogirelay.app.ui.RemoteImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BlogImageDownloadActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_BLOG_ID = "blog_image_download_blog_id"

        fun intent(context: Context, blogId: String): Intent =
            Intent(context, BlogImageDownloadActivity::class.java).putExtra(EXTRA_BLOG_ID, blogId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    var waitingForPermission by remember { mutableStateOf(false) }

    fun downloadSelected() {
        val requested = urls.filter(selectedUrls::contains)
        if (requested.isEmpty() || downloading) return
        downloading = true
        scope.launch {
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
            downloading = false
            val savedCount = results.count(Result<*>::isSuccess)
            val failedCount = results.size - savedCount
            val message = when {
                failedCount == 0 -> "已保存 $savedCount 张图片到 Download"
                savedCount == 0 -> results.firstNotNullOfOrNull { it.exceptionOrNull()?.message } ?: "图片下载失败"
                else -> "已保存 $savedCount 张图片，$failedCount 张失败"
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            IconButton(onClick = onBack, enabled = !downloading) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回博客")
            }
            Column(Modifier.weight(1f)) {
                Text("选择要下载的图片", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    blog.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
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
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(4.dp)) {
                            RemoteImage(
                                url = url,
                                contentDescription = "第 ${index + 1} 张博客图片",
                                contentScale = ContentScale.Fit,
                                loadCachedImmediately = true,
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(9.dp)),
                            )
                            Text(
                                "${index + 1}",
                                color = Color.White,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(6.dp)
                                    .background(Color.Black.copy(alpha = 0.58f), CircleShape)
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                            if (selected) {
                                Icon(
                                    Icons.Rounded.CheckCircle,
                                    contentDescription = "已选择",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(30.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        Surface(tonalElevation = 3.dp) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                IconButton(onClick = { selectedUrls = urls.toSet() }, enabled = urls.isNotEmpty() && !downloading) {
                    Icon(Icons.Rounded.DoneAll, contentDescription = "全部选择")
                }
                IconButton(onClick = { selectedUrls = emptySet() }, enabled = selectedUrls.isNotEmpty() && !downloading) {
                    Icon(Icons.Rounded.ClearAll, contentDescription = "全部清除")
                }
                Text("已选 ${selectedUrls.size} / ${urls.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                ) {
                    if (downloading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.size(6.dp))
                    Text(if (downloading) "下载中" else "下载")
                }
            }
        }
    }
}
