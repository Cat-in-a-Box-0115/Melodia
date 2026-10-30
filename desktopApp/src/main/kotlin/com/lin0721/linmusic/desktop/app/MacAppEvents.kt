package com.lin0721.linmusic.desktop.app

import com.lin0721.linmusic.core.log.AppLogger
import java.awt.Desktop
import java.awt.desktop.AppReopenedListener

private const val TAG = "MacAppEvents"

// macOS 的程序坞点击与 ⌘Q 不经过单实例 socket，需单独接入系统应用事件
object MacAppEvents {

    fun install(onReopen: () -> Unit, onQuit: () -> Unit) {
        if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) return
        runCatching {
            val desktop = Desktop.getDesktop()
            // 主窗口隐藏到后台后，点击程序坞图标重新显示
            desktop.addAppEventListener(AppReopenedListener { onReopen() })
            // 交给统一的退出流程清理播放器与缓存，由其自行结束进程
            desktop.setQuitHandler { _, response ->
                onQuit()
                response.cancelQuit()
            }
        }.onFailure { AppLogger.w(TAG, "注册 macOS 应用事件失败", it) }
    }
}
