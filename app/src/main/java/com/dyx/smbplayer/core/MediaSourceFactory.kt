package com.dyx.smbplayer.core

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.dyx.smbplayer.data.SmbSource
import com.dyx.smbplayer.data.model.RemoteProtocol
import com.dyx.smbplayer.data.model.RemoteServer
import com.dyx.smbplayer.data.model.VideoItem

/**
 * 构造远程视频的 MediaSource：
 * 按协议选择数据源（SMB 句柄复用 / WebDAV Range 取流），并**明确使用 ProgressiveMediaSource**，
 * 避免 DefaultMediaSourceFactory 依赖 URI 后缀推断容器类型导致装载失败。
 */
object MediaSourceFactory {

    @OptIn(UnstableApi::class)
    fun create(item: VideoItem, server: RemoteServer): MediaSource {
        val dataSourceFactory: DataSource.Factory = DataSource.Factory {
            when (server.protocol) {
                RemoteProtocol.SMB -> SmbDataSource(SmbSource.contextFor(server))
                RemoteProtocol.WEBDAV -> WebDavDataSource(server)
            }
        }

        val builder = MediaItem.Builder().setUri(item.path)

        // 同目录同名 .srt 外挂字幕（字幕加载失败不会影响正片播放）
        val subtitleUrl = item.path.substringBeforeLast('.') + ".srt"
        val subtitle = MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUrl))
            .setMimeType(MimeTypes.APPLICATION_SUBRIP)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        builder.setSubtitleConfigurations(listOf(subtitle))

        return ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(builder.build())
    }
}
