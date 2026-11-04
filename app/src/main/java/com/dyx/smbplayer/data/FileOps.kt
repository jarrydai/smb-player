package com.dyx.smbplayer.data

import com.dyx.smbplayer.data.model.RemoteEntry
import com.dyx.smbplayer.data.model.RemoteProtocol
import com.dyx.smbplayer.data.model.RemoteServer
import java.io.IOException

/** 批量操作类型 */
enum class FileOpMode { COPY, MOVE, DELETE }

data class OpFailure(val name: String, val reason: String)

/** 批量操作结果：成功的条数 + 每条失败的原因 */
data class OpResult(val succeeded: Int, val failures: List<OpFailure>) {
    val allSucceeded: Boolean get() = failures.isEmpty()
}

/**
 * 共享文件批量操作的统一入口（SMB / WebDAV）。
 *
 * 三条原则：
 * 1. **每项独立执行**，单个失败不影响其余项，最后把原因汇总给 UI；
 * 2. **绝不覆盖**：目标目录已有同名条目时自动改成「xxx (1)」这类名字；
 * 3. **不自我嵌套**：禁止把文件夹复制/移动到它自己内部，否则 SMB 递归会失控。
 */
object FileOps {

    /** 目标选择器用：只列子目录 */
    @Throws(IOException::class)
    fun listDirs(server: RemoteServer, dirUrl: String): List<RemoteEntry> =
        RemoteFiles.listDir(server, dirUrl).filter { it.isDir }

    /** WebDAV 目标地址：目录补尾斜杠，中文/空格做百分号编码 */
    private fun childUrl(parentDir: String, name: String, isDir: Boolean): String {
        val encoded = WebDavSource.encodePath(name.trimEnd('/'))
        return "${parentDir.trimEnd('/')}/$encoded" + if (isDir) "/" else ""
    }

    fun exists(server: RemoteServer, parentDir: String, name: String): Boolean =
        when (server.protocol) {
            RemoteProtocol.SMB -> SmbSource.exists(server, parentDir, name)
            RemoteProtocol.WEBDAV -> WebDavSource.exists(server, childUrl(parentDir, name, false))
        }

    /** 生成一个不与现有条目冲突的名字 */
    @Throws(IOException::class)
    private fun uniqueName(
        server: RemoteServer,
        parentDir: String,
        original: String,
        isDir: Boolean,
    ): String {
        if (!exists(server, parentDir, original)) return original
        val dot = if (isDir) -1 else original.lastIndexOf('.').let { if (it > 0) it else -1 }
        val stem = if (dot > 0) original.substring(0, dot) else original
        val ext = if (dot > 0) original.substring(dot) else ""
        for (i in 1..99) {
            val candidate = "$stem ($i)$ext"
            if (!exists(server, parentDir, candidate)) return candidate
        }
        throw IOException("同名文件太多，无法自动生成不冲突的名字")
    }

    private fun insideSelf(item: RemoteEntry, destDir: String): Boolean {
        if (!item.isDir) return false
        val self = item.url.trimEnd('/')
        val dest = destDir.trimEnd('/')
        return dest == self || dest.startsWith("$self/")
    }

    private fun isSameDir(server: RemoteServer, item: RemoteEntry, destDir: String): Boolean {
        val parent = RemoteFiles.parentOf(server, item.url) ?: return false
        return RemoteFiles.normalize(parent) == RemoteFiles.normalize(destDir)
    }

    private fun reason(e: Exception): String {
        val raw = e.message ?: e.cause?.message ?: e.javaClass.simpleName
        return raw.replace(Regex("\\s+"), " ").take(120).ifBlank { "未知错误" }
    }

    @Throws(IOException::class)
    fun delete(
        server: RemoteServer,
        items: List<RemoteEntry>,
        onProgress: (done: Int, total: Int, current: String) -> Unit = { _, _, _ -> },
    ): OpResult {
        val failures = mutableListOf<OpFailure>()
        var ok = 0
        items.forEachIndexed { index, item ->
            onProgress(index, items.size, item.displayName)
            try {
                when (server.protocol) {
                    RemoteProtocol.SMB -> SmbSource.delete(server, item.url)
                    RemoteProtocol.WEBDAV -> WebDavSource.delete(server, item.url)
                }
                ok++
            } catch (e: Exception) {
                failures += OpFailure(item.displayName, reason(e))
            }
        }
        onProgress(items.size, items.size, "")
        return OpResult(ok, failures)
    }

    fun copy(
        server: RemoteServer,
        items: List<RemoteEntry>,
        destDir: String,
        onProgress: (done: Int, total: Int, current: String) -> Unit = { _, _, _ -> },
    ): OpResult = transfer(server, FileOpMode.COPY, items, destDir, onProgress)

    fun move(
        server: RemoteServer,
        items: List<RemoteEntry>,
        destDir: String,
        onProgress: (done: Int, total: Int, current: String) -> Unit = { _, _, _ -> },
    ): OpResult = transfer(server, FileOpMode.MOVE, items, destDir, onProgress)

    private fun transfer(
        server: RemoteServer,
        mode: FileOpMode,
        items: List<RemoteEntry>,
        destDir: String,
        onProgress: (done: Int, total: Int, current: String) -> Unit,
    ): OpResult {
        val failures = mutableListOf<OpFailure>()
        var ok = 0
        items.forEachIndexed { index, item ->
            onProgress(index, items.size, item.displayName)
            try {
                when {
                    insideSelf(item, destDir) ->
                        throw IOException("不能把文件夹放进它自己里面")

                    // 目标就是原目录：移动没有意义，复制也不该悄悄造出一份副本
                    isSameDir(server, item, destDir) && mode == FileOpMode.MOVE -> {
                        ok++
                        return@forEachIndexed
                    }
                }
                val name = uniqueName(server, destDir, item.displayName, item.isDir)
                when (server.protocol) {
                    RemoteProtocol.SMB -> if (mode == FileOpMode.COPY) {
                        SmbSource.copy(server, item.url, destDir, name)
                    } else {
                        SmbSource.move(server, item.url, destDir, name)
                    }

                    RemoteProtocol.WEBDAV -> {
                        val dest = childUrl(destDir, name, item.isDir)
                        if (mode == FileOpMode.COPY) {
                            WebDavSource.copy(server, item.url, dest)
                        } else {
                            WebDavSource.move(server, item.url, dest)
                        }
                    }
                }
                ok++
            } catch (e: Exception) {
                failures += OpFailure(item.displayName, reason(e))
            }
        }
        onProgress(items.size, items.size, "")
        return OpResult(ok, failures)
    }

    /** 重命名：同目录内移动，遇到同名直接报错（不自动改名） */
    @Throws(IOException::class)
    fun rename(server: RemoteServer, item: RemoteEntry, parentDir: String, newName: String) {
        val name = newName.trim()
        if (name.isEmpty() || name.contains('/') || name == "." || name == "..") {
            throw IOException("名称不合法")
        }
        if (exists(server, parentDir, name)) throw IOException("已存在同名条目：$name")
        when (server.protocol) {
            RemoteProtocol.SMB -> SmbSource.move(server, item.url, parentDir, name)
            RemoteProtocol.WEBDAV -> WebDavSource.move(
                server, item.url, childUrl(parentDir, name, item.isDir),
            )
        }
    }

    /** 新建文件夹，返回新目录地址 */
    @Throws(IOException::class)
    fun mkdir(server: RemoteServer, parentDir: String, name: String): String {
        val trimmed = name.trim().trim('/')
        if (trimmed.isEmpty() || trimmed.contains('/') || trimmed == "." || trimmed == "..") {
            throw IOException("名称不合法")
        }
        if (exists(server, parentDir, trimmed)) throw IOException("已存在同名条目：$trimmed")
        return when (server.protocol) {
            RemoteProtocol.SMB -> SmbSource.mkdir(server, parentDir, trimmed)
            RemoteProtocol.WEBDAV -> {
                val url = childUrl(parentDir, trimmed, true)
                WebDavSource.mkdir(server, url)
                url
            }
        }
    }
}
