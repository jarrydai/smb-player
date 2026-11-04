package com.dyx.smbplayer.viewmodel

import android.app.Application
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dyx.smbplayer.data.FileOpMode
import com.dyx.smbplayer.data.FileOps
import com.dyx.smbplayer.data.OpFailure
import com.dyx.smbplayer.data.OpResult
import com.dyx.smbplayer.data.PrefsStore
import com.dyx.smbplayer.data.RemoteFiles
import com.dyx.smbplayer.data.model.DiscoveredService
import com.dyx.smbplayer.data.model.RemoteEntry
import com.dyx.smbplayer.data.model.RemoteProtocol
import com.dyx.smbplayer.data.model.RemoteServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BrowserViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = PrefsStore(app)

    val servers: StateFlow<List<RemoteServer>> = prefs.serversFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 整屏横滑快进秒数（设置项） */
    val seekRangeSeconds: StateFlow<Int> = prefs.seekRangeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PrefsStore.DEFAULT_SEEK_RANGE_SECONDS)

    fun setSeekRange(seconds: Int) {
        viewModelScope.launch { prefs.setSeekRangeSeconds(seconds) }
    }

    /** 远程目录浏览状态 */
    data class BrowseState(
        val server: RemoteServer? = null,
        val path: String = "",
        val entries: List<RemoteEntry> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
    )

    private val _browse = MutableStateFlow(BrowseState())
    val browse: StateFlow<BrowseState> = _browse.asStateFlow()

    private val _discovered = MutableStateFlow<List<DiscoveredService>>(emptyList())
    val discovered: StateFlow<List<DiscoveredService>> = _discovered.asStateFlow()

    private val _discovering = MutableStateFlow(false)
    val discovering: StateFlow<Boolean> = _discovering.asStateFlow()

    fun saveServer(server: RemoteServer) {
        viewModelScope.launch { prefs.saveServer(server) }
    }

    fun deleteServer(id: Long) {
        viewModelScope.launch { prefs.deleteServer(id) }
    }

    fun testConnection(server: RemoteServer, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                RemoteFiles.testConnection(server)
                withContext(Dispatchers.Main) { onResult(true, "连接成功") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(false, e.message ?: "连接失败") }
            }
        }
    }

    fun connect(server: RemoteServer) = listInto(RemoteFiles.rootUrl(server), server)

    fun openDir(url: String) {
        val server = _browse.value.server ?: return
        listInto(url, server)
    }

    /** 返回上一级；已在根目录时退出浏览（回到服务器列表） */
    fun goUp() {
        val state = _browse.value
        val server = state.server ?: return
        val parent = RemoteFiles.parentOf(server, state.path)
        if (parent == null) exitBrowse() else listInto(parent, server)
    }

    fun exitBrowse() {
        _browse.value = BrowseState()
        _ops.value = OpsUiState()
    }

    fun retry() {
        val state = _browse.value
        val server = state.server ?: return
        if (state.path.isBlank()) connect(server) else listInto(state.path, server)
    }

    private fun listInto(url: String, server: RemoteServer) {
        _browse.value = _browse.value.copy(path = url, loading = true, error = null)
        // 换目录后旧选中项已不在列表里，清掉多选状态
        _ops.value = OpsUiState()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entries = RemoteFiles.listDir(server, url)
                _browse.value = BrowseState(server, url, entries, false, null)
            } catch (e: Exception) {
                val msg = e.message ?: e.cause?.message ?: e.javaClass.simpleName
                _browse.value = BrowseState(server, url, emptyList(), false, msg)
            }
        }
    }

    // ---------------- 批量文件操作 ----------------

    data class PickerState(
        val mode: FileOpMode,
        val path: String,
        val dirs: List<RemoteEntry> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
    )

    data class OpsUiState(
        val selecting: Boolean = false,
        val selection: Set<String> = emptySet(),
        val busy: String? = null,
        val progress: Pair<Int, Int>? = null,
        val currentName: String = "",
        val picker: PickerState? = null,
        val result: OpResult? = null,
        val resultLabel: String = "操作结果",
        val toast: String? = null,
    )

    private val _ops = MutableStateFlow(OpsUiState())
    val ops: StateFlow<OpsUiState> = _ops.asStateFlow()

    private var pickerJob: Job? = null

    /** 当前选中的完整条目（顺序与列表一致） */
    fun selectedEntries(): List<RemoteEntry> {
        val selected = _ops.value.selection
        return _browse.value.entries.filter { it.url in selected }
    }

    fun startSelection(url: String? = null) {
        _ops.value = _ops.value.copy(
            selecting = true,
            selection = if (url != null) setOf(url) else emptySet(),
        )
    }

    fun toggleSelection(url: String) {
        val cur = _ops.value.selection
        _ops.value = _ops.value.copy(
            selecting = true,
            selection = if (url in cur) cur - url else cur + url,
        )
    }

    fun selectAll() {
        _ops.value = _ops.value.copy(
            selecting = true,
            selection = _browse.value.entries.map { it.url }.toSet(),
        )
    }

    fun clearSelection() {
        _ops.value = _ops.value.copy(selecting = false, selection = emptySet())
    }

    // ---- 目标文件夹选择器 ----

    fun beginTransfer(mode: FileOpMode) {
        val path = _browse.value.path
        if (path.isBlank() || selectedEntries().isEmpty() || _ops.value.busy != null) return
        loadPicker(mode, path)
    }

    private fun loadPicker(mode: FileOpMode, path: String) {
        val server = _browse.value.server ?: return
        pickerJob?.cancel()
        _ops.value = _ops.value.copy(picker = PickerState(mode, path, loading = true))
        pickerJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val dirs = FileOps.listDirs(server, path)
                _ops.value = _ops.value.copy(picker = PickerState(mode, path, dirs))
            } catch (e: Exception) {
                _ops.value = _ops.value.copy(
                    picker = PickerState(mode, path, emptyList(), false, e.message ?: "读取目录失败"),
                )
            }
        }
    }

    fun pickerOpenDir(url: String) {
        val p = _ops.value.picker ?: return
        loadPicker(p.mode, url)
    }

    fun pickerGoUp() {
        val p = _ops.value.picker ?: return
        val server = _browse.value.server ?: return
        val parent = RemoteFiles.parentOf(server, p.path) ?: return
        loadPicker(p.mode, parent)
    }

    fun pickerToRoot() {
        val p = _ops.value.picker ?: return
        val server = _browse.value.server ?: return
        loadPicker(p.mode, RemoteFiles.rootUrl(server))
    }

    fun dismissPicker() {
        pickerJob?.cancel()
        _ops.value = _ops.value.copy(picker = null)
    }

    // ---- 执行 ----

    fun confirmTransfer() {
        val p = _ops.value.picker ?: return
        val server = _browse.value.server ?: return
        val items = selectedEntries()
        if (items.isEmpty() || _ops.value.busy != null) return
        val mode = p.mode
        val dest = p.path
        // 先收起选择器，让执行中的进度遮罩能露出来
        _ops.value = _ops.value.copy(picker = null)
        runOp(
            label = if (mode == FileOpMode.COPY) "正在复制…" else "正在移动…",
            resultLabel = if (mode == FileOpMode.COPY) "复制结果" else "移动结果",
            total = items.size,
            success = if (mode == FileOpMode.COPY) "已复制 ${items.size} 项" else "已移动 ${items.size} 项",
        ) { onProgress ->
            if (mode == FileOpMode.COPY) FileOps.copy(server, items, dest, onProgress)
            else FileOps.move(server, items, dest, onProgress)
        }
    }

    fun deleteSelected() {
        val server = _browse.value.server ?: return
        val items = selectedEntries()
        if (items.isEmpty() || _ops.value.busy != null) return
        runOp("正在删除…", "删除结果", items.size, "已删除 ${items.size} 项") { onProgress ->
            FileOps.delete(server, items, onProgress)
        }
    }

    fun renameSelected(newName: String) {
        val server = _browse.value.server ?: return
        val item = selectedEntries().firstOrNull() ?: return
        val parent = _browse.value.path
        if (parent.isBlank() || _ops.value.busy != null) return
        viewModelScope.launch(Dispatchers.IO) {
            _ops.value = _ops.value.copy(busy = "正在重命名…")
            val result = try {
                FileOps.rename(server, item, parent, newName)
                OpResult(1, emptyList())
            } catch (e: Exception) {
                OpResult(0, listOf(OpFailure(item.displayName, e.message ?: "重命名失败")))
            }
            reloadCurrent()
            finish(result, "重命名结果", "已重命名为 $newName")
        }
    }

    fun createFolder(name: String) {
        val server = _browse.value.server ?: return
        val target = _ops.value.picker?.path ?: _browse.value.path
        if (target.isBlank() || _ops.value.busy != null) return
        viewModelScope.launch(Dispatchers.IO) {
            _ops.value = _ops.value.copy(busy = "正在新建文件夹…")
            val result = try {
                FileOps.mkdir(server, target, name)
                OpResult(1, emptyList())
            } catch (e: Exception) {
                OpResult(0, listOf(OpFailure(name, e.message ?: "新建失败")))
            }
            val picker = _ops.value.picker
            if (picker != null) {
                loadPicker(picker.mode, picker.path)
                _ops.value = _ops.value.copy(
                    busy = null,
                    result = if (result.allSucceeded) null else result,
                    resultLabel = "新建文件夹",
                )
            } else {
                reloadCurrent()
                finish(result, "新建文件夹", "已新建文件夹 $name")
            }
        }
    }

    fun ackResult() {
        _ops.value = _ops.value.copy(result = null)
    }

    fun consumeToast() {
        _ops.value = _ops.value.copy(toast = null)
    }

    private fun runOp(
        label: String,
        resultLabel: String,
        total: Int,
        success: String,
        block: ((Int, Int, String) -> Unit) -> OpResult,
    ) {
        if (_ops.value.busy != null) return
        viewModelScope.launch(Dispatchers.IO) {
            _ops.value = _ops.value.copy(busy = label, progress = 0 to total, currentName = "")
            val result = try {
                block { done, count, current ->
                    _ops.value = _ops.value.copy(progress = done to count, currentName = current)
                }
            } catch (e: Exception) {
                OpResult(0, listOf(OpFailure("操作", e.message ?: "失败")))
            }
            reloadCurrent()
            finish(result, resultLabel, success)
        }
    }

    private fun finish(result: OpResult, resultLabel: String, successToast: String) {
        _ops.value = _ops.value.copy(
            busy = null,
            progress = null,
            currentName = "",
            selecting = false,
            selection = emptySet(),
            picker = null,
            result = if (result.allSucceeded) null else result,
            resultLabel = resultLabel,
            toast = if (result.allSucceeded) successToast else null,
        )
    }

    /** 操作完成后原地刷新，保留路径与滚动位置由 UI 自己维护 */
    private suspend fun reloadCurrent() {
        val st = _browse.value
        val server = st.server ?: return
        if (st.path.isBlank()) return
        try {
            val entries = RemoteFiles.listDir(server, st.path)
            _browse.value = st.copy(entries = entries, loading = false, error = null)
        } catch (e: Exception) {
            _browse.value = st.copy(loading = false, error = e.message ?: "刷新目录失败")
        }
    }

    // ---- 局域网服务自动发现（NsdManager / mDNS：SMB、WebDAV） ----

    private var nsdManager: NsdManager? = null
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()

    /** 正在运行的发现任务数（三种服务类型并发，全部结束才关掉"扫描中"） */
    private var runningDiscoveries = 0

    private val serviceTypes = listOf(
        "_smb._tcp." to RemoteProtocol.SMB,
        "_webdav._tcp." to RemoteProtocol.WEBDAV,
        "_webdavs._tcp." to RemoteProtocol.WEBDAV,
    )

    fun startDiscovery(context: Context) {
        if (_discovering.value) return
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsdManager = manager
        _discovered.value = emptyList()
        _discovering.value = true

        serviceTypes.forEach { (type, protocol) ->
            val resolveListener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host?.hostAddress ?: return
                    val item = DiscoveredService(
                        host = host,
                        port = info.port,
                        protocol = protocol,
                        name = info.serviceName ?: host,
                    )
                    val existing = _discovered.value
                    if (existing.none { it.host == host && it.protocol == protocol }) {
                        _discovered.value = existing + item
                    }
                }
            }

            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    finishOne()
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    try {
                        manager.resolveService(serviceInfo, resolveListener)
                    } catch (_: Exception) {
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
                override fun onDiscoveryStopped(serviceType: String) {
                    finishOne()
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    finishOne()
                }
            }
            try {
                manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
                runningDiscoveries++
                listeners += listener
            } catch (_: Exception) {
                // 部分机型不支持 WebDAV 服务类型，忽略即可
            }
        }
        if (runningDiscoveries == 0) _discovering.value = false
    }

    private fun finishOne() {
        if (runningDiscoveries > 0) runningDiscoveries--
        if (runningDiscoveries == 0) _discovering.value = false
    }

    fun stopDiscovery(context: Context) {
        _discovering.value = false
        runningDiscoveries = 0
        listeners.forEach { listener -> runCatching { nsdManager?.stopServiceDiscovery(listener) } }
        listeners.clear()
    }

    override fun onCleared() {
        super.onCleared()
        listeners.forEach { listener -> runCatching { nsdManager?.stopServiceDiscovery(listener) } }
        listeners.clear()
        nsdManager = null
    }
}
