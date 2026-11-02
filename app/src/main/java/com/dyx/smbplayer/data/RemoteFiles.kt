package com.dyx.smbplayer.data

import com.dyx.smbplayer.data.model.FileKind
import com.dyx.smbplayer.data.model.RemoteEntry
import com.dyx.smbplayer.data.model.RemoteProtocol
import com.dyx.smbplayer.data.model.RemoteServer
import java.io.IOException
import java.net.URLDecoder

/**
 * 远程文件访问统一入口：按服务器协议分发到 [SmbSource] / [WebDavSource]。
 *
 * 上层（ViewModel / 查看器 / 播放器）只依赖这里，不再关心底层是 SMB 还是 WebDAV。
 */
object RemoteFiles {

    /** 播放器支持的视频扩展名 */
    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "avi", "mov", "webm", "flv", "ts", "m4v",
        "wmv", "rmvb", "mpg", "mpeg", "3gp", "m2ts", "vob", "ogv",
        "asf", "divx", "f4v", "m1v", "m2v", "ogm", "tp", "trp",
    )

    /** 图片查看器支持的扩展名 */
    private val IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif",
    )

    /** 文本查看器支持的扩展名 */
    private val TEXT_EXTENSIONS = setOf(
        "txt", "log", "md", "csv", "json", "xml", "ini", "cfg", "conf",
        "yml", "yaml", "properties", "srt", "ass", "html", "htm", "bat", "sh",
    )

    /** 单个文件读取上限（图片 64MB，文本 2MB） */
    const val MAX_IMAGE_BYTES = 64L * 1024 * 1024
    const val MAX_TEXT_BYTES = 2L * 1024 * 1024

    /** 按扩展名判断文件类型 */
    fun kindOf(name: String, isDir: Boolean): FileKind {
        if (isDir) return FileKind.DIR
        return when (name.substringAfterLast('.', "").lowercase()) {
            in VIDEO_EXTENSIONS -> FileKind.VIDEO
            in IMAGE_EXTENSIONS -> FileKind.IMAGE
            in TEXT_EXTENSIONS -> FileKind.TEXT
            else -> FileKind.OTHER // 未知扩展名：不在列表中展示
        }
    }

    // ---------------- 分发 ----------------

    /** 一个服务器的根地址（SMB 共享根 / WebDAV 基础路径根） */
    fun rootUrl(server: RemoteServer): String = when (server.protocol) {
        RemoteProtocol.SMB -> SmbSource.rootUrl(server)
        RemoteProtocol.WEBDAV -> WebDavSource.rootUrl(server)
    }

    @Throws(IOException::class)
    fun listDir(server: RemoteServer, dirUrl: String): List<RemoteEntry> =
        when (server.protocol) {
            RemoteProtocol.SMB -> SmbSource.listDir(server, dirUrl)
            RemoteProtocol.WEBDAV -> WebDavSource.listDir(server, dirUrl)
        }

    @Throws(IOException::class)
    fun testConnection(server: RemoteServer) {
        when (server.protocol) {
            RemoteProtocol.SMB -> SmbSource.testConnection(server)
            RemoteProtocol.WEBDAV -> WebDavSource.testConnection(server)
        }
    }

    /** 打开前探测：返回文件大小，失败抛出带中文原因的 IOException */
    @Throws(IOException::class)
    fun probeFile(server: RemoteServer, url: String): Long =
        when (server.protocol) {
            RemoteProtocol.SMB -> SmbSource.probeFile(server, url)
            RemoteProtocol.WEBDAV -> WebDavSource.probeFile(server, url)
        }

    /**
     * 整文件读取（图片 / 文本查看）。
     * @param truncate 为 true 时超过 [maxBytes] 只取前 [maxBytes] 字节，否则报错
     */
    @Throws(IOException::class)
    fun readFile(
        server: RemoteServer,
        url: String,
        maxBytes: Long,
        truncate: Boolean = false,
    ): ByteArray = when (server.protocol) {
        RemoteProtocol.SMB -> SmbSource.readFile(server, url, maxBytes, truncate)
        RemoteProtocol.WEBDAV -> WebDavSource.readFile(server, url, maxBytes, truncate)
    }

    /** 丢弃某文件占用的连接/句柄（查看器退出、播放失败重试时调用） */
    fun invalidate(server: RemoteServer, url: String) {
        when (server.protocol) {
            RemoteProtocol.SMB -> SmbSource.invalidate(url)
            RemoteProtocol.WEBDAV -> Unit // HTTP 无长连接句柄可释放
        }
    }

    /** 释放全部底层连接（退出播放时调用） */
    fun releaseAll() {
        SmbSource.releaseFiles()
    }

    // ---------------- 路径计算 ----------------

    /** 是否已经在这个服务器的根目录 */
    fun isRoot(server: RemoteServer, url: String): Boolean =
        normalize(url) == normalize(rootUrl(server))

    /** 上一级目录；已在根目录时返回 null（调用方据此退出浏览） */
    fun parentOf(server: RemoteServer, url: String): String? {
        val root = normalize(rootUrl(server))
        val current = url.substringBefore('?').trimEnd('/')
        if (normalize(current) == root) return null
        val idx = current.lastIndexOf('/')
        if (idx <= 0) return null
        val parent = current.substring(0, idx)
        if (!normalize(parent).startsWith(root)) return null
        return "$parent/"
    }

    /** 面包屑/副标题用：去掉协议与主机，只留路径 */
    fun prettyPath(server: RemoteServer, url: String): String {
        val raw = when (server.protocol) {
            RemoteProtocol.SMB -> {
                val host = server.host
                url.removePrefix("smb://$host").removePrefix("smb://")
            }

            RemoteProtocol.WEBDAV -> {
                val authority = "${if (server.secure) "https" else "http"}://${server.host}"
                url.removePrefix(authority).let { rest ->
                    // 去掉查询串（个别服务端会带 ETag 参数）
                    rest.substringBefore('?')
                }.ifBlank { "/" }
            }
        }
        return decodePath(raw).ifBlank { "/" }
    }

    /** 目录项展示名：取最后一段并做百分号解码 */
    fun nameOf(url: String): String {
        val path = url.substringBefore('?').trimEnd('/')
        val segment = path.substringAfterLast('/')
        return decodePath(segment).ifBlank { "/" }
    }

    private fun decodePath(value: String): String = runCatching {
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    }.getOrDefault(value)

    /** 规范化：去掉末尾 `/`、查询串，并把 %XX 解码，便于比较 */
    internal fun normalize(url: String): String =
        decodePath(url.substringBefore('?').trimEnd('/'))
}
