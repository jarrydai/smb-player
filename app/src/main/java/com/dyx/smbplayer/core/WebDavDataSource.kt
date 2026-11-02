package com.dyx.smbplayer.core

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.dyx.smbplayer.data.DavResponse
import com.dyx.smbplayer.data.WebDavHttp
import com.dyx.smbplayer.data.model.RemoteServer
import java.io.IOException
import java.io.InputStream
import kotlin.math.min

/**
 * WebDAV 播放数据源：用 HTTP Range 请求按需取流，适配 ExoPlayer 的 seek。
 *
 * 思路与 [SmbDataSource] 一致，只是底层换成 HTTP：
 * - open 带 `Range: bytes=start-`，一次请求就能从任意位置开始读，拖动进度条不需要重下；
 * - 服务端忽略 Range（返回 200）时，把 start 之前的字节读掉丢弃，保证数据对齐；
 * - 读取中断（Wi-Fi 抖动 / NAS 主动断流）时按当前位置重连一次再续播。
 */
@OptIn(UnstableApi::class)
class WebDavDataSource(private val server: RemoteServer) : BaseDataSource(true) {

    companion object {
        private const val TAG = "NetPlayer"
        private const val READ_TIMEOUT_MS = 60_000
    }

    private var uriString: String? = null
    private var response: DavResponse? = null
    private var bytesRemaining = 0L
    private var position = 0L
    private var opened = false

    /** 每次 open 只允许重连一次，避免连接反复异常时死循环 */
    private var retried = false

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val url = dataSpec.uri.toString()
        uriString = url
        val start = dataSpec.position.coerceAtLeast(0L)
        val total = try {
            openStream(url, start)
        } catch (e: Exception) {
            // 一次重连重试：刚建立的连接也可能被服务端立刻关闭
            Log.w(TAG, "WebDAV open 失败，重试一次 @$start: ${e.message}")
            closeQuietly()
            try {
                openStream(url, start)
            } catch (e2: Exception) {
                closeQuietly()
                Log.e(TAG, "WebDAV open 失败: $url", e2)
                throw (e2 as? IOException ?: IOException("WebDAV 打开失败：${e2.message}", e2))
            }
        }
        position = start
        retried = false
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else if (total > 0) {
            (total - start).coerceAtLeast(0L)
        } else {
            Long.MAX_VALUE
        }
        opened = true
        transferStarted(dataSpec)
        Log.d(TAG, "WebDAV open ok: pos=$start len=$bytesRemaining total=$total url=$url")
        return bytesRemaining
    }

    /** 发起 Range 请求，返回文件总长度（未知返回 -1） */
    @Throws(IOException::class)
    private fun openStream(url: String, start: Long): Long {
        val resp = WebDavHttp.execute(
            server = server,
            method = "GET",
            url = url,
            headers = mapOf("Range" to "bytes=$start-"),
            timeoutMs = READ_TIMEOUT_MS,
        )
        if (resp.code == 416) {
            resp.close()
            throw IOException("请求范围无效（416），文件可能已被修改")
        }
        if (resp.code !in 200..299) {
            val message = resp.errorMessage()
            resp.close()
            throw IOException(message)
        }
        response = resp
        return when (resp.code) {
            206 -> resp.header("content-range")?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: -1L
            else -> {
                // 200：服务端忽略了 Range，需要先跳过 start 之前的字节
                if (start > 0) skipFully(resp.body, start)
                val len = resp.header("content-length")?.trim()?.toLongOrNull()
                if (len != null) len + start else -1L
            }
        }
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val resp = response ?: return C.RESULT_END_OF_INPUT
        val want = min(length.toLong(), bytesRemaining).toInt()
        val n = try {
            resp.body.read(target, offset, want)
        } catch (e: IOException) {
            if (!reopen()) {
                Log.e(TAG, "WebDAV 读取失败 @$position ($uriString)", e)
                throw IOException("WebDAV 读取失败：${e.message}", e)
            }
            return read(target, offset, length)
        }
        if (n < 0) return C.RESULT_END_OF_INPUT
        position += n
        if (bytesRemaining != Long.MAX_VALUE) bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    /** 从当前位置重连续读（只尝试一次，避免死循环） */
    private fun reopen(): Boolean {
        val url = uriString ?: return false
        if (retried) return false
        retried = true
        val retryPos = position
        closeQuietly()
        return try {
            Log.w(TAG, "WebDAV 重连续播 @$retryPos")
            openStream(url, retryPos)
            true
        } catch (e: Exception) {
            Log.e(TAG, "WebDAV 重连失败 @$retryPos", e)
            false
        }
    }

    @Throws(IOException::class)
    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        val buf = ByteArray(64 * 1024)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n <= 0) throw IOException("跳过前 ${count - left} 字节后连接中断")
            left -= n
        }
    }

    override fun getUri(): Uri? = uriString?.let(Uri::parse)

    override fun close() {
        closeQuietly()
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private fun closeQuietly() {
        val resp = response ?: return
        response = null
        runCatching { resp.close() }
    }
}
