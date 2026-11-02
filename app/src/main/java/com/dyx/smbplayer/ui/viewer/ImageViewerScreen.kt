package com.dyx.smbplayer.ui.viewer

import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.dyx.smbplayer.data.RemoteFiles
import com.dyx.smbplayer.data.model.RemoteEntry
import com.dyx.smbplayer.data.model.RemoteServer
import com.dyx.smbplayer.ui.common.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 图片查看页：SMB 串流加载，支持双指缩放、拖动、双击放大；GIF 自动播放动画。
 */
@Composable
fun ImageViewerScreen(
    entry: RemoteEntry,
    server: RemoteServer,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }

    var result by remember(entry.url) { mutableStateOf<Result<Drawable>?>(null) }
    var retryTick by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val maxDim = minOf(
        LocalConfiguration.current.screenWidthDp,
        LocalConfiguration.current.screenHeightDp,
    ) * 2 // 保证放大 2 倍仍有清晰度

    LaunchedEffect(entry.url, retryTick) {
        result = null
        withContext(Dispatchers.IO) {
            try {
                val bytes = RemoteFiles.readFile(server, entry.url, RemoteFiles.MAX_IMAGE_BYTES)
                val drawable = decodeDrawable(bytes, maxDim)
                withContext(Dispatchers.Main) { result = Result.success(drawable) }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    result = Result.failure(e)
                }
            }
        }
    }

    // 退出时释放 SMB 句柄
    androidx.compose.runtime.DisposableEffect(entry.url) {
        onDispose { RemoteFiles.invalidate(server, entry.url) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val r = result) {
            null -> Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                Text(
                    "正在从共享读取图片…",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            else -> r.fold(
                onSuccess = { drawable -> ImageCanvas(drawable) },
                onFailure = { e ->
                    ErrorPane(
                        message = e.message ?: "图片解码失败",
                        onRetry = { retryTick++ },
                        onBack = onBack,
                    )
                },
            )
        }

        // 顶部信息栏
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)
                    )
                )
                .statusBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                onClick = onBack,
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.14f),
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White,
                        modifier = Modifier.size(21.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.displayName,
                    color = Color.White,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${formatSize(entry.sizeBytes)} · ${server.name}",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ImageCanvas(drawable: Drawable) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    offset = if (scale > 1.01f) offset + pan else Offset.Zero
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    },
                )
            }
    ) {
        AndroidView(
            factory = { ctx ->
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(Color.Black.toArgb())
                }
            },
            update = { iv ->
                iv.setImageDrawable(drawable)
                (drawable as? android.graphics.drawable.Animatable)?.start()
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
        if (scale > 1.01f) {
            Surface(
                color = Color.White.copy(alpha = 0.16f),
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
            ) {
                TextButton(onClick = {
                    scale = 1f
                    offset = Offset.Zero
                }) {
                    Text(
                        "${"%.1f".format(scale)}x · 双击复位",
                        color = Color.White,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorPane(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(
            Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = Color(0xFFFF9E9E),
            modifier = Modifier.size(40.dp),
        )
        Text(
            "无法显示这张图片",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            message,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(Modifier.padding(top = 20.dp)) {
            TextButton(onClick = onBack) { Text("返回", color = Color.White.copy(alpha = 0.7f)) }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onRetry, shape = CircleShape) { Text("重试") }
        }
    }
}

/** 解码为 Drawable：API 28+ 用 ImageDecoder（支持 GIF 动画 / HEIF），低版本退回 BitmapFactory */
private fun decodeDrawable(bytes: ByteArray, maxDim: Int): Drawable {
    if (Build.VERSION.SDK_INT >= 28) {
        val source = android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
        return android.graphics.ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
            val largest = maxOf(info.size.width, info.size.height)
            if (largest > maxDim) {
                decoder.setTargetSampleSize((largest + maxDim - 1) / maxDim)
            }
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        ?: throw IllegalStateException("图片解码失败，格式可能不受支持")
    return BitmapDrawable(bmp)
}
