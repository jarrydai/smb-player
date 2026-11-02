package com.dyx.smbplayer.data

import com.dyx.smbplayer.data.model.RemoteServer
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Locale
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * 极简 HTTP/1.1 客户端，专供 WebDAV 使用。
 *
 * 为什么不用 HttpURLConnection：Android 的实现（OkHttp 后端）只允许
 * GET/POST/HEAD/OPTIONS/PUT/DELETE/PATCH 等固定方法，`PROPFIND` 会直接抛
 * ProtocolException。离线构建环境又无法引入第三方 HTTP 库，
 * 因此这里直接用 Socket 发请求，顺带把 Range / Basic 认证 / 自签名证书都控制在自己手里。
 *
 * 只实现 WebDAV 需要的部分：请求行 + 头 + 定长/分块响应体，Connection: close。
 */
internal object WebDavHttp {

    private const val USER_AGENT = "NetPlayer/1.4 (Android)"

    /** 默认连接 / 读取超时 */
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000

    /**
     * 发起请求。
     *
     * @param timeoutMs 读取超时（播放取流时放宽）
     * @param redirectsLeft 允许跟随的重定向次数
     */
    @Throws(IOException::class)
    fun execute(
        server: RemoteServer,
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        timeoutMs: Int = READ_TIMEOUT_MS,
        redirectsLeft: Int = 3,
    ): DavResponse {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            throw IOException("地址不合法：$url", e)
        }
        val scheme = (uri.scheme ?: "http").lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") throw IOException("不支持的协议：$scheme")
        val host = uri.host ?: throw IOException("地址缺少主机名：$url")
        val port = if (uri.port > 0) uri.port else if (scheme == "https") 443 else 80
        val target = buildString {
            append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
            uri.rawQuery?.let { append('?').append(it) }
        }

        val socket = connect(server, scheme, host, port, timeoutMs)
        try {
            val out = socket.getOutputStream()
            val sb = StringBuilder()
            sb.append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
            sb.append("Host: ").append(hostHeader(host, port, scheme)).append("\r\n")
            sb.append("User-Agent: ").append(USER_AGENT).append("\r\n")
            sb.append("Accept: */*\r\n")
            sb.append("Connection: close\r\n")
            if (!server.anonymous && server.username.isNotBlank()) {
                val token = Base64.getEncoder()
                    .encodeToString("${server.username}:${server.password}".toByteArray())
                sb.append("Authorization: Basic ").append(token).append("\r\n")
            }
            headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
            if (body != null) {
                sb.append("Content-Length: ").append(body.size).append("\r\n")
            } else if (method == "PROPFIND") {
                sb.append("Content-Length: 0\r\n")
            }
            sb.append("\r\n")
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            if (body != null) out.write(body)
            out.flush()

            val input = BufferedInputStream(socket.getInputStream(), 16 * 1024)
            val statusLine = readAsciiLine(input) ?: throw IOException("服务器没有返回任何数据")
            val parts = statusLine.split(' ', limit = 3)
            if (parts.size < 2 || !parts[0].startsWith("HTTP/")) {
                throw IOException("服务器返回了无法识别的响应：$statusLine")
            }
            val code = parts[1].toIntOrNull() ?: throw IOException("解析状态码失败：$statusLine")
            val message = parts.getOrElse(2) { "" }

            val responseHeaders = readHeaders(input)
            val bodyStream = when {
                responseHeaders["transfer-encoding"]?.contains("chunked", true) == true ->
                    ChunkedInputStream(input)

                responseHeaders["content-length"]?.toLongOrNull() != null ->
                    FixedLengthInputStream(input, responseHeaders["content-length"]!!.toLong())

                else -> input // 响应体到连接关闭为止
            }
            val response = DavResponse(code, message, responseHeaders, bodyStream, socket)

            // 跟随重定向（WebDAV 常见：http -> https、缺尾斜杠被 301 到带斜杠地址）
            if (code in REDIRECT_CODES && redirectsLeft > 0) {
                val location = responseHeaders["location"]
                if (!location.isNullOrBlank()) {
                    val next = runCatching { java.net.URL(java.net.URL(url), location).toString() }
                        .getOrNull()
                    response.close()
                    if (next != null) {
                        return execute(server, method, next, headers, body, timeoutMs, redirectsLeft - 1)
                    }
                }
            }
            return response
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw (e as? IOException ?: IOException("请求失败：${e.message}", e))
        }
    }

    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

    private fun hostHeader(host: String, port: Int, scheme: String): String {
        val defaultPort = (scheme == "https" && port == 443) || (scheme == "http" && port == 80)
        return if (defaultPort) host else "$host:$port"
    }

    // ---------------- 连接（含 TLS） ----------------

    private fun connect(
        server: RemoteServer,
        scheme: String,
        host: String,
        port: Int,
        timeoutMs: Int,
    ): Socket {
        val raw = Socket()
        raw.tcpNoDelay = true
        raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        raw.soTimeout = maxOf(timeoutMs, 5_000)
        if (scheme != "https") return raw

        return try {
            val context = SSLContext.getInstance("TLS")
            context.init(null, if (server.insecureTls) arrayOf(TRUST_ALL) else null, null)
            val factory = context.socketFactory as SSLSocketFactory
            val ssl = factory.createSocket(raw, host, port, true) as SSLSocket
            if (!server.insecureTls) {
                // 开启主机名校验（自签名模式才跳过）
                val params = ssl.sslParameters
                params.endpointIdentificationAlgorithm = "HTTPS"
                ssl.sslParameters = params
            }
            ssl.soTimeout = maxOf(timeoutMs, 5_000)
            ssl.startHandshake()
            ssl
        } catch (e: Exception) {
            runCatching { raw.close() }
            if (server.insecureTls) {
                throw IOException("HTTPS 连接失败：${e.message}", e)
            }
            throw IOException(
                "HTTPS 连接失败（${e.message}）。若 NAS 使用自签名证书，请打开「允许自签名证书」。",
                e,
            )
        }
    }

    /** 接受任意证书：仅用于用户显式勾选"允许自签名证书"的局域网设备 */
    private val TRUST_ALL = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    // ---------------- 报文解析 ----------------

    @Throws(IOException::class)
    private fun readHeaders(input: InputStream): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readAsciiLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val name = line.substring(0, idx).trim().lowercase(Locale.US)
            val value = line.substring(idx + 1).trim()
            val old = headers[name]
            headers[name] = if (old == null) value else "$old,$value"
        }
        return headers
    }

    private class FixedLengthInputStream(
        private val source: InputStream,
        length: Long,
    ) : FilterInputStream(source) {
        private var remaining = length
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = source.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = source.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }

        override fun available(): Int = minOf(remaining, source.available().toLong()).toInt()
    }

    private class ChunkedInputStream(private val source: InputStream) : FilterInputStream(source) {
        private var chunkRemaining = 0L
        private var eof = false

        private fun ensureChunk(): Boolean {
            if (eof) return false
            while (chunkRemaining == 0L) {
                val line = readAsciiLine(source)
                if (line == null) {
                    eof = true
                    return false
                }
                if (line.isEmpty()) continue // 上一块的结尾 CRLF
                val size = line.substringBefore(';').trim().toLongOrNull(16)
                if (size == null || size == 0L) {
                    eof = true
                    return false
                }
                chunkRemaining = size
            }
            return true
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!ensureChunk()) return -1
            val n = source.read(b, off, minOf(len.toLong(), chunkRemaining).toInt())
            if (n > 0) chunkRemaining -= n
            return n
        }
    }
}

/** 读取一行 ASCII（CRLF 结尾），连接结束且无内容时返回 null */
@Throws(IOException::class)
private fun readAsciiLine(input: InputStream): String? {
    val buf = ByteArrayOutputStream(128)
    while (true) {
        val b = input.read()
        if (b == -1) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
        if (b == '\n'.code) break
        if (b != '\r'.code) buf.write(b)
        if (buf.size() > 16 * 1024) throw IOException("响应头过长")
    }
    return buf.toString("ISO-8859-1")
}

/** 一次 HTTP 响应。调用方负责 [close]。 */
internal class DavResponse(
    val code: Int,
    val message: String,
    private val headers: Map<String, String>,
    val body: InputStream,
    private val socket: Socket,
) : Closeable {

    fun header(name: String): String? = headers[name.lowercase(Locale.US)]

    /** 读取整个响应体（最多 limit 字节） */
    @Throws(IOException::class)
    fun readBytes(limit: Long = 8L * 1024 * 1024): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (total < limit) {
            val n = body.read(buf, 0, minOf(buf.size.toLong(), limit - total).toInt())
            if (n <= 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    /** 服务端返回的友好错误描述 */
    fun errorMessage(): String = when (code) {
        401 -> "认证失败（401），请检查用户名和密码"
        403 -> "没有访问权限（403）"
        404 -> "路径不存在（404），请检查服务器地址和路径"
        405 -> "服务器不支持该方法（405），可能不是 WebDAV 服务"
        407 -> "需要代理认证（407）"
        in 500..599 -> "服务器内部错误（$code $message）"
        else -> "HTTP $code $message"
    }

    override fun close() {
        runCatching { body.close() }
        runCatching { socket.close() }
    }
}
