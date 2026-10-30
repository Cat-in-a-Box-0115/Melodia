package com.lin0721.linmusic.desktop.platform.macos

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary

// 签名对照 native-src/macos/nowplaying.h
@Suppress("FunctionName")
internal interface NowPlayingLibrary : Library {
    interface CommandCallback : Callback {
        fun invoke(command: Int, value: Long)
    }

    fun nowplaying_init(callback: CommandCallback): Int
    fun nowplaying_set_enabled(enabled: Int)
    fun nowplaying_set_metadata(title: String?, artist: String?, album: String?)
    fun nowplaying_set_artwork(bytes: ByteArray?, length: Int)
    fun nowplaying_set_playback(playing: Int, positionMs: Long, durationMs: Long)
    fun nowplaying_shutdown()

    companion object {
        private const val LIBRARY_NAME = "melodia_macos"

        fun load(): NowPlayingLibrary {
            System.getProperty("compose.application.resources.dir")?.let {
                NativeLibrary.addSearchPath(LIBRARY_NAME, it)
            }
            return Native.load(
                LIBRARY_NAME,
                NowPlayingLibrary::class.java,
                mapOf(Library.OPTION_STRING_ENCODING to "UTF-8")
            )
        }
    }
}

// 与原生侧命令顺序保持一致
internal object NowPlayingCommand {
    const val PLAY = 1
    const val PAUSE = 2
    const val NEXT = 3
    const val PREVIOUS = 4
    const val SEEK = 5
    const val TOGGLE = 6
}
