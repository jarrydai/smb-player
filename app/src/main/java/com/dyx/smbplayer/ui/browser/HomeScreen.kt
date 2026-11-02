package com.dyx.smbplayer.ui.browser

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dyx.smbplayer.data.model.DiscoveredService
import com.dyx.smbplayer.data.model.RemoteProtocol
import com.dyx.smbplayer.data.model.RemoteServer
import com.dyx.smbplayer.ui.common.EmptyState
import com.dyx.smbplayer.ui.common.IconTile
import com.dyx.smbplayer.ui.common.SectionHeader
import com.dyx.smbplayer.ui.common.TagPill
import com.dyx.smbplayer.ui.common.headerBrush

/**
 * 首页：已保存的网络服务器（SMB / WebDAV）列表 + 局域网发现。
 */
@Composable
fun HomeScreen(
    servers: List<RemoteServer>,
    discovered: List<DiscoveredService>,
    discovering: Boolean,
    onOpenServer: (RemoteServer) -> Unit,
    onAddServer: () -> Unit,
    onEditServer: (RemoteServer) -> Unit,
    onDeleteServer: (RemoteServer) -> Unit,
    onToggleScan: () -> Unit,
    onPickDiscovered: (DiscoveredService) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<RemoteServer?>(null) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            HomeHeader(onOpenSettings = onOpenSettings)

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 108.dp),
            ) {
                if (servers.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Rounded.Dns,
                            title = "还没有添加服务器",
                            message = "支持 SMB 共享与 WebDAV（群晖、威联通、Nextcloud、坚果云…），填好地址即可串流播放。",
                            primaryLabel = "添加服务器",
                            onPrimary = onAddServer,
                            secondaryLabel = "扫描局域网",
                            onSecondary = onToggleScan,
                        )
                    }
                } else {
                    item {
                        SectionHeader(
                            title = "我的服务器",
                            subtitle = "共 ${servers.size} 个",
                        )
                    }
                    items(servers, key = { it.id }) { server ->
                        ServerCard(
                            server = server,
                            onClick = { onOpenServer(server) },
                            onEdit = { onEditServer(server) },
                            onDelete = { pendingDelete = server },
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }

                item {
                    SectionHeader(
                        title = "局域网发现",
                        subtitle = "自动搜索同一网络内的 SMB / WebDAV 服务",
                        trailing = {
                            TextButton(onClick = onToggleScan) {
                                Icon(
                                    Icons.Rounded.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(if (discovering) "扫描中" else "扫描", style = MaterialTheme.typography.labelMedium)
                            }
                        },
                    )
                }

                item { DiscoveryBlock(discovered, discovering, onPickDiscovered, onToggleScan) }
            }
        }

        if (servers.isNotEmpty()) {
            ExtendedFloatingActionButton(
                onClick = onAddServer,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("添加服务器", style = MaterialTheme.typography.labelLarge) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(20.dp),
            )
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            shape = RoundedCornerShape(24.dp),
            title = { Text("删除服务器", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    "确定要移除「${target.name}」吗？只删除本机保存的配置，不会影响服务器上的文件。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteServer(target)
                    pendingDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun HomeHeader(onOpenSettings: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clipToBounds()
            .background(headerBrush())
            .statusBarsPadding()
    ) {
        // 右上角柔和光斑，让顶部不那么平
        Box(
            Modifier
                .size(200.dp)
                .offset(x = 210.dp, y = (-80).dp)
                .background(
                    Brush.radialGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                            MaterialTheme.colorScheme.primary.copy(alpha = 0f),
                        )
                    ),
                    CircleShape,
                )
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "网络播放器",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Lan,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "SMB · WebDAV · 局域网直连播放",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Surface(
                onClick = onOpenSettings,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                shadowElevation = 1.dp,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.Settings,
                        contentDescription = "设置",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(21.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerCard(
    server: RemoteServer,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val isWebDav = server.protocol == RemoteProtocol.WEBDAV
            IconTile(
                icon = if (isWebDav) Icons.Rounded.Cloud else Icons.Rounded.Dns,
                container = if (isWebDav) MaterialTheme.colorScheme.tertiaryContainer
                else MaterialTheme.colorScheme.primaryContainer,
                tint = if (isWebDav) MaterialTheme.colorScheme.onTertiaryContainer
                else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    server.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    server.addressLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TagPill(
                        text = server.protocol.label,
                        container = if (isWebDav) MaterialTheme.colorScheme.tertiaryContainer
                        else MaterialTheme.colorScheme.primaryContainer,
                        contentColor = if (isWebDav) MaterialTheme.colorScheme.onTertiaryContainer
                        else MaterialTheme.colorScheme.primary,
                    )
                    if (server.anonymous) {
                        TagPill(
                            text = "匿名访问",
                            icon = Icons.Rounded.LockOpen,
                            container = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    } else {
                        TagPill(
                            text = server.username.ifBlank { "需要账号" },
                            icon = Icons.Rounded.Lock,
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = "更多",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("编辑配置") },
                        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiscoveryBlock(
    discovered: List<DiscoveredService>,
    discovering: Boolean,
    onPickDiscovered: (DiscoveredService) -> Unit,
    onToggleScan: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            when {
                discovering -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "正在扫描局域网…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                discovered.isEmpty() -> Column {
                    Text(
                        "未发现可用的网络服务",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "部分 NAS 未开启 mDNS 广播，可直接手动填写 IP 添加。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onToggleScan,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("开始扫描", style = MaterialTheme.typography.labelMedium)
                    }
                }

                else -> Column {
                    Text(
                        "点击即可填入地址与协议",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        discovered.forEach { service ->
                            val isWebDav = service.protocol == RemoteProtocol.WEBDAV
                            val container = if (isWebDav) MaterialTheme.colorScheme.tertiaryContainer
                            else MaterialTheme.colorScheme.primaryContainer
                            val content = if (isWebDav) MaterialTheme.colorScheme.onTertiaryContainer
                            else MaterialTheme.colorScheme.onPrimaryContainer
                            Surface(
                                onClick = { onPickDiscovered(service) },
                                shape = CircleShape,
                                color = container,
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        if (isWebDav) Icons.Rounded.Cloud else Icons.Rounded.Dns,
                                        contentDescription = null,
                                        tint = content,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        service.host,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = content,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        service.protocol.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = content.copy(alpha = 0.7f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
