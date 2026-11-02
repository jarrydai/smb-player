package com.dyx.smbplayer.ui.browser

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dyx.smbplayer.data.RemoteFiles
import com.dyx.smbplayer.data.model.FileKind
import com.dyx.smbplayer.data.model.RemoteEntry
import com.dyx.smbplayer.data.model.RemoteServer
import com.dyx.smbplayer.ui.common.EmptyState
import com.dyx.smbplayer.ui.common.ErrorState
import com.dyx.smbplayer.ui.common.IconTile
import com.dyx.smbplayer.ui.common.LoadingState
import com.dyx.smbplayer.ui.common.formatSize
import com.dyx.smbplayer.ui.common.headerBrush

/**
 * 目录浏览页：当前路径 + 文件夹/视频列表。
 */
@Composable
fun DirListScreen(
    server: RemoteServer,
    path: String,
    entries: List<RemoteEntry>,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onOpenDir: (String) -> Unit,
    onOpenEntry: (RemoteEntry) -> Unit,
    onExitToList: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).navigationBarsPadding()) {
        DirHeader(
            server = server,
            path = path,
            onBack = onBack,
            onExitToList = onExitToList,
        )

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingState("正在读取目录…")
            }

            error != null -> ErrorState(
                title = "无法打开这个目录",
                message = error,
                onRetry = onRetry,
            )

            entries.isEmpty() -> EmptyState(
                icon = Icons.Rounded.FolderOpen,
                title = "这个文件夹是空的",
                message = "没有找到视频、图片或文本文件。",
            )

            else -> {
                val folderCount = entries.count { it.isDir }
                val videoCount = entries.count { !it.isDir && it.kind == FileKind.VIDEO }
                val imageCount = entries.count { !it.isDir && it.kind == FileKind.IMAGE }
                val textCount = entries.count { !it.isDir && it.kind == FileKind.TEXT }
                Text(
                    buildString {
                        val parts = mutableListOf<String>()
                        if (folderCount > 0) parts.add("$folderCount 个文件夹")
                        if (videoCount > 0) parts.add("$videoCount 个视频")
                        if (imageCount > 0) parts.add("$imageCount 张图片")
                        if (textCount > 0) parts.add("$textCount 个文本")
                        append(parts.ifEmpty { listOf("空目录") }.joinToString(" · "))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 22.dp, top = 14.dp),
                )
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(entries, key = { it.url }) { entry ->
                        EntryCard(
                            entry = entry,
                            onClick = {
                                if (entry.isDir) onOpenDir(entry.url) else onOpenEntry(entry)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DirHeader(
    server: RemoteServer,
    path: String,
    onBack: () -> Unit,
    onExitToList: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(headerBrush())
            .statusBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "上一级",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    server.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    prettyPath(server, path),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Surface(
                onClick = onExitToList,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.Dns,
                        contentDescription = "服务器列表",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }
    }
}

/** 完整地址 -> 只保留路径部分（SMB: /share/dir；WebDAV: /dav/dir） */
private fun prettyPath(server: RemoteServer, path: String): String =
    RemoteFiles.prettyPath(server, path)

@Composable
private fun EntryCard(entry: RemoteEntry, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val (icon, container, tint) = when (entry.kind) {
                FileKind.DIR -> Triple(
                    Icons.Rounded.Folder,
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.onSecondaryContainer,
                )
                FileKind.IMAGE -> Triple(
                    Icons.Rounded.Image,
                    MaterialTheme.colorScheme.tertiaryContainer,
                    MaterialTheme.colorScheme.onTertiaryContainer,
                )
                FileKind.TEXT -> Triple(
                    Icons.Rounded.Description,
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Triple(
                    Icons.Rounded.Movie,
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.primary,
                )
            }
            IconTile(
                icon = icon,
                container = container,
                tint = tint,
                size = 44.dp,
                iconSize = 22.dp,
                shape = RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    kindLabel(entry) + sizeSuffix(entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.kind == FileKind.VIDEO) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(34.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = "播放",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            } else {
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

private fun kindLabel(entry: RemoteEntry): String = when (entry.kind) {
    FileKind.DIR -> "文件夹"
    FileKind.VIDEO -> "视频"
    FileKind.IMAGE -> "图片"
    FileKind.TEXT -> "文本"
    FileKind.OTHER -> "文件"
}

private fun sizeSuffix(entry: RemoteEntry): String {
    val size = formatSize(entry.sizeBytes)
    return if (size.isBlank()) "" else " · $size"
}
