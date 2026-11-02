package com.dyx.smbplayer.data

import com.dyx.smbplayer.data.model.FileKind
import com.dyx.smbplayer.data.model.RemoteEntry
import com.dyx.smbplayer.data.model.RemoteServer
import jcifs.CIFSContext
import jcifs.CIFSException
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import java.io.IOException
import java.util.LinkedHashMap
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * jcifs-ng SMB1/2/3 协议封装：认证、列目录、连接测试、整文件读取。
 */
object SmbSource {

    /** 按服务器配置缓存 CIFS 上下文，复用底层连接池 */
    private val contextCache = ConcurrentHashMap<String, CIFSContext>()

    private fun cacheKey(server: RemoteServer): String =
        "${server.id}|${server.host}|${server.username}|${server.password.hashCode()}|${server.anonymous}"

    fun contextFor(server: RemoteServer): CIFSContext =
        contextCache.getOrPut(cacheKey(server)) { buildContext(server) }

    private fun buildContext(server: RemoteServer): CIFSContext {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.connTimeout", "10000")
            // 大文件（>4GB）seek 到文件尾部读索引时，单次请求可能较久，超时放宽避免误判失败
            setProperty("jcifs.smb.client.responseTimeout", "60000")
            setProperty("jcifs.smb.client.soTimeout", "120000")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            // 跳过 DFS 探测：不少 NAS/Windows 共享会因此多一次往返，起播变慢
            setProperty("jcifs.smb.client.dfs.disabled", "true")
            // 加大单次读写块与收发缓冲：减少往返次数，拖动进度条后灌数据更快
            setProperty("jcifs.smb.client.rcv_buf_size", "262144")
            setProperty("jcifs.smb.client.snd_buf_size", "262144")
            setProperty("jcifs.smb.client.maxRequestSize", "1048576")
            setProperty("jcifs.smb.client.transaction_buf_size", "65535")
        }
        return try {
            val base = BaseContext(PropertyConfiguration(props))
            val auth = if (server.anonymous) {
                NtlmPasswordAuthenticator(NtlmPasswordAuthenticator.AuthenticationType.NULL)
            } else {
                NtlmPasswordAuthenticator(server.domain, server.username, server.password)
            }
            base.withCredentials(auth)
        } catch (e: CIFSException) {
            throw IOException("SMB 配置初始化失败", e)
        }
    }

    fun rootUrl(server: RemoteServer): String {
        val share = server.share.trim().trim('/')
        // 共享名留空时浏览服务器根目录，列目录会返回全部共享列表
        return if (share.isEmpty()) "smb://${server.host}/" else "smb://${server.host}/$share/"
    }

    /** 列出目录内容（目录在前、按名称排序） */
    @Throws(IOException::class)
    fun listDir(server: RemoteServer, dirUrl: String): List<RemoteEntry> {
        val url = if (dirUrl.endsWith("/")) dirUrl else "$dirUrl/"
        val ctx = contextFor(server)
        val dir = SmbFile(url, ctx)
        if (!dir.exists()) throw IOException("路径不存在：$url")
        if (!dir.isDirectory) throw IOException("不是目录：$url")
        return dir.listFiles()
            .filter { it.name != "." && it.name != ".." }
            .map {
                val isDir = it.isDirectory
                RemoteEntry(
                    name = it.name,
                    displayName = it.name.removeSuffix("/"),
                    isDir = isDir,
                    sizeBytes = it.length(),
                    url = it.url.toString(),
                    kind = RemoteFiles.kindOf(it.name, isDir),
                )
            }
            // 只展示目录和可查看的文件，未知类型一律隐藏
            .filter { it.isDir || it.kind != FileKind.OTHER }
            .sortedWith(compareByDescending<RemoteEntry> { it.isDir }.thenBy { it.displayName.lowercase() })
    }

    /** 连接测试：可访问主机，且（若填写）共享可访问 */
    @Throws(IOException::class)
    fun testConnection(server: RemoteServer) {
        val ctx = contextFor(server)
        val host = SmbFile("smb://${server.host}/", ctx)
        if (!host.exists()) throw IOException("无法连接主机 ${server.host}")
        val share = server.share.trim('/')
        if (share.isNotEmpty()) {
            val shareFile = SmbFile(rootUrl(server), ctx)
            if (!shareFile.exists()) throw IOException("共享 [$share] 不存在或无权限")
        }
    }

    /**
     * 播放前探测：确认文件可访问、是文件而非目录，返回文件大小。
     * 失败时抛出带明确中文原因的 IOException，供 UI 直接展示。
     */
    @Throws(IOException::class)
    fun probeFile(server: RemoteServer, url: String): Long {
        val ctx = contextFor(server)
        val handle = runCatching { openFile(url, ctx) }.getOrElse { openError ->
            val file = SmbFile(url, ctx)
            if (runCatching { file.exists() }.getOrDefault(false).not()) {
                throw IOException("文件不存在或无权访问：${file.name}")
            }
            if (runCatching { file.isDirectory }.getOrDefault(false)) {
                throw IOException("这是一个目录，不是视频文件")
            }
            throw (openError as? IOException ?: IOException("SMB 打开失败：$url", openError))
        }
        if (handle.length <= 0) throw IOException("文件大小为 0 或无法读取长度")
        return handle.length
    }

    /**
     * 一次性读取整个文件（图片 / 文本查看用）。
     * @param truncate 为 true 时超限只取前 maxBytes 字节（文本查看需要），否则报错
     */
    @Throws(IOException::class)
    fun readFile(server: RemoteServer, url: String, maxBytes: Long, truncate: Boolean): ByteArray {
        val ctx = contextFor(server)
        val handle = runCatching { openFile(url, ctx) }.getOrElse { openError ->
            val file = SmbFile(url, ctx)
            if (runCatching { file.exists() }.getOrDefault(false).not()) {
                throw IOException("文件不存在或无权访问：${file.name}")
            }
            throw (openError as? IOException ?: IOException("SMB 打开失败：$url", openError))
        }
        if (handle.length <= 0) throw IOException("文件大小为 0 或无法读取长度")
        if (handle.length > maxBytes && !truncate) {
            throw IOException(
                "文件过大（${"%.1f".format(handle.length / 1024.0 / 1024.0)} MB），超过查看上限",
            )
        }
        val target = minOf(handle.length, maxBytes)
        val buf = ByteArray(target.toInt())
        handle.raf.seek(0)
        var off = 0
        while (off < buf.size) {
            val n = handle.raf.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        if (off < buf.size && !truncate) {
            throw IOException("读取不完整（${off}/${buf.size} 字节），共享可能不稳定")
        }
        return if (off == buf.size) buf else buf.copyOf(off)
    }

    // ---------------- 播放句柄缓存 ----------------

    /**
     * 已打开的 SMB 文件句柄。拖动进度条时 ExoPlayer 会关闭再重开数据源，
     * 若每次都重新 open 文件就要多两三次 SMB 往返（局域网几十毫秒、跳到新位置还要等），
     * 这里按 URL 复用同一个句柄，seek 只在本地移动读取指针，几乎零延迟。
     */
    class CachedFile internal constructor(
        val url: String,
        val raf: SmbRandomAccessFile,
        val length: Long,
    ) {
        internal var closed = false
            private set

        internal fun closeQuietly() {
            if (closed) return
            closed = true
            try {
                raf.close()
            } catch (_: Exception) {
            }
        }
    }

    /** 最多同时保留几个文件句柄，避免切换多个视频后句柄堆积 */
    private const val MAX_CACHED_FILES = 3

    private val handleCache = LinkedHashMap<String, CachedFile>()

    @Synchronized
    @Throws(IOException::class)
    fun openFile(url: String, ctx: CIFSContext): CachedFile {
        handleCache[url]?.let { if (!it.closed) return it }
        val smbFile = SmbFile(url, ctx)
        val raf = try {
            SmbRandomAccessFile(smbFile, "r")
        } catch (e: Exception) {
            throw IOException("SMB 打开失败：$url", e)
        }
        val length = try {
            raf.length()
        } catch (e: Exception) {
            try {
                raf.close()
            } catch (_: Exception) {
            }
            throw IOException("SMB 读取文件大小失败：$url", e)
        }
        val cached = CachedFile(url, raf, length)
        handleCache[url] = cached
        // 超量淘汰最久未用的句柄
        while (handleCache.size > MAX_CACHED_FILES) {
            val eldest = handleCache.entries.firstOrNull() ?: break
            handleCache.remove(eldest.key)
            eldest.value.closeQuietly()
        }
        return cached
    }

    /** 该 URL 是否已有可用的缓存句柄（用于日志判断"复用/新建"） */
    @Synchronized
    fun isCached(url: String): Boolean = handleCache[url]?.let { !it.closed } ?: false

    /** 丢弃某个文件的句柄（读取失败后重建连接前调用） */
    @Synchronized
    fun invalidate(url: String) {
        handleCache.remove(url)?.closeQuietly()
    }

    /** 退出播放时释放全部句柄 */
    @Synchronized
    fun releaseFiles() {
        handleCache.values.forEach { it.closeQuietly() }
        handleCache.clear()
    }
}
