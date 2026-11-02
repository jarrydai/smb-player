package com.dyx.smbplayer.ui.player

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer
import com.dyx.smbplayer.ui.common.formatTime
import com.dyx.smbplayer.ui.common.findActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private enum class Zone { LEFT, CENTER, RIGHT }
private enum class Mode { PENDING, SEEK, BRIGHTNESS, VOLUME, LONG_PRESS }

/**
 * 播放器手势层：
 * - 中间横滑：快进/快退（顶部气泡显示「当前 » 目标」）
 * - 左侧竖滑：亮度；右侧竖滑：音量
 * - 双击左半屏 -10s，双击右半屏 +10s
 * - 单击：切换控制栏；长按：2.0x 倍速，松手恢复
 */
@Composable
fun PlayerGestureLayer(
    player: ExoPlayer,
    onToggleControls: () -> Unit,
    onDoubleTapSeek: (deltaMs: Long) -> Unit,
    modifier: Modifier = Modifier,
    /** 整屏宽度横滑对应的快进/快退毫秒数（设置项，默认 5 分钟） */
    seekRangeMs: Long = 300_000L,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    val scope = rememberCoroutineScope()

    var bubble by remember { mutableStateOf<String?>(null) }
    var pendingTap by remember { mutableStateOf<Job?>(null) }
    var lastTapAt by remember { mutableStateOf(0L) }
    var lastTapX by remember { mutableStateOf(0f) }
    var lastTapY by remember { mutableStateOf(0f) }

    Box(modifier) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(player, seekRangeMs) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val pointerId = down.id
                        val width = size.width.toFloat()
                        val height = size.height.toFloat()
                        val zone = when {
                            down.position.x < width / 4f -> Zone.LEFT
                            down.position.x > width * 3f / 4f -> Zone.RIGHT
                            else -> Zone.CENTER
                        }
                        var mode = Mode.PENDING
                        var moved = false
                        var longPressed = false
                        val downAt = System.currentTimeMillis()
                        val startX = down.position.x
                        val startY = down.position.y
                        val startPos = player.currentPosition
                        val duration = player.duration.takeIf { it > 0 } ?: 0L
                        val baseSpeed = player.playbackParameters.speed
                        val startBrightness = activity?.window?.attributes?.screenBrightness
                            ?.takeIf { it >= 0f } ?: 0.5f
                        var brightness = startBrightness
                        val startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        val seekPerWidth = seekRangeMs.coerceAtLeast(10_000L).toFloat()
                        var targetSeek = startPos

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                            if (!change.pressed) {
                                when (mode) {
                                    Mode.LONG_PRESS -> {
                                        player.setPlaybackSpeed(baseSpeed)
                                        bubble = null
                                    }
                                    Mode.SEEK -> {
                                        player.seekTo(targetSeek)
                                        bubble = null
                                    }
                                    Mode.PENDING -> {
                                        if (!longPressed) {
                                            val now = System.currentTimeMillis()
                                            val isDoubleTap = now - lastTapAt < 300 &&
                                                    abs(down.position.x - lastTapX) < 120f &&
                                                    abs(down.position.y - lastTapY) < 240f
                                            lastTapAt = now
                                            lastTapX = down.position.x
                                            lastTapY = down.position.y
                                            if (isDoubleTap) {
                                                pendingTap?.cancel()
                                                lastTapAt = 0L
                                                val delta =
                                                    if (down.position.x < width / 2f) -10_000L else 10_000L
                                                onDoubleTapSeek(delta)
                                                bubble =
                                                    if (delta > 0) "快进 10 秒 ▶▶" else "◀◀ 快退 10 秒"
                                                scope.launch {
                                                    delay(800)
                                                    if (bubble?.contains("10 秒") == true) bubble = null
                                                }
                                            } else {
                                                pendingTap = scope.launch {
                                                    delay(280)
                                                    onToggleControls()
                                                }
                                            }
                                        }
                                    }
                                    else -> bubble = null
                                }
                                break
                            }
                            val dx = change.position.x - startX
                            val dy = change.position.y - startY
                            if (!moved && (abs(dx) > 28f || abs(dy) > 28f)) {
                                moved = true
                                mode = when {
                                    abs(dy) > abs(dx) && zone == Zone.LEFT -> Mode.BRIGHTNESS
                                    abs(dy) > abs(dx) && zone == Zone.RIGHT -> Mode.VOLUME
                                    abs(dx) > abs(dy) -> Mode.SEEK
                                    zone == Zone.LEFT -> Mode.BRIGHTNESS
                                    zone == Zone.RIGHT -> Mode.VOLUME
                                    else -> Mode.SEEK
                                }
                            }
                            if (!moved && mode == Mode.PENDING &&
                                System.currentTimeMillis() - downAt > 600
                            ) {
                                longPressed = true
                                mode = Mode.LONG_PRESS
                                player.setPlaybackSpeed(2f)
                                bubble = "2.0x 倍速播放中"
                            }
                            when (mode) {
                                Mode.SEEK -> {
                                    if (duration > 0) {
                                        targetSeek = (startPos + dx / width * seekPerWidth)
                                            .roundToLong()
                                            .coerceIn(0L, duration)
                                        val delta = targetSeek - startPos
                                        val sign = if (delta >= 0) "+" else "-"
                                        bubble = "${formatTime(startPos)}  »  ${formatTime(targetSeek)}" +
                                                "   $sign${formatTime(abs(delta))}"
                                    }
                                }
                                Mode.BRIGHTNESS -> {
                                    brightness = (startBrightness - dy / height * 1.2f)
                                        .coerceIn(0.01f, 1f)
                                    activity?.window?.let { w ->
                                        w.attributes = w.attributes.apply {
                                            screenBrightness = brightness
                                        }
                                    }
                                    bubble = "亮度 ${(brightness * 100).roundToInt()}%"
                                }
                                Mode.VOLUME -> {
                                    val v = (startVolume - dy / height * 1.2f * maxVolume)
                                        .roundToInt()
                                        .coerceIn(0, maxVolume)
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                                    bubble = "音量 ${v * 100 / maxVolume}%"
                                }
                                else -> {}
                            }
                            change.consume()
                        }
                    }
                }
        )

        // 手势气泡
        bubble?.let { text ->
            LaunchedEffect(text) {
                delay(1200)
                if (bubble == text) bubble = null
            }
            Surface(
                color = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.align(Alignment.Center),
            ) {
                Text(
                    text,
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}
