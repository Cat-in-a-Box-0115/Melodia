package com.lin0721.linmusic.desktop.platform.macos

import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.desktop.platform.WindowEffects
import com.lin0721.linmusic.desktop.platform.fallback.NoopWindowEffects
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary

private const val TAG = "MacWindowChrome"

// 签名对照 native-src/macos/windowchrome.h
@Suppress("FunctionName")
internal interface WindowChromeLibrary : Library {
    interface ThemeCallback : Callback {
        fun invoke()
    }

    fun melodia_system_is_dark(): Int
    fun melodia_observe_theme(callback: ThemeCallback?)
    fun melodia_window_chrome(title: String, x: Double, centerY: Double, dark: Int)

    companion object {
        private const val LIBRARY_NAME = "melodia_macos"

        fun load(): WindowChromeLibrary {
            System.getProperty("compose.application.resources.dir")?.let {
                NativeLibrary.addSearchPath(LIBRARY_NAME, it)
            }
            return Native.load(
                LIBRARY_NAME,
                WindowChromeLibrary::class.java,
                mapOf(Library.OPTION_STRING_ENCODING to "UTF-8")
            )
        }
    }
}

// 原生库加载失败时所有调用退化为空操作，窗口保持系统默认样式
object MacWindowChrome {
    private val library: WindowChromeLibrary? by lazy {
        runCatching { WindowChromeLibrary.load() }
            .onFailure { AppLogger.w(TAG, "加载窗口外观原生库失败", it) }
            .getOrNull()
    }

    // 原生侧只存函数指针，注销又是异步进行，回调对象须永久持有，否则可能在注销生效前被回收
    private val themeCallbacks = mutableListOf<WindowChromeLibrary.ThemeCallback>()

    fun systemIsDark(): Boolean = library?.melodia_system_is_dark() == 1

    fun observeTheme(onChanged: () -> Unit): () -> Unit {
        val lib = library ?: return {}
        val callback = object : WindowChromeLibrary.ThemeCallback {
            override fun invoke() {
                runCatching(onChanged).onFailure { AppLogger.w(TAG, "处理系统深浅色切换失败", it) }
            }
        }
        synchronized(themeCallbacks) { themeCallbacks += callback }
        lib.melodia_observe_theme(callback)
        return { lib.melodia_observe_theme(null) }
    }

    // x、centerY 为窗口逻辑点
    fun apply(windowTitle: String, x: Double, centerY: Double, dark: Boolean) {
        library?.melodia_window_chrome(windowTitle, x, centerY, if (dark) 1 else 0)
    }
}

// macOS 的窗口装饰交给系统，只需提供深浅色的读取与监听，供“跟随系统”主题使用
internal class MacWindowEffects : WindowEffects by NoopWindowEffects() {
    override fun systemAppsUseLightTheme(): Boolean = !MacWindowChrome.systemIsDark()

    override fun observeSystemTheme(onChanged: () -> Unit): () -> Unit = MacWindowChrome.observeTheme(onChanged)
}
