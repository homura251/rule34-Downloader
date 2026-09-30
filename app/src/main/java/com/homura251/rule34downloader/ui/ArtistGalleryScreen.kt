package com.homura251.rule34downloader.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.homura251.rule34downloader.data.DownloadStatus
import com.homura251.rule34downloader.data.GalleryPost
import com.homura251.rule34downloader.data.SyncState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistGalleryScreen(
    state: GalleryState,
    syncing: Boolean,
    syncState: SyncState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onSync: () -> Unit,
    onPause: () -> Unit,
    onSettings: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    var previewPosts by remember(state.artistTag) { mutableStateOf<List<GalleryPost>>(emptyList()) }
    var previewIndex by remember(state.artistTag) { mutableStateOf(0) }
    BackHandler(enabled = previewPosts.isEmpty(), onBack = onBack)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(state.artistTag.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = if (syncing) onPause else onSync, enabled = syncState != SyncState.PAUSING) {
                        Icon(when (syncState) {
                            SyncState.SYNCING, SyncState.PAUSING -> Icons.Rounded.Pause
                            SyncState.PAUSED -> Icons.Rounded.PlayArrow
                            else -> Icons.Rounded.Sync
                        }, if (syncing) "暂停同步" else if (syncState == SyncState.PAUSED) "继续同步" else "同步作品")
                    }
                    IconButton(onSettings) { Icon(Icons.Rounded.Settings, "设置与网页验证") }
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(140.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("画师作品 · ${state.totalCount} 项", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "按最新帖子排序 · 已下载图片优先离线读取 · 点图片查看大图",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.error != null) item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text(state.error, color = MaterialTheme.colorScheme.error)
                    TextButton(onRetry) { Text("重新读取") }
                }
            }
            if (state.posts.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.loading || syncing) CircularProgressIndicator()
                    else Icon(Icons.Rounded.Image, null, Modifier.size(48.dp))
                    Text(if (state.loading) "正在读取作品…" else if (syncing) "正在发现作品，列表会自动更新…" else "还没有发现作品")
                    Text("首次同步后，作品及下载状态会显示在这里。", style = MaterialTheme.typography.bodySmall)
                    if (!syncing && !state.loading) Button(onSync) { Text("同步画师作品") }
                }
            }
            itemsIndexed(state.posts, key = { _, post -> post.postId }) { index, post ->
                GalleryCard(post) {
                    // Freeze ordering while previewing so background sync cannot
                    // switch the displayed picture or pager index unexpectedly.
                    previewPosts = state.posts
                    previewIndex = index
                }
            }
            if (state.posts.isNotEmpty() && state.posts.size < state.totalCount) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Button(onLoadMore, modifier = Modifier.fillMaxWidth(), enabled = !state.loading) {
                        Text(if (state.loading) "正在读取…" else "加载更多 (${state.posts.size}/${state.totalCount})")
                    }
                }
            }
        }
    }

    if (previewPosts.isNotEmpty()) ImagePreviewDialog(
        posts = previewPosts,
        initialPage = previewIndex,
        onDismiss = { previewPosts = emptyList() },
        onSettings = onSettings,
    )
}

@Composable
private fun GalleryCard(post: GalleryPost, onOpen: () -> Unit) {
    Card(onClick = onOpen) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant)) {
            PostImage(post, fullSize = false, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (post.isVideo) Icon(
                Icons.Rounded.Videocam, "视频", Modifier.align(Alignment.BottomEnd).padding(8.dp),
                tint = Color.White,
            )
        }
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("#${post.postId}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Text(
                when (post.status) {
                    DownloadStatus.DOWNLOADED -> "已下载"
                    DownloadStatus.DOWNLOADING -> "下载中"
                    DownloadStatus.PENDING -> "待下载"
                    DownloadStatus.FAILED -> "失败"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (post.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun PostImage(
    post: GalleryPost,
    fullSize: Boolean,
    modifier: Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val context = LocalContext.current
    var skipLocal by remember(post.localUri, post.fileUrl) { mutableStateOf(false) }
    var loading by remember(post.postId, fullSize) { mutableStateOf(true) }
    var error by remember(post.postId, fullSize) { mutableStateOf<String?>(null) }
    var retry by remember(post.postId, fullSize) { mutableStateOf(0) }
    val source = post.imageSource(fullSize, skipLocal)
    val request = remember(source, retry) {
        ImageRequest.Builder(context).data(source).addHeader("Referer", post.postUrl).build()
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (source == null) {
            Icon(if (post.isVideo) Icons.Rounded.Videocam else Icons.Rounded.BrokenImage, null, Modifier.size(40.dp), tint)
        } else {
            key(retry) {
                AsyncImage(
                    model = request,
                    contentDescription = "${post.artistTag} 的作品 #${post.postId}",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale,
                    onLoading = { loading = true; error = null },
                    onSuccess = { loading = false; error = null },
                    onError = { result ->
                        if (!skipLocal && !post.localUri.isNullOrBlank() && source == post.localUri) {
                            skipLocal = true
                        } else {
                            loading = false
                            error = if (result.result.throwable.message.orEmpty().contains("Cloudflare")) {
                                "请在设置中完成网页验证后重试"
                            } else "图片加载失败"
                        }
                    },
                )
            }
            if (loading) CircularProgressIndicator(Modifier.size(28.dp), color = tint)
            error?.let { message ->
                Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.BrokenImage, null, tint = tint)
                    Text(message, color = tint, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { retry++; loading = true; error = null }) { Text("重试", color = tint) }
                }
            }
        }
    }
}

@Composable
private fun ImagePreviewDialog(
    posts: List<GalleryPost>,
    initialPage: Int,
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val pager = rememberPagerState(initialPage = initialPage, pageCount = { posts.size })
    var zoomed by remember { mutableStateOf(false) }
    var openError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pager.currentPage) { zoomed = false; openError = null }
    val currentPost = posts[pager.currentPage]

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onDismiss) { Icon(Icons.Rounded.Close, "关闭预览", tint = Color.White) }
                    Text(
                        "#${currentPost.postId} · ${pager.currentPage + 1}/${posts.size}",
                        Modifier.weight(1f), color = Color.White, maxLines = 1,
                    )
                    IconButton(onSettings) { Icon(Icons.Rounded.Settings, "网页验证", tint = Color.White) }
                    IconButton(onClick = {
                        openError = openMedia(context, currentPost, useLocalVideo = false)
                    }) { Icon(Icons.Rounded.OpenInNew, "打开原帖", tint = Color.White) }
                }
                HorizontalPager(
                    state = pager,
                    userScrollEnabled = !zoomed,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) { page ->
                    val post = posts[page]
                    if (post.isVideo) {
                        Column(
                            Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                        ) {
                            Icon(Icons.Rounded.Videocam, null, Modifier.size(56.dp), Color.White)
                            Text("视频作品", color = Color.White)
                            Text("可打开原帖查看；已下载的视频可使用系统播放器。", color = Color.LightGray)
                            if (!post.localUri.isNullOrBlank()) Button(onClick = {
                                openError = openMedia(context, post, useLocalVideo = true)
                            }) { Text("播放已下载视频") }
                        }
                    } else ZoomablePostImage(post, active = page == pager.currentPage) { if (page == pager.currentPage) zoomed = it }
                }
                openError?.let { Text(it, Modifier.padding(12.dp), color = Color.White) }
                Text(
                    "左右滑动切换 · 双指缩放 · 双击复位/放大",
                    Modifier.align(Alignment.CenterHorizontally).padding(12.dp),
                    color = Color.LightGray, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ZoomablePostImage(post: GalleryPost, active: Boolean, onZoomChanged: (Boolean) -> Unit) {
    var scale by remember(post.postId) { mutableFloatStateOf(1f) }
    var offset by remember(post.postId) { mutableStateOf(Offset.Zero) }
    var bounds by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(active) { if (!active) { scale = 1f; offset = Offset.Zero } }
    LaunchedEffect(scale, active) { if (active) onZoomChanged(scale > 1f) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        val maxX = bounds.width * (scale - 1f) / 2f
        val maxY = bounds.height * (scale - 1f) / 2f
        offset = if (scale == 1f) Offset.Zero else Offset(
            (offset.x + pan.x).coerceIn(-maxX, maxX),
            (offset.y + pan.y).coerceIn(-maxY, maxY),
        )
    }
    Box(
        Modifier.fillMaxSize().clipToBounds().onSizeChanged { bounds = it }
            .pointerInput(post.postId) {
                detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; offset = Offset.Zero })
            }
            .transformable(transform, canPan = { scale > 1f }),
    ) {
        PostImage(
            post, fullSize = true,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
            },
            tint = Color.White,
        )
    }
}

private fun openMedia(context: Context, post: GalleryPost, useLocalVideo: Boolean): String? = try {
    val intent = if (useLocalVideo && !post.localUri.isNullOrBlank()) {
        Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(post.localUri), "video/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } else Intent(Intent.ACTION_VIEW, Uri.parse(post.postUrl))
    context.startActivity(intent)
    null
} catch (_: Exception) {
    "无法打开，请确认已安装浏览器或视频播放器。"
}
