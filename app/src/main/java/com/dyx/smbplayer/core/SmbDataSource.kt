package com.dyx.smbplayer.core

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.dyx.smbplayer.data.SmbSource
import jcifs.CIFSContext
import java.io.IOException
import java.util.Locale
import kotlin.math.min

/**
 * 把 jcifs-ng 的 SMB 随机访问流适配为 ExoPlayer 数据源。
 *
 * 两个关键优化（针对大文件和拖动）：
 * 1. **句柄复用**：文件按 URL 缓存（见 [SmbSource.openFile]），`close()` 不真正关文件，
 *    拖动进度条时 open 只是本地 seek，省掉每次重连/打开文件的往返。
 * 2. **1MB 预读**：ExoPlayer 每次只读 32~64KB，直接透传就要一次 SMB 往返搬运这点数据，
 *    5GB+ 高码率文件（4K remux 常见 60~80Mbps）很容易供不上。这里一次请求预读 1MB，
 *    顺序播放的请求数降到 1/16~1/32，吞吐显著提升。
 *
 * 另外读取失败会自动重建句柄重试一次，避免长时间播放中连接被 NAS 回收导致直接报错。
 */
@OptIn(UnstableApi::class)
class SmbDataSource(private val cifsContext: CIFSContext) : BaseDataSource(true) {

    companion object {
        private const val TAG = "SmbPlayer"

        /** 单次预读块大小 */
        private const val PREFETCH_SIZE = 1024 * 1024
    }

    private var uriString: String? = null
    private var cachedFile: SmbSource.CachedFile? = null
    private var bytesRemaining: Long = 0
    private var opened = false

    // ---- 预读缓冲 ----
    private var buffer: ByteArray? = null
    private var bufLen = 0
    private var bufPos = 0

    /** 逻辑文件位置：下一次预读的起始偏移（Long，支持 >4GB 文件） */
    private var filePos = 0L

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val url = dataSpec.uri.toString()
        uriString = url
        try {
            val wasCached = SmbSource.isCached(url)
            val file = SmbSource.openFile(url, cifsContext)
            cachedFile = file
            val position = dataSpec.position.coerceAtLeast(0L)
            filePos = position
            bufLen = 0
            bufPos = 0
            if (buffer == null) buffer = ByteArray(PREFETCH_SIZE)
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                dataSpec.length
            } else {
                (file.length - position).coerceAtLeast(0L)
            }
            Log.d(
                TAG,
                "SMB open ok: pos=$position len=$bytesRemaining fileLen=${file.length} " +
                    "(${humanSize(file.length)}) handle=${if (wasCached) "复用" else "新建"} url=$url",
            )
        } catch (e: Exception) {
            cachedFile = null
            Log.e(TAG, "SMB open failed: $url", e)
            throw IOException("SMB 打开失败：$url", e)
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        if (bufPos >= bufLen && !fillBuffer()) return C.RESULT_END_OF_INPUT
        val buf = buffer ?: return C.RESULT_END_OF_INPUT
        val n = min(length, bufLen - bufPos)
        System.arraycopy(buf, bufPos, target, offset, n)
        bufPos += n
        filePos += n
        bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    /**
     * 从 [filePos] 预读一块到缓冲区；失败时重建句柄重试一次。
     * @return 是否拿到了数据（false 表示已到文件末尾或无法读取）
     */
    private fun fillBuffer(retry: Boolean = true): Boolean {
        val want = min(PREFETCH_SIZE.toLong(), bytesRemaining).toInt()
        if (want <= 0) return false
        val file = cachedFile ?: return false
        val buf = buffer ?: return false
        var got = 0
        try {
            synchronized(file) {
                if (file.closed) throw IOException("句柄已关闭")
                file.raf.seek(filePos)
                while (got < want) {
                    val r = file.raf.read(buf, got, want - got)
                    if (r <= 0) break
                    got += r
                }
            }
        } catch (e: Exception) {
            if (got == 0 && retry) {
                // 连接可能被服务端回收：重建句柄后重试一次
                val url = uriString
                if (url != null) {
                    Log.w(TAG, "SMB 预读失败，重建连接重试 @$filePos: ${e.message}")
                    SmbSource.invalidate(url)
                    val reopened = try {
                        SmbSource.openFile(url, cifsContext)
                    } catch (e2: Exception) {
                        Log.e(TAG, "SMB 重建句柄失败", e2)
                        null
                    }
                    if (reopened != null) {
                        cachedFile = reopened
                        return fillBuffer(retry = false)
                    }
                }
            }
            if (got == 0) {
                Log.e(TAG, "SMB 预读失败 @$filePos ($uriString)", e)
                throw IOException("SMB 读取失败", e)
            }
            Log.w(TAG, "SMB 预读中断，先用已读到的 $got 字节续播", e)
        }
        bufPos = 0
        bufLen = got
        return got > 0
    }

    override fun getUri(): Uri? = uriString?.let(Uri::parse)

    /**
     * 注意：这里**不关闭**底层文件句柄，留给下次 open（拖动进度条）复用。
     * 句柄由 PlayerViewModel 退出播放时统一调用 SmbSource.releaseFiles() 释放。
     */
    override fun close() {
        bufLen = 0
        bufPos = 0
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }
}
