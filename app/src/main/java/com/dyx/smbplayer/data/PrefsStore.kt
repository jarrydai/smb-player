package com.dyx.smbplayer.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dyx.smbplayer.data.model.RemoteProtocol
import com.dyx.smbplayer.data.model.RemoteServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "smb_player")

/**
 * DataStore 持久化：远程服务器列表（JSON）+ 各视频播放进度。
 *
 * 数据格式向后兼容：旧版本只有 SMB 字段，缺失的协议字段按 SMB 处理，
 * 升级覆盖安装不会丢失已保存的服务器。
 */
class PrefsStore(private val context: Context) {

    companion object {
        private val KEY_SERVERS = stringPreferencesKey("smb_servers")
        private val KEY_SEEK_RANGE = intPreferencesKey("seek_range_seconds")
        private fun progressKeyOf(key: String) = longPreferencesKey("pos_$key")

        /** 整屏横滑默认对应 300 秒（5 分钟）；可在设置里改 */
        const val DEFAULT_SEEK_RANGE_SECONDS = 300
    }

    val serversFlow: Flow<List<RemoteServer>> = context.dataStore.data
        .map { prefs -> prefs[KEY_SERVERS]?.let(::decodeServers) ?: emptyList() }

    /** 整屏宽度横滑对应的快进/快退秒数 */
    val seekRangeFlow: Flow<Int> = context.dataStore.data
        .map { it[KEY_SEEK_RANGE] ?: DEFAULT_SEEK_RANGE_SECONDS }

    suspend fun setSeekRangeSeconds(seconds: Int) {
        context.dataStore.edit { it[KEY_SEEK_RANGE] = seconds }
    }

    suspend fun saveServer(server: RemoteServer) {
        val list = serversFlow.first().toMutableList()
        val idx = list.indexOfFirst { it.id == server.id }
        if (idx >= 0) list[idx] = server else list.add(server.copy(id = nextId(list)))
        setServers(list)
    }

    suspend fun deleteServer(id: Long) {
        setServers(serversFlow.first().filterNot { it.id == id })
    }

    private suspend fun setServers(list: List<RemoteServer>) {
        context.dataStore.edit { it[KEY_SERVERS] = encodeServers(list) }
    }

    private fun nextId(list: List<RemoteServer>): Long = (list.maxOfOrNull { it.id } ?: 0L) + 1L

    // ---- 播放进度 ----

    suspend fun getProgress(key: String): Long =
        context.dataStore.data.first()[progressKeyOf(key)] ?: 0L

    suspend fun setProgress(key: String, positionMs: Long) {
        context.dataStore.edit { it[progressKeyOf(key)] = positionMs }
    }

    suspend fun clearProgress(key: String) {
        context.dataStore.edit { it.remove(progressKeyOf(key)) }
    }

    // ---- JSON 序列化 ----

    private fun encodeServers(list: List<RemoteServer>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("protocol", it.protocol.name)
                    .put("host", it.host)
                    .put("share", it.share)
                    .put("domain", it.domain)
                    .put("username", it.username)
                    .put("password", it.password)
                    .put("anonymous", it.anonymous)
                    .put("secure", it.secure)
                    .put("insecureTls", it.insecureTls)
            )
        }
        return arr.toString()
    }

    private fun decodeServers(json: String): List<RemoteServer> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RemoteServer(
                id = o.optLong("id"),
                name = o.optString("name"),
                // 旧数据没有 protocol 字段，视为 SMB
                protocol = runCatching {
                    RemoteProtocol.valueOf(o.optString("protocol", "SMB"))
                }.getOrDefault(RemoteProtocol.SMB),
                host = o.optString("host"),
                share = o.optString("share"),
                domain = o.optString("domain"),
                username = o.optString("username"),
                password = o.optString("password"),
                anonymous = o.optBoolean("anonymous"),
                secure = o.optBoolean("secure"),
                insecureTls = o.optBoolean("insecureTls"),
            )
        }
    }.getOrDefault(emptyList())
}
