package com.dyx.smbplayer.ui.browser

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dyx.smbplayer.data.FileOpMode
import com.dyx.smbplayer.data.model.RemoteEntry
import com.dyx.smbplayer.data.model.RemoteProtocol
import com.dyx.smbplayer.data.model.RemoteServer
import com.dyx.smbplayer.viewmodel.BrowserViewModel

/**
 * 浏览层容器：在「服务器列表」和「目录浏览」之间切换，
 * 并承载共享文件的批量操作（多选 / 复制 / 移动 / 删除 / 重命名 / 新建文件夹）。
 */
@Composable
fun BrowserScreen(onOpenEntry: (RemoteEntry, RemoteServer) -> Unit) {
    val vm: BrowserViewModel = viewModel()
    val servers by vm.servers.collectAsState()
    val browse by vm.browse.collectAsState()
    val ops by vm.ops.collectAsState()
    val discovered by vm.discovered.collectAsState()
    val discovering by vm.discovering.collectAsState()
    val seekRangeSeconds by vm.seekRangeSeconds.collectAsState()
    val context = LocalContext.current

    var showAddDialog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showCreateFolder by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<RemoteServer?>(null) }
    var prefillHost by remember { mutableStateOf<String?>(null) }
    var prefillProtocol by remember { mutableStateOf(RemoteProtocol.SMB) }

    val actions = FileOpsActions(
        startSelection = { url -> vm.startSelection(url) },
        toggle = { url -> vm.toggleSelection(url) },
        selectAll = { vm.selectAll() },
        clearSelection = { vm.clearSelection() },
        copy = { vm.beginTransfer(FileOpMode.COPY) },
        move = { vm.beginTransfer(FileOpMode.MOVE) },
        delete = { showDeleteConfirm = true },
        rename = { showRename = true },
        createFolder = { showCreateFolder = true },
    )

    // 返回键优先级：目标选择器 > 退出多选 > 目录逐级返回；操作执行中不响应
    BackHandler(enabled = browse.server != null || ops.selecting || ops.picker != null) {
        if (ops.busy != null) return@BackHandler
        when {
            ops.picker != null -> vm.dismissPicker()
            ops.selecting -> vm.clearSelection()
            browse.server != null -> vm.goUp()
        }
    }

    // 成功提示用 Toast，失败明细走结果对话框
    LaunchedEffect(ops.toast) {
        ops.toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.consumeToast()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = browse.server,
            transitionSpec = {
                if (targetState != null) {
                    (slideInHorizontally(tween(260)) { it / 5 } + fadeIn(tween(220))) togetherWith
                            fadeOut(tween(140))
                } else {
                    fadeIn(tween(220)) togetherWith
                            (slideOutHorizontally(tween(260)) { it / 5 } + fadeOut(tween(160)))
                }
            },
            label = "browse",
        ) { server ->
            if (server == null) {
                HomeScreen(
                    servers = servers,
                    discovered = discovered,
                    discovering = discovering,
                    onOpenServer = { vm.connect(it) },
                    onAddServer = {
                        prefillHost = null
                        prefillProtocol = RemoteProtocol.SMB
                        showAddDialog = true
                    },
                    onEditServer = { editingServer = it },
                    onDeleteServer = { vm.deleteServer(it.id) },
                    onToggleScan = {
                        if (discovering) vm.stopDiscovery(context) else vm.startDiscovery(context)
                    },
                    onPickDiscovered = { service ->
                        // WebDAV 端口多样（5005/5006…），发现到的一并带上；SMB 默认 445 不必写
                        prefillHost = if (service.port != 0 && service.port != 445) {
                            "${service.host}:${service.port}"
                        } else {
                            service.host
                        }
                        prefillProtocol = service.protocol
                        showAddDialog = true
                    },
                    onOpenSettings = { showSettings = true },
                )
            } else {
                DirListScreen(
                    server = server,
                    path = browse.path,
                    entries = browse.entries,
                    loading = browse.loading,
                    error = browse.error,
                    ops = ops,
                    actions = actions,
                    onBack = { vm.goUp() },
                    onOpenDir = { vm.openDir(it) },
                    onOpenEntry = { entry -> onOpenEntry(entry, server) },
                    onExitToList = { vm.exitBrowse() },
                    onRetry = { vm.retry() },
                )
            }
        }

        // 操作执行中盖一层遮罩，避免重复点击
        if (ops.busy != null) {
            BusyOverlay(
                label = ops.busy!!,
                progress = ops.progress,
                current = ops.currentName,
            )
        }
    }

    if (showSettings) {
        SettingsSheet(
            seekRangeSeconds = seekRangeSeconds,
            onSeekRangeChange = { vm.setSeekRange(it) },
            onDismiss = { showSettings = false },
        )
    }

    val dialogOpen = showAddDialog || editingServer != null
    if (dialogOpen) {
        ServerDialog(
            initial = editingServer,
            prefillHost = prefillHost,
            prefillProtocol = prefillProtocol,
            onTest = { server, onResult -> vm.testConnection(server, onResult) },
            onSave = { server ->
                vm.saveServer(server)
                showAddDialog = false
                editingServer = null
                prefillHost = null
            },
            onDismiss = {
                showAddDialog = false
                editingServer = null
                prefillHost = null
            },
        )
    }

    if (showDeleteConfirm) {
        val names = vm.selectedEntries().map { it.displayName }
        DeleteConfirmDialog(
            count = names.size,
            names = names,
            onConfirm = {
                showDeleteConfirm = false
                vm.deleteSelected()
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }

    if (showRename) {
        NameInputDialog(
            title = "重命名",
            label = "新名称",
            initial = vm.selectedEntries().firstOrNull()?.displayName.orEmpty(),
            confirmLabel = "确定",
            onConfirm = {
                showRename = false
                vm.renameSelected(it)
            },
            onDismiss = { showRename = false },
        )
    }

    if (showCreateFolder) {
        NameInputDialog(
            title = "新建文件夹",
            label = "文件夹名称",
            initial = "",
            confirmLabel = "创建",
            onConfirm = {
                showCreateFolder = false
                vm.createFolder(it)
            },
            onDismiss = { showCreateFolder = false },
        )
    }

    ops.picker?.let { picker ->
        browse.server?.let { server ->
            DestinationPickerDialog(
                server = server,
                picker = picker,
                itemCount = vm.selectedEntries().size,
                busy = ops.busy != null,
                onOpenDir = { vm.pickerOpenDir(it) },
                onGoUp = { vm.pickerGoUp() },
                onToRoot = { vm.pickerToRoot() },
                onCreateFolder = { showCreateFolder = true },
                onConfirm = { vm.confirmTransfer() },
                onDismiss = { vm.dismissPicker() },
            )
        }
    }

    ops.result?.let { result ->
        OpResultDialog(
            result = result,
            label = ops.resultLabel,
            onDismiss = { vm.ackResult() },
        )
    }
}
