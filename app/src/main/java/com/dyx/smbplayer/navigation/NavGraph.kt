package com.dyx.smbplayer.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.dyx.smbplayer.data.model.FileKind
import com.dyx.smbplayer.data.model.RemoteEntry
import com.dyx.smbplayer.data.model.RemoteServer
import com.dyx.smbplayer.data.model.VideoItem
import com.dyx.smbplayer.ui.browser.BrowserScreen
import com.dyx.smbplayer.ui.player.PlayerScreen
import com.dyx.smbplayer.ui.viewer.ImageViewerScreen
import com.dyx.smbplayer.ui.viewer.TextViewerScreen

/**
 * 一次打开请求：远程条目 + 所属服务器。
 *
 * 说明：这里刻意不使用 navigation-compose（2.8.x 在部分 Android 14 设备上
 * 返回后会出现 NavHost 内容空白的问题），应用只有"浏览/查看"两个层级，
 * 直接用状态切换更简单可靠。
 */
private data class OpenRequest(val entry: RemoteEntry, val server: RemoteServer)

@Composable
fun AppRoot() {
    var request by remember { mutableStateOf<OpenRequest?>(null) }

    // 查看页叠加在浏览页之上（而非替换），保证返回后服务器列表 / 目录位置不丢失。
    Box(Modifier.fillMaxSize()) {
        BrowserScreen(
            onOpenEntry = { entry, server -> request = OpenRequest(entry, server) },
        )

        val current = request
        if (current != null) {
            key(current.entry.url) {
                when (current.entry.kind) {
                    FileKind.IMAGE -> ImageViewerScreen(
                        entry = current.entry,
                        server = current.server,
                        onBack = { request = null },
                    )

                    FileKind.TEXT -> TextViewerScreen(
                        entry = current.entry,
                        server = current.server,
                        onBack = { request = null },
                    )

                    else -> PlayerScreen(
                        item = VideoItem(
                            id = "entry_${current.entry.url}",
                            title = current.entry.displayName,
                            path = current.entry.url,
                            sizeBytes = current.entry.sizeBytes,
                        ),
                        server = current.server,
                        onBack = { request = null },
                    )
                }
            }
        }
    }
}
