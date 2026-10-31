package com.lin0721.linmusic.desktop.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import kotlinx.coroutines.delay

// 主窗口的全屏切换：退出时回到进入前的形态（浮动或最大化）
@Stable
class FullscreenState internal constructor(private val windowState: WindowState) {
    internal var restorePlacement = WindowPlacement.Floating

    val isFullscreen: Boolean get() = windowState.placement == WindowPlacement.Fullscreen

    fun toggle() {
        if (isFullscreen) exit() else enter()
    }

    fun exit() {
        if (isFullscreen) windowState.placement = restorePlacement
    }

    private fun enter() {
        restorePlacement = windowState.placement
        windowState.placement = WindowPlacement.Fullscreen
    }
}

@Composable
fun rememberFullscreenState(windowState: WindowState): FullscreenState {
    val state = remember(windowState) { FullscreenState(windowState) }
    // macOS 绿灯等系统途径进入全屏时不经过 enter()，持续记录最近的非全屏形态
    LaunchedEffect(state) {
        snapshotFlow { windowState.placement }.collect { placement ->
            if (placement != WindowPlacement.Fullscreen) state.restorePlacement = placement
        }
    }
    return state
}

private val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
private const val FS_ADAPTER = "sun.awt.Win32GraphicsDevice\$Win32FSWindowAdapter"
private const val FS_ADAPTER_POLL_MS = 50L
private const val FS_ADAPTER_POLLS = 40

// Windows 下全屏走 AWT 独占全屏，JDK 会在窗口失去焦点时把它最小化（点另一块屏幕、切到其他窗口即缩回任务栏）；
// 进入全屏后摘掉 JDK 挂上的这个监听，窗口保持全屏，行为与普通全屏应用一致
@Composable
fun WindowScope.KeepFullscreenOnFocusLoss(windowState: WindowState) {
    if (!isWindows) return
    LaunchedEffect(window, windowState) {
        snapshotFlow { windowState.placement == WindowPlacement.Fullscreen }.collect { fullscreen ->
            if (!fullscreen) return@collect
            // 全屏状态在之后的帧才交给 AWT，监听也可能稍后才挂上
            repeat(FS_ADAPTER_POLLS) {
                val adapters = window.windowListeners.filter { it.javaClass.name == FS_ADAPTER }
                if (adapters.isNotEmpty()) {
                    adapters.forEach(window::removeWindowListener)
                    return@collect
                }
                delay(FS_ADAPTER_POLL_MS)
            }
        }
    }
}
