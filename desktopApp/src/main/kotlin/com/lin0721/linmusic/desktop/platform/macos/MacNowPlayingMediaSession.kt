package com.lin0721.linmusic.desktop.platform.macos

import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.core.player.PlaybackController
import com.lin0721.linmusic.desktop.platform.MediaSessionService
import com.lin0721.linmusic.desktop.ui.components.sizedCoverUrl
import com.lin0721.linmusic.feature.player.ui.PlayerViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

private const val TAG = "MacNowPlayingMediaSession"
private const val COVER_SIZE_PX = 300
private const val MAX_COVER_BYTES = 8 * 1024 * 1024

private data class Metadata(val id: String, val title: String, val artist: String, val album: String, val cover: String)

internal class MacNowPlayingMediaSession : MediaSessionService {
    private var library: NowPlayingLibrary? = null

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
        launch {
            combine(controller.nowPlaying, playerViewModel.songDetailState) { track, detail ->
                track?.let {
                    val album = detail.songDetail?.takeIf { song -> song.id == it.songId }?.al?.name.orEmpty()
                    Metadata(it.mediaId, it.title, it.artist, album, sizedCoverUrl(it.artworkUri, COVER_SIZE_PX).orEmpty())
                }
            }.distinctUntilChanged().collectLatest { meta ->
                call { it.nowplaying_set_metadata(meta?.title, meta?.artist, meta?.album) }
                pushPlayback(controller)
                if (meta != null && meta.cover.isNotBlank()) {
                    val bytes = loadCover(meta.cover)
                    call { it.nowplaying_set_artwork(bytes, bytes?.size ?: 0) }
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
    private suspend fun loadCover(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val uri = URI(url)
            if (uri.scheme != "http" && uri.scheme != "https") return@withContext null
            val connection = uri.toURL().openConnection().apply {
                connectTimeout = 5_000
                readTimeout = 5_000
            }
            connection.getInputStream().use { input ->
                input.readNBytes(MAX_COVER_BYTES + 1).takeIf { it.size <= MAX_COVER_BYTES }
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
