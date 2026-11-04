package com.dyx.smbplayer.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.dyx.smbplayer.data.FileOpMode
import com.dyx.smbplayer.data.OpResult
import com.dyx.smbplayer.data.RemoteFiles
import com.dyx.smbplayer.data.model.RemoteEntry
import com.dyx.smbplayer.data.model.RemoteServer
import com.dyx.smbplayer.viewmodel.BrowserViewModel

/** 目录页与容器之间的操作回调集合 */
data class FileOpsActions(
    val startSelection: (String?) -> Unit,
    val toggle: (String) -> Unit,
    val selectAll: () -> Unit,
    val clearSelection: () -> Unit,
    val copy: () -> Unit,
    val move: () -> Unit,
    val delete: () -> Unit,
    val rename: () -> Unit,
    val createFolder: () -> Unit,
)

/** 多选模式下的底部操作条 */
@Composable
fun FileOpsBar(count: Int, enabled: Boolean, actions: FileOpsActions) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 10.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OpAction(Icons.Rounded.ContentCopy, "复制", count > 0 && enabled, actions.copy)
            OpAction(Icons.Rounded.ContentCut, "移动", count > 0 && enabled, actions.move)
            OpAction(
                Icons.Rounded.Delete, "删除", count > 0 && enabled, actions.delete,
                tint = MaterialTheme.colorScheme.error,
            )
            OpAction(Icons.Rounded.Edit, "重命名", count == 1 && enabled, actions.rename)
            OpAction(Icons.Rounded.Add, "新建", enabled, actions.createFolder)
        }
    }
}

@Composable
private fun OpAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(64.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        val color = if (enabled) tint else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        Surface(shape = CircleShape, color = color.copy(alpha = 0.12f), modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(21.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, color = color, maxLines = 1)
    }
}

/** 勾选圈：多选模式下替换条目右侧的播放/箭头图标 */
@Composable
fun SelectionCheck(selected: Boolean) {
    Box(
        Modifier
            .size(26.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = "已选中",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        } else {
            // 内圈掏空，形成空心圆
            Box(
                Modifier
                    .size(22.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape),
            )
        }
    }
}

/** 删除二次确认 */
@Composable
fun DeleteConfirmDialog(
    count: Int,
    names: List<String>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除选中的 $count 项？") },
        text = {
            Column {
                Text(
                    "这些文件会从共享服务器上永久删除，无法撤销。当前目录内的子文件夹会连同内容一起删除。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                val preview = names.take(4).joinToString("、")
                Text(
                    preview + if (names.size > 4) " 等 ${names.size} 项" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("确认删除") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 重命名 / 新建文件夹共用的输入框 */
@Composable
fun NameInputDialog(
    title: String,
    label: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 目标文件夹选择器：从当前目录出发，逐级进入/返回，支持新建文件夹 */
@Composable
fun DestinationPickerDialog(
    server: RemoteServer,
    picker: BrowserViewModel.PickerState,
    itemCount: Int,
    busy: Boolean,
    onOpenDir: (String) -> Unit,
    onGoUp: () -> Unit,
    onToRoot: () -> Unit,
    onCreateFolder: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val action = if (picker.mode == FileOpMode.COPY) "复制到此处" else "移动到此处"
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
        ) {
            Column(Modifier.padding(top = 18.dp, bottom = 12.dp)) {
                Text(
                    if (picker.mode == FileOpMode.COPY) "复制 $itemCount 项到…" else "移动 $itemCount 项到…",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    RemoteFiles.prettyPath(server, picker.path),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(8.dp))

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onGoUp, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "上一级")
                    }
                    Text("选择目标文件夹", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onToRoot, enabled = !busy) { Text("根目录") }
                    TextButton(onClick = onCreateFolder, enabled = !busy) { Text("新建") }
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp, max = 320.dp),
                ) {
                    when {
                        picker.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                        }

                        picker.error != null -> Column(
                            Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                picker.error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { onOpenDir(picker.path) }) { Text("重试") }
                        }

                        picker.dirs.isEmpty() -> Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "这里没有子文件夹",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        else -> LazyColumn(Modifier.fillMaxSize()) {
                            items(picker.dirs, key = { it.url }) { dir ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !busy) { onOpenDir(dir.url) }
                                        .padding(horizontal = 18.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Rounded.Folder,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(21.dp),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        dir.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Icon(
                                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onConfirm, enabled = !busy && picker.error == null) {
                        Text(action)
                    }
                }
            }
        }
    }
}

/** 部分失败时列出原因；全部成功时只在底栏提示，不弹这个框 */
@Composable
fun OpResultDialog(result: OpResult, label: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$label（成功 ${result.succeeded} 项，失败 ${result.failures.size} 项）") },
        text = {
            Column(Modifier.heightIn(max = 320.dp)) {
                result.failures.forEach { failure ->
                    Text(
                        "· ${failure.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        failure.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}

/** 操作执行中的全屏遮罩，避免用户重复点击或误以为卡死 */
@Composable
fun BusyOverlay(label: String, progress: Pair<Int, Int>?, current: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
            .clickable(enabled = false, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp) {
            Column(
                Modifier.padding(horizontal = 30.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(38.dp))
                Spacer(Modifier.height(16.dp))
                Text(label, style = MaterialTheme.typography.titleSmall)
                if (progress != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${progress.first} / ${progress.second}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (current.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        current,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
