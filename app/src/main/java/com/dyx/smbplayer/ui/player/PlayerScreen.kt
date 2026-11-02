package com.dyx.smbplayer.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.dyx.smbplayer.data.model.RemoteServer
import com.dyx.smbplayer.data.model.VideoItem
import com.dyx.smbplayer.ui.common.findActivity
import com.dyx.smbplayer.ui.common.formatTime
import com.dyx.smbplayer.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay

/**
 * 播放页：沉浸式全屏 + 手势层 + 控制栏 + 断点续播。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerScreen(
    item: VideoItem,
    server: RemoteServer,
    onBack: () -> Unit,
) {
    val vm: PlayerViewModel = viewModel()
    val player by vm.player.collectAsState()
    val resumeFrom by vm.resumeFrom.collectAsState()
    val errorText by vm.error.collectAsState()
    val buffering by vm.buffering.collectAsState()
    val seekRangeSeconds by vm.seekRangeSeconds.collectAsState()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    // ---- 沉浸式全屏 ----
    // decorFitsSystemWindows 只在进入时确认一次（MainActivity 已全局设为 false），
    // 退出时不改回去：实测"false→true"来回切换会让部分设备窗口布局卡死（返回后白屏）。
    DisposableEffect(activity) {
        val window = activity?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            if (window != null) {
                WindowInsetsControllerCompat(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // ---- 装载媒体 ----
    LaunchedEffect(item.path) {
        vm.prepare(item, server)
    }

    // ---- 退出时保存进度并释放播放器（VM 为 Activity 级，需显式释放） ----
    DisposableEffect(item.path) {
        onDispose { vm.releasePlayer() }
    }

    // ---- 进入后台时保存进度 ----
    LifecycleResumeEffect(item.path) {
        onPauseOrDispose { vm.saveProgress() }
    }

    val exo = player

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (exo == null) {
            CircularProgressIndicator(
                Modifier.align(Alignment.Center),
                color = Color.White,
                strokeWidth = 3.dp,
            )
        } else {
            val playerRef = exo

            // 播放画面
            var resizeMode by remember {
                mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT)
            }
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = playerRef
                        useController = false
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update = { view ->
                    view.player = playerRef
                    view.resizeMode = resizeMode
                },
                modifier = Modifier.fillMaxSize(),
            )

            // 播放状态轮询（每 500ms；每 10s 自动保存进度）
            var isPlaying by remember { mutableStateOf(false) }
            var positionMs by remember { mutableLongStateOf(0L) }
            var durationMs by remember { mutableLongStateOf(0L) }
            LaunchedEffect(playerRef) {
                var tick = 0
                while (true) {
                    positionMs = playerRef.currentPosition
                    durationMs = playerRef.duration.takeIf { it > 0 } ?: 0L
                    isPlaying = playerRef.isPlaying
                    tick++
                    if (tick % 20 == 0) vm.saveProgress()
                    delay(500)
                }
            }

            // 控制栏显隐与自动隐藏
            var controlsVisible by remember { mutableStateOf(false) }
            LaunchedEffect(controlsVisible, isPlaying) {
                if (controlsVisible && isPlaying) {
                    delay(4000)
                    controlsVisible = false
                }
            }

            // 手势层
            PlayerGestureLayer(
                player = playerRef,
                onToggleControls = { controlsVisible = !controlsVisible },
                onDoubleTapSeek = { delta ->
                    val max = playerRef.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                    playerRef.seekTo((playerRef.currentPosition + delta).coerceIn(0L, max))
                },
                modifier = Modifier.fillMaxSize(),
                seekRangeMs = seekRangeSeconds * 1000L,
            )

            // 控制栏
            var currentSpeed by remember { mutableStateOf(1.0f) }
            ControlBar(
                player = playerRef,
                title = item.title,
                subtitle = server.name,
                visible = controlsVisible,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                currentSpeed = currentSpeed,
                onSpeedChange = {
                    currentSpeed = it
                    playerRef.setPlaybackSpeed(it)
                },
                resizeMode = resizeMode,
                onToggleResize = {
                    resizeMode = when (resizeMode) {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT ->
                            AspectRatioFrameLayout.RESIZE_MODE_FILL
                        AspectRatioFrameLayout.RESIZE_MODE_FILL ->
                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                onBack = {
                    vm.saveProgress()
                    onBack()
                },
                onTogglePlay = {
                    if (playerRef.isPlaying) playerRef.pause() else playerRef.play()
                },
                onSeek = { playerRef.seekTo(it) },
            )

            // 断点续播提示
            resumeFrom?.let { pos ->
                LaunchedEffect(pos) {
                    delay(2200)
                    vm.consumeResume()
                }
                Surface(
                    color = Color.White.copy(alpha = 0.12f),
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 76.dp),
                ) {
                    Text(
                        "已从 ${formatTime(pos)} 继续播放",
                        color = Color.White,
                        fontSize = 12.5.sp,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }

            // 缓冲中提示（SMB 首次打开需要握手/缓冲，避免看起来“没反应”）
            if (buffering && errorText == null) {
                Surface(
                    color = Color.Black.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 26.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(32.dp),
                        )
                        Text(
                            "正在连接共享并缓冲…",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }

            // 播放错误提示（含重试）
            errorText?.let { msg ->
                Surface(
                    color = Color(0xF2141820),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                ) {
                    Column(
                        Modifier.padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .size(52.dp)
                                .background(Color(0xFF3A1E1E), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = Color(0xFFFF9E9E),
                                modifier = Modifier.size(26.dp),
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "播放失败",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            msg,
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Spacer(Modifier.height(18.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TextButton(onClick = {
                                vm.saveProgress()
                                onBack()
                            }) {
                                Text("返回", color = Color.White.copy(alpha = 0.7f))
                            }
                            Button(
                                onClick = { vm.retry() },
                                shape = CircleShape,
                                modifier = Modifier.height(40.dp),
                            ) {
                                Icon(
                                    Icons.Rounded.PlayCircleOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("重试", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
    BackHandler(enabled = true) {
        vm.saveProgress()
        onBack()
    }
}
