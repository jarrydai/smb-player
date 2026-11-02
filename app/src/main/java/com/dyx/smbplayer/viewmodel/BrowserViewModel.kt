package com.dyx.smbplayer.viewmodel

import android.app.Application
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dyx.smbplayer.data.PrefsStore
import com.dyx.smbplayer.data.RemoteFiles
import com.dyx.smbplayer.data.model.DiscoveredService
import com.dyx.smbplayer.data.model.RemoteEntry
import com.dyx.smbplayer.data.model.RemoteProtocol
import com.dyx.smbplayer.data.model.RemoteServer
import kotlinx.coroutines.Dispatchers
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
    }

    fun retry() {
        val state = _browse.value
        val server = state.server ?: return
        if (state.path.isBlank()) connect(server) else listInto(state.path, server)
    }

    private fun listInto(url: String, server: RemoteServer) {
        _browse.value = _browse.value.copy(path = url, loading = true, error = null)
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
