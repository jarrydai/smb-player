package com.dyx.smbplayer.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import com.dyx.smbplayer.ui.common.formatTime

private val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

private val ScrimTop = Brush.verticalGradient(
    listOf(Color.Black.copy(alpha = 0.62f), Color.Black.copy(alpha = 0.28f), Color.Transparent)
)

private val ScrimBottom = Brush.verticalGradient(
    listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.86f))
)

/**
 * 播放页控制层：顶部（返回 / 标题 / 倍速 / 画面比例）+ 底部（时间 / 进度条 / 播放键）。
 */
@Composable
fun ControlBar(
    player: ExoPlayer,
    title: String,
    subtitle: String,
    visible: Boolean,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    currentSpeed: Float,
    onSpeedChange: (Float) -> Unit,
    resizeMode: Int,
    onToggleResize: () -> Unit,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    var speedExpanded by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize()) {
            // ---------- 顶部 ----------
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(ScrimTop)
                    .padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 26.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundIconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White,
                        modifier = Modifier.size(21.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        color = Color.White,
                        fontSize = 15.sp,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle.isNotBlank()) {
                        Text(
                            subtitle,
                            color = Color.White.copy(alpha = 0.62f),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box {
                    RoundPill(onClick = { speedExpanded = true }) {
                        Icon(
                            Icons.Rounded.Speed,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "${trimSpeed(currentSpeed)}x",
                            color = Color.White,
                            fontSize = 12.sp,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    DropdownMenu(
                        expanded = speedExpanded,
                        onDismissRequest = { speedExpanded = false },
                        containerColor = Color(0xF21C222C),
                    ) {
                        SPEED_OPTIONS.forEach { s ->
                            val active = s == currentSpeed
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "${trimSpeed(s)}x",
                                        color = if (active) MaterialTheme.colorScheme.primary else Color.White,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                },
                                onClick = {
                                    onSpeedChange(s)
                                    speedExpanded = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                RoundIconButton(onClick = onToggleResize) {
                    Icon(
                        Icons.Rounded.AspectRatio,
                        contentDescription = "画面比例",
                        tint = if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) Color.White
                        else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }

            // ---------- 底部 ----------
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(ScrimBottom)
                    .padding(horizontal = 20.dp)
                    .padding(top = 40.dp, bottom = 26.dp),
            ) {
                var dragging by remember { mutableStateOf(false) }
                var dragPos by remember { mutableLongStateOf(0L) }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        formatTime(if (dragging) dragPos else positionMs),
                        color = Color.White,
                        fontSize = 12.5.sp,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "/ ${formatTime(durationMs)}",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.5.sp,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Slider(
                    value = (if (dragging) dragPos else positionMs.coerceAtMost(durationMs)).toFloat(),
                    onValueChange = {
                        dragging = true
                        dragPos = it.toLong()
                    },
                    onValueChangeFinished = {
                        dragging = false
                        onSeek(dragPos)
                    },
                    valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.28f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Surface(
                        onClick = onTogglePlay,
                        shape = CircleShape,
                        color = Color.White,
                        modifier = Modifier.size(58.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (isPlaying) "暂停" else "播放",
                                tint = Color(0xFF14181F),
                                modifier = Modifier.size(30.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.14f),
        modifier = Modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun RoundPill(onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.14f),
        modifier = Modifier.height(32.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
    }
}

private fun trimSpeed(v: Float): String =
    if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
