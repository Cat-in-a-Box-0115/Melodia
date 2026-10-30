package com.lin0721.linmusic.desktop.platform.macos

import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.core.player.PlaybackController
import com.lin0721.linmusic.desktop.platform.MediaSessionService
import com.lin0721.linmusic.desktop.ui.components.sizedCoverUrl
import com.lin0721.linmusic.feature.player.ui.PlayerViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URI

private const val TAG = "MacNowPlayingMediaSession"
private const val COVER_SIZE_PX = 300
private const val MAX_COVER_BYTES = 8 * 1024 * 1024

private data class Metadata(val id: String, val title: String, val artist: String, val album: String, val cover: String)

internal class MacNowPlayingMediaSession : MediaSessionService {
    private var library: NowPlayingLibrary? = null
    private val coverClient = OkHttpClient()

    // 原生侧解除回调前保持强引用
    private var callback: NowPlayingLibrary.CommandCallback? = null
    private val commands = MutableSharedFlow<Pair<Int, Long>>(extraBufferCapacity = 16)
    private val _available = MutableStateFlow(false)
    override val available: StateFlow<Boolean> = _available.asStateFlow()
    @Volatile private var enabled = true

    @Synchronized
    override fun start(): Boolean {
        if (library != null) return true
        return try {
            val lib = NowPlayingLibrary.load()
            val cb = object : NowPlayingLibrary.CommandCallback {
                override fun invoke(command: Int, value: Long) {
                    if (enabled) commands.tryEmit(command to value)
                }
            }
            callback = cb
            if (lib.nowplaying_init(cb) != 0) {
                lib.nowplaying_shutdown()
                callback = null
                false
            } else {
                library = lib
                lib.nowplaying_set_enabled(if (enabled) 1 else 0)
                _available.value = true
                true
            }
        } catch (e: LinkageError) {
            AppLogger.w(TAG, "Now Playing 加载失败", e)
            false
        } catch (e: Exception) {
            AppLogger.w(TAG, "Now Playing 初始化失败", e)
            false
        }
    }

    override fun setEnabled(value: Boolean) {
        enabled = value
        call { it.nowplaying_set_enabled(if (value) 1 else 0) }
    }

    @Synchronized
    override fun shutdown() {
        call { it.nowplaying_shutdown() }
        library = null
        callback = null
        _available.value = false
    }

    override suspend fun bind(controller: PlaybackController, playerViewModel: PlayerViewModel): Unit = coroutineScope {
        if (!available.value) return@coroutineScope
        launch(Dispatchers.Main.immediate) {
            var coverJob: Job? = null
            var coverToken: Pair<String, String>? = null
            combine(controller.nowPlaying, playerViewModel.songDetailState) { track, detail ->
                track?.let {
                    val album = detail.songDetail?.takeIf { song -> song.id == it.songId }?.al?.name.orEmpty()
                    Metadata(it.mediaId, it.title, it.artist, album, sizedCoverUrl(it.artworkUri, COVER_SIZE_PX).orEmpty())
                }
            }.distinctUntilChanged().collect { meta ->
                // 文字立即更新，封面下载不阻塞切歌
                call { it.nowplaying_set_metadata(meta?.title, meta?.artist, meta?.album) }
                pushPlayback(controller)
                val token = meta?.let { it.id to it.cover }
                if (token != coverToken) {
                    coverToken = token
                    coverJob?.cancel()
                    call { it.nowplaying_set_artwork(null, 0) }
                    coverJob = if (meta != null && meta.cover.isNotBlank()) launch {
                        val bytes = loadCover(meta.cover)
                        val track = controller.nowPlaying.value
                        val currentToken = track?.let {
                            it.mediaId to sizedCoverUrl(it.artworkUri, COVER_SIZE_PX).orEmpty()
                        }
                        // 只接收当前歌曲的封面
                        if (token == coverToken && token == currentToken) {
                            call { it.nowplaying_set_artwork(bytes, bytes?.size ?: 0) }
                        }
                    } else null
                }
            }
        }
        launch {
            combine(controller.nowPlaying, controller.playWhenReady, controller.duration) { track, playing, duration ->
                Triple(track?.mediaId, playing, duration)
            }.distinctUntilChanged().collect { pushPlayback(controller) }
        }
        launch {
            // 系统按播放速率推算进度，每秒校准跳转与缓冲偏差
            while (true) {
                delay(1_000)
                pushPlayback(controller)
            }
        }
        launch(Dispatchers.Main.immediate) {
            commands.collect { (command, value) ->
                if (!enabled || !available.value || controller.nowPlaying.value == null) return@collect
                when (command) {
                    NowPlayingCommand.PLAY -> controller.resume()
                    NowPlayingCommand.PAUSE -> controller.pause()
                    NowPlayingCommand.NEXT -> controller.playNext()
                    NowPlayingCommand.PREVIOUS -> controller.skipToPrevious()
                    NowPlayingCommand.SEEK -> controller.seekTo(value.coerceAtLeast(0))
                    NowPlayingCommand.TOGGLE -> if (controller.playWhenReady.value) controller.pause() else controller.resume()
                }
            }
        }
    }

    private fun pushPlayback(controller: PlaybackController) {
        call {
            it.nowplaying_set_playback(
                if (controller.nowPlaying.value != null && controller.playWhenReady.value) 1 else 0,
                controller.currentPosition.value,
                controller.duration.value
            )
        }
    }

    // 切歌取消旧封面，下载失败不影响文字和播放控制
    private suspend fun loadCover(url: String): ByteArray? = withTimeoutOrNull(8_000) {
        try {
            val uri = URI(url)
            if (uri.scheme != "http" && uri.scheme != "https") return@withTimeoutOrNull null
            suspendCancellableCoroutine { continuation ->
                val request = coverClient.newCall(Request.Builder().url(url).build())
                continuation.invokeOnCancellation { request.cancel() }
                request.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeWith(Result.failure(e))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        continuation.resumeWith(runCatching {
                            response.use {
                                if (!it.isSuccessful) return@use null
                                it.body?.byteStream()?.use { input ->
                                    input.readNBytes(MAX_COVER_BYTES + 1).takeIf { bytes -> bytes.size <= MAX_COVER_BYTES }
                                }
                            }
                        })
                    }
                })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "系统卡片封面加载失败", e)
            null
        }
    }

    @Synchronized
    private fun call(block: (NowPlayingLibrary) -> Unit) {
        val lib = library ?: return
        runCatching { block(lib) }.onFailure { AppLogger.w(TAG, "Now Playing 调用失败", it) }
    }
}
