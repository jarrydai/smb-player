package com.dyx.smbplayer.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.dyx.smbplayer.core.MediaSourceFactory
import com.dyx.smbplayer.data.PrefsStore
import com.dyx.smbplayer.data.RemoteFiles
import com.dyx.smbplayer.data.model.RemoteServer
import com.dyx.smbplayer.data.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val TAG = "NetPlayer"
    }

    private val prefs = PrefsStore(app)

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    private val _resumeFrom = MutableStateFlow<Long?>(null)
    val resumeFrom: StateFlow<Long?> = _resumeFrom.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _buffering = MutableStateFlow(false)
    val buffering: StateFlow<Boolean> = _buffering.asStateFlow()

    /** 整屏横滑对应的快进秒数（设置项，默认为 5 分钟） */
    private val _seekRangeSeconds = MutableStateFlow(PrefsStore.DEFAULT_SEEK_RANGE_SECONDS)
    val seekRangeSeconds: StateFlow<Int> = _seekRangeSeconds.asStateFlow()

    private var currentItem: VideoItem? = null
    private var currentServer: RemoteServer? = null

    init {
        viewModelScope.launch {
            prefs.seekRangeFlow.collect { _seekRangeSeconds.value = it }
        }
    }

    fun ensurePlayer(): ExoPlayer {
        _player.value?.let { return it }
        // 网络播放加大缓冲：起播慢一点但拖动/卡顿明显减少；
        // 起播阈值降到 1s，拖动进度条后不必等 2s 才继续。
        // 同时给总缓冲设 96MB 上限：5GB+ 高码率大文件若按 90s 时长无限缓存，会吃掉几百 MB 内存导致 OOM/卡顿
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 60_000, 1_000, 2_500)
            .setTargetBufferBytes(96 * 1024 * 1024)
            .build()
        val p = ExoPlayer.Builder(getApplication())
            .setLoadControl(loadControl)
            // 关键：拖动后直接跳到最近关键帧，不再从上一个关键帧解码追赶（否则大文件拖动很慢）
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            .build()
        // 网络播放保持唤醒，避免缓冲时休眠断流
        p.setWakeMode(C.WAKE_MODE_NETWORK)
        p.setHandleAudioBecomingNoisy(true)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                _buffering.value = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) _error.value = null
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "播放失败: ${currentItem?.path}", error)
                _buffering.value = false
                _error.value = describeError(error)
            }
        })
        _player.value = p
        return p
    }

    /** 准备播放：同一路径已在播则不重复装载 */
    fun prepare(item: VideoItem, server: RemoteServer) {
        val p = ensurePlayer()
        if (currentItem?.path == item.path && p.playbackState != ExoPlayer.STATE_IDLE) return
        currentItem = item
        currentServer = server
        _error.value = null
        // 切到新视频前释放上一个视频残留的连接（同路径重试时 probe 会重新打开）
        RemoteFiles.releaseAll()

        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { prefs.getProgress(item.progressKey) }
                val resumePos = if (saved > 10_000L) saved else 0L
                Log.d(TAG, "prepare: ${item.path} (server=${server.host}, resume=$resumePos)")

                // 先做可访问性探测：失败直接给出明确原因，避免黑屏无反馈
                val size = withContext(Dispatchers.IO) {
                    RemoteFiles.probeFile(server, item.path)
                }
                Log.d(
                    TAG,
                    "probe ok, size=$size (${String.format("%.2f", size / 1024.0 / 1024.0)} MB), " +
                        "seekRange=${_seekRangeSeconds.value}s",
                )

                val source = MediaSourceFactory.create(item, server)
                p.setMediaSource(source, resumePos)
                p.prepare()
                p.playWhenReady = true
                if (resumePos > 0) {
                    _resumeFrom.value = resumePos
                }
            } catch (e: Throwable) {
                Log.e(TAG, "装载媒体失败: ${item.path}", e)
                _buffering.value = false
                _error.value = "无法打开文件\n${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun retry() {
        val item = currentItem ?: return
        val server = currentServer ?: return
        _error.value = null
        _player.value?.let { p ->
            p.stop()
            p.clearMediaItems()
        }
        currentItem = null
        prepare(item, server)
    }

    fun consumeResume() {
        _resumeFrom.value = null
    }

    fun saveProgress() {
        val p = _player.value ?: return
        val item = currentItem ?: return
        if (p.playbackState == ExoPlayer.STATE_READY && p.currentPosition > 0) {
            val pos = p.currentPosition
            viewModelScope.launch(Dispatchers.IO) {
                prefs.setProgress(item.progressKey, pos)
            }
        }
    }

    /** 从头播放（清除记忆进度） */
    fun restartFromBeginning() {
        val item = currentItem ?: return
        viewModelScope.launch(Dispatchers.IO) {
            prefs.clearProgress(item.progressKey)
        }
        _player.value?.let { p ->
            p.seekTo(0)
            p.play()
        }
    }

    /** 退出播放页时调用：保存进度并完全释放播放器（ViewModel 是 Activity 级的，需手动释放） */
    fun releasePlayer() {
        saveProgress()
        _player.value?.release()
        _player.value = null
        currentItem = null
        currentServer = null
        _buffering.value = false
        _error.value = null
        _resumeFrom.value = null
        // 释放底层连接，避免停留在后台占用 NAS 会话
        RemoteFiles.releaseAll()
    }

    override fun onCleared() {
        saveProgress()
        _player.value?.release()
        _player.value = null
        RemoteFiles.releaseAll()
    }

    /** 把 ExoPlayer 的错误链拼成用户看得懂的文案 */
    private fun describeError(error: PlaybackException): String {
        val sb = StringBuilder("播放失败（错误码 ${error.errorCodeName}）\n")
        currentItem?.let { sb.append("文件：").append(it.title).append('\n') }
        var t: Throwable? = error
        var depth = 0
        val seen = mutableSetOf<Throwable>()
        while (t != null && depth < 6 && seen.add(t)) {
            t.message?.takeIf { it.isNotBlank() }?.let { sb.append("· ").append(it).append('\n') }
            t = t.cause
            depth++
        }
        return sb.toString().trim()
    }
}
