package com.dyx.smbplayer.data

import android.util.Xml
import com.dyx.smbplayer.data.model.FileKind
import com.dyx.smbplayer.data.model.RemoteEntry
import com.dyx.smbplayer.data.model.RemoteServer
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URL

/**
 * WebDAV（RFC 4918）客户端：PROPFIND 列目录、HEAD/Range 探测、GET 取内容。
 *
 * 服务端类型覆盖群晖/威联通/Nextcloud/Apache mod_dav/坚果云等，
 * 解析时忽略命名空间前缀（部分设备返回 `D:`、部分返回默认命名空间）。
 */
object WebDavSource {

    private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:resourcetype/>
    <d:getcontentlength/>
    <d:getcontenttype/>
    <d:displayname/>
  </d:prop>
</d:propfind>"""

    // ---------------- 地址 ----------------

    /** 服务器根地址：http(s)://host[:port]/basePath/ */
    fun rootUrl(server: RemoteServer): String {
        val scheme = if (server.secure) "https" else "http"
        val path = server.webdavPath
        return "$scheme://${server.host}${encodePath(if (path.isEmpty()) "/" else "$path/")}"
    }

    /** 把用户输入的路径编码为合法 URL（保留 `/`，编码中文与空格） */
    internal fun encodePath(path: String): String {
        val sb = StringBuilder()
        path.forEach { ch ->
            when {
                ch.isLetterOrDigit() && ch.code < 128 -> sb.append(ch)
                ch in "-._~/:@!$&'()*+,;=" -> sb.append(ch)
                ch == '%' -> sb.append(ch)
                else -> {
                    val bytes = ch.toString().toByteArray(Charsets.UTF_8)
                    bytes.forEach { b -> sb.append('%').append("%02X".format(b)) }
                }
            }
        }
        return sb.toString()
    }

    // ---------------- 列目录 ----------------

    @Throws(IOException::class)
    fun listDir(server: RemoteServer, dirUrl: String): List<RemoteEntry> {
        val url = if (dirUrl.endsWith("/")) dirUrl else "$dirUrl/"
        val body = PROPFIND_BODY.toByteArray(Charsets.UTF_8)
        WebDavHttp.execute(
            server = server,
            method = "PROPFIND",
            url = url,
            headers = mapOf(
                "Depth" to "1",
                "Content-Type" to "application/xml; charset=utf-8",
            ),
            body = body,
        ).use { resp ->
            if (resp.code != 207 && resp.code != 200) {
                throw IOException(resp.errorMessage())
            }
            val xml = resp.readBytes(16L * 1024 * 1024)
            val items = parseMultiStatus(xml, url)
            if (items.isEmpty()) {
                throw IOException("服务器没有返回目录内容，可能不是 WebDAV 路径")
            }
            return items
                .filter { RemoteFiles.normalize(it.url) != RemoteFiles.normalize(url) }
                .map { item ->
                    val entryUrl = if (item.isDir && !item.url.endsWith("/")) "${item.url}/" else item.url
                    val name = RemoteFiles.nameOf(entryUrl)
                    RemoteEntry(
                        name = name,
                        displayName = name,
                        isDir = item.isDir,
                        sizeBytes = item.size,
                        url = entryUrl,
                        kind = RemoteFiles.kindOf(name, item.isDir),
                    )
                }
                .filter { it.isDir || it.kind != FileKind.OTHER }
                .sortedWith(compareByDescending<RemoteEntry> { it.isDir }.thenBy { it.displayName.lowercase() })
        }
    }

    private class DavItem(val url: String, val isDir: Boolean, val size: Long)

    @Throws(IOException::class)
    private fun parseMultiStatus(xml: ByteArray, requestUrl: String): List<DavItem> {
        val items = mutableListOf<DavItem>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(xml), null)

        var href: String? = null
        var isDir = false
        var size = 0L
        var inResponse = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.substringAfterLast(':').lowercase()) {
                        "response" -> {
                            inResponse = true
                            href = null
                            isDir = false
                            size = 0L
                        }

                        "href" -> if (inResponse && href == null) href = parser.nextText().trim()
                        "collection" -> if (inResponse) isDir = true
                        "getcontentlength" -> if (inResponse) {
                            size = parser.nextText().trim().toLongOrNull() ?: 0L
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name.substringAfterLast(':').lowercase() == "response") {
                        val rawHref = href
                        if (!rawHref.isNullOrBlank()) {
                            val url = resolve(requestUrl, rawHref)
                            if (url != null) {
                                items.add(
                                    DavItem(
                                        url = url,
                                        isDir = isDir || rawHref.endsWith("/"),
                                        size = size,
                                    ),
                                )
                            }
                        }
                        inResponse = false
                    }
                }
            }
            event = parser.next()
        }
        return items
    }

    /** 把服务端返回的 href（相对或绝对）解析成绝对地址 */
    private fun resolve(requestUrl: String, href: String): String? = runCatching {
        if (href.startsWith("http://", true) || href.startsWith("https://", true)) {
            URL(encodeIfNeeded(href)).toString()
        } else {
            URL(URL(requestUrl), encodeIfNeeded(href)).toString()
        }
    }.getOrNull()

    /** href 里若含未编码的中文/空格，先编码；已编码的保持原样 */
    private fun encodeIfNeeded(value: String): String =
        if (value.none { it.code > 126 || it == ' ' }) value else encodePath(value)

    // ---------------- 连接测试 ----------------

    @Throws(IOException::class)
    fun testConnection(server: RemoteServer) {
        val url = rootUrl(server)
        val root = url.substringBeforeLast('/', "").ifEmpty { url }
        // 有些服务器不允许 PROPFIND 根路径，退一步用 PROPFIND 基础路径 + OPTIONS
        val candidates = listOf(url, root).distinct()
        var lastError: String? = null
        for (candidate in candidates) {
            val result = runCatching {
                WebDavHttp.execute(
                    server = server,
                    method = "PROPFIND",
                    url = candidate,
                    headers = mapOf("Depth" to "0", "Content-Type" to "application/xml; charset=utf-8"),
                    body = PROPFIND_BODY.toByteArray(Charsets.UTF_8),
                ).use { resp ->
                    when (resp.code) {
                        207, 200 -> return@use null
                        401 -> "认证失败（401），请检查用户名和密码"
                        403 -> "没有访问权限（403）"
                        404 -> "路径不存在（404），请检查地址和路径"
                        405 -> {
                            // 不支持 PROPFIND：至少确认服务器在
                            WebDavHttp.execute(server, "OPTIONS", candidate).use { opt ->
                                when (opt.code) {
                                    in 200..399 -> null
                                    else -> opt.errorMessage()
                                }
                            }
                        }

                        else -> resp.errorMessage()
                    }
                }
            }.getOrElse { it.message ?: "连接失败" }
            if (result == null) return
            lastError = result
        }
        throw IOException(lastError ?: "无法连接到 WebDAV 服务器")
    }

    // ---------------- 文件探测 / 读取 ----------------

    /** 返回文件总字节数；失败抛中文原因 */
    @Throws(IOException::class)
    fun probeFile(server: RemoteServer, url: String): Long {
        var hardError: String? = null

        // 1) HEAD 最省流量
        WebDavHttp.execute(server, "HEAD", url).use { resp ->
            when {
                resp.code in 200..299 -> {
                    val len = resp.header("content-length")?.trim()?.toLongOrNull() ?: 0L
                    if (len > 0) return len
                }
                // 认证/权限/路径错误直接失败，其他状态码（如 405）继续尝试 Range 探测
                resp.code == 401 || resp.code == 403 || resp.code == 404 -> hardError = resp.errorMessage()
            }
        }
        hardError?.let { throw IOException(it) }

        // 2) HEAD 没有长度（部分服务器对 HEAD 支持不完整）：用 Range: bytes=0-0 拿 Content-Range
        WebDavHttp.execute(server, "GET", url, mapOf("Range" to "bytes=0-0")).use { resp ->
            if (resp.code !in 200..299) throw IOException(resp.errorMessage())
            parseTotal(resp.header("content-range"))?.let { if (it > 0) return it }
            val len = resp.header("content-length")?.toLongOrNull() ?: 0L
            if (len > 0 && resp.code == 200) return len
        }
        throw IOException("文件大小为 0 或服务器未返回内容长度")
    }

    /** `bytes 0-0/12345` -> 12345（`*` 表示未知） */
    private fun parseTotal(contentRange: String?): Long? {
        val total = contentRange?.substringAfterLast('/', "")?.trim()
        return total?.toLongOrNull()
    }

    @Throws(IOException::class)
    fun readFile(
        server: RemoteServer,
        url: String,
        maxBytes: Long,
        truncate: Boolean,
    ): ByteArray {
        val size = probeFile(server, url)
        if (size > maxBytes && !truncate) {
            throw IOException("文件过大（${"%.1f".format(size / 1024.0 / 1024.0)} MB），超过查看上限")
        }
        val limit = if (truncate) maxBytes else size
        val headers = if (truncate) mapOf("Range" to "bytes=0-${maxBytes - 1}") else emptyMap()
        // 部分网络环境（代理/劣质 Wi-Fi）在连接关闭时会丢掉尾部几个字节：
        // 完整性校验失败时自动重取（最多 3 次），仍不完整才报错
        var lastShort: IOException? = null
        repeat(3) {
            try {
                WebDavHttp.execute(server, "GET", url, headers, timeoutMs = 60_000).use { resp ->
                    if (resp.code !in 200..299) throw IOException(resp.errorMessage())
                    val bytes = resp.readBytes(limit)
                    if (bytes.isEmpty()) throw IOException("服务器返回了空内容")
                    if (!truncate && bytes.size < size) {
                        throw IOException(
                            "内容不完整（${bytes.size}/${size} 字节），网络中断",
                        )
                    }
                    return bytes
                }
            } catch (e: IOException) {
                lastShort = e
            }
        }
        throw (lastShort ?: IOException("读取失败"))
    }

    // ---------------- 写操作（复制 / 移动 / 删除 / 新建文件夹） ----------------

    /** 2xx 才算成功；把 WebDAV 特有的错误码翻译成能看懂的原因 */
    private fun ensureOk(method: String, resp: DavResponse) {
        if (resp.code in 200..299) return
        val hint = when (resp.code) {
            403 -> "（没有写入权限，或服务器禁止该操作）"
            405 -> "（服务器不支持 $method，可能未开启 WebDAV 写入）"
            409 -> "（目标的上级目录不存在）"
            412 -> "（目标已存在，已跳过以避免覆盖）"
            423 -> "（文件被锁定）"
            507 -> "（服务器存储空间不足）"
            else -> ""
        }
        throw IOException(resp.errorMessage() + hint)
    }

    /** 删除文件或整个目录（Depth: infinity 让服务端自己递归） */
    @Throws(IOException::class)
    fun delete(server: RemoteServer, url: String) {
        WebDavHttp.execute(server, "DELETE", url, mapOf("Depth" to "infinity")).use {
            ensureOk("DELETE", it)
        }
    }

    /** 复制到已编码好的绝对目标地址；Overwrite: F 保证不会覆盖同名文件 */
    @Throws(IOException::class)
    fun copy(server: RemoteServer, srcUrl: String, destUrl: String) {
        transfer(server, "COPY", srcUrl, destUrl)
    }

    @Throws(IOException::class)
    fun move(server: RemoteServer, srcUrl: String, destUrl: String) {
        transfer(server, "MOVE", srcUrl, destUrl)
    }

    private fun transfer(server: RemoteServer, method: String, srcUrl: String, destUrl: String) {
        WebDavHttp.execute(
            server = server,
            method = method,
            url = srcUrl,
            headers = mapOf(
                "Destination" to destUrl,
                "Overwrite" to "F",
                "Depth" to "infinity",
            ),
            timeoutMs = 120_000, // 大目录的服务端复制可能较慢
        ).use { ensureOk(method, it) }
    }

    /** 新建目录；MKCOL 要求请求路径以 / 结尾 */
    @Throws(IOException::class)
    fun mkdir(server: RemoteServer, dirUrl: String) {
        WebDavHttp.execute(server, "MKCOL", dirUrl.trimEnd('/') + "/").use {
            ensureOk("MKCOL", it)
        }
    }

    /**
     * 探测某个地址是否已存在（用于避免覆盖）。
     * 无法判定时保守返回 true —— 宁可自动改名，也不能覆盖用户文件。
     */
    fun exists(server: RemoteServer, url: String): Boolean = runCatching {
        WebDavHttp.execute(
            server = server,
            method = "PROPFIND",
            url = url,
            headers = mapOf("Depth" to "0", "Content-Type" to "application/xml; charset=utf-8"),
            body = PROPFIND_BODY.toByteArray(Charsets.UTF_8),
        ).use { resp ->
            when (resp.code) {
                404, 410 -> false
                else -> true
            }
        }
    }.getOrDefault(true)
}
