package com.dyx.smbplayer.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ErrorOutline
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dyx.smbplayer.data.RemoteFiles
import com.dyx.smbplayer.data.model.RemoteEntry
import com.dyx.smbplayer.data.model.RemoteServer
import com.dyx.smbplayer.ui.common.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 文本查看页：SMB 串流加载，自动识别编码（UTF-8 / UTF-16 / GBK），
 * 大文件只读取前 2MB。按行懒加载渲染，几万行也不卡。
 */
@Composable
fun TextViewerScreen(
    entry: RemoteEntry,
    server: RemoteServer,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }

    var result by remember(entry.url) { mutableStateOf<Result<DecodedText>?>(null) }
    var retryTick by remember { mutableStateOf(0) }

    LaunchedEffect(entry.url, retryTick) {
        result = null
        withContext(Dispatchers.IO) {
            try {
                val bytes = RemoteFiles.readFile(server, entry.url, RemoteFiles.MAX_TEXT_BYTES, truncate = true)
                val truncated = bytes.size >= RemoteFiles.MAX_TEXT_BYTES
                withContext(Dispatchers.Main) {
                    result = Result.success(decodeText(bytes, truncated))
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { result = Result.failure(e) }
            }
        }
    }

    DisposableEffect(entry.url) {
        onDispose { RemoteFiles.invalidate(server, entry.url) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
    ) {
        // 顶栏
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .statusBarsPadding()
                .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    entry.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${formatSize(entry.sizeBytes)} · ${server.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        when (val r = result) {
            null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(32.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "正在从共享读取文本…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }

            else -> r.fold(
                onSuccess = { decoded -> TextContent(decoded) },
                onFailure = { e ->
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(36.dp),
                        )
                        Text(
                            "无法读取这个文本文件",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        Text(
                            e.message ?: "未知错误",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Row(Modifier.padding(top = 18.dp)) {
                            TextButton(onClick = onBack) { Text("返回") }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { retryTick++ }, shape = CircleShape) { Text("重试") }
                        }
                    }
                },
            )
        }
    }
}

data class DecodedText(val lines: List<String>, val charsetName: String, val truncated: Boolean)

@Composable
private fun TextContent(decoded: DecodedText) {
    Column(Modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
        ) {
            Text(
                buildString {
                    append("编码 ${decoded.charsetName}")
                    if (decoded.truncated) append(" · 文件较大，仅显示前 2 MB")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            itemsIndexed(decoded.lines) { index, line ->
                Text(
                    line,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** BOM → 严格 UTF-8 → GBK → Latin-1 */
private fun decodeText(bytes: ByteArray, truncated: Boolean): DecodedText {
    val hasBomUtf8 = bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
    val hasBomLe = bytes.size >= 2 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()
    val hasBomBe = bytes.size >= 2 &&
            bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()

    val text: String
    val csName: String
    when {
        hasBomLe -> { text = String(bytes, Charsets.UTF_16LE); csName = "UTF-16LE" }
        hasBomBe -> { text = String(bytes, Charsets.UTF_16BE); csName = "UTF-16BE" }
        else -> {
            val strictUtf8 = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            val decodedUtf8 = try {
                strictUtf8.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (e: java.nio.charset.CharacterCodingException) {
                null
            }
            if (decodedUtf8 != null || hasBomUtf8) {
                text = decodedUtf8 ?: String(bytes, Charsets.UTF_8)
                csName = "UTF-8"
            } else {
                // 中文 Windows 记事本最常见的非 UTF-8 编码
                text = String(bytes, charset("GBK"))
                csName = "GBK"
            }
        }
    }
    val clean = text.replace("\r\n", "\n").replace('\r', '\n')
    return DecodedText(clean.split('\n'), csName, truncated)
}
