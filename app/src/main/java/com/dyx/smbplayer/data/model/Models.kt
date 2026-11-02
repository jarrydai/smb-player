package com.dyx.smbplayer.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 支持的远程协议。
 *
 * 播放器不再只面向 SMB：浏览/播放/查看都走同一套模型，
 * 具体协议差异由 [com.dyx.smbplayer.data.RemoteFiles] 分发。
 */
enum class RemoteProtocol(val label: String) {
    SMB("SMB"),
    WEBDAV("WebDAV"),
}

/**
 * 待播放的视频。
 *
 * @param path 完整远程地址：SMB 形如 smb://host/share/dir/a.mp4，
 *             WebDAV 形如 http(s)://host:port/dav/dir/a.mp4
 */
@Parcelize
data class VideoItem(
    val id: String,
    val title: String,
    val path: String,
    val sizeBytes: Long = 0,
) : Parcelable {
    /** 播放进度持久化 key */
    val progressKey: String
        get() = "remote:$path"
}

/**
 * 已保存的远程服务器配置（SMB 或 WebDAV）。
 *
 * @param host     SMB：`192.168.1.10` 或 `host:445`；WebDAV：`192.168.1.10:5005`（不含协议）
 * @param share    SMB：共享名；WebDAV：基础路径（如 `/dav/视频`，可为空）
 * @param secure   WebDAV 是否使用 https
 * @param insecureTls WebDAV 是否接受自签名证书（NAS 常见）
 */
@Parcelize
data class RemoteServer(
    val id: Long,
    val name: String,
    val protocol: RemoteProtocol = RemoteProtocol.SMB,
    val host: String,
    val share: String = "",
    val domain: String = "",
    val username: String = "",
    val password: String = "",
    val anonymous: Boolean = false,
    val secure: Boolean = false,
    val insecureTls: Boolean = false,
) : Parcelable {

    /** WebDAV 基础路径：以 `/` 开头、不以 `/` 结尾；根目录返回空串 */
    val webdavPath: String
        get() = share.trim().trim('/').let { if (it.isEmpty()) "" else "/$it" }

    /** 卡片上展示的地址 */
    val addressLabel: String
        get() = when (protocol) {
            RemoteProtocol.SMB -> "$host/${share.trim('/')}"
            RemoteProtocol.WEBDAV -> "${if (secure) "https" else "http"}://$host$webdavPath"
        }
}

/** 条目类型：决定用哪个查看器打开 */
enum class FileKind { DIR, VIDEO, IMAGE, TEXT, OTHER }

/** 远程目录条目 */
@Parcelize
data class RemoteEntry(
    val name: String,
    val displayName: String,
    val isDir: Boolean,
    val sizeBytes: Long,
    val url: String,
    val kind: FileKind = FileKind.VIDEO,
) : Parcelable

/** 局域网发现到的服务 */
data class DiscoveredService(
    val host: String,
    val port: Int,
    val protocol: RemoteProtocol,
    val name: String,
) {
    /** 显示用：主机 + 协议标签 */
    val label: String get() = host
}
