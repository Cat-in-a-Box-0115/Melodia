package com.lin0721.linmusic.desktop.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.desktop.platform.windows.User32
import com.sun.jna.Native
import java.awt.Frame
import java.awt.Rectangle
import java.awt.Window

private const val TAG = "WindowFullscreen"
private val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

// 主窗口的全屏切换：退出时回到进入前的形态（浮动或最大化）
// Windows 下 WindowPlacement.Fullscreen 走 AWT/D3D 独占全屏，窗口一失去焦点（点另一块屏幕、切到其他窗口）
// 就被系统最小化；改为无边框全屏——浮动窗口铺满所在屏幕，失焦时原样保留
@Stable
class FullscreenState internal constructor(private val windowState: WindowState) {
    internal var restorePlacement = WindowPlacement.Floating
    internal var window: Window? = null
    private var borderless by mutableStateOf(false)
    private var restoreBounds: Rectangle? = null

    val isFullscreen: Boolean get() = borderless || windowState.placement == WindowPlacement.Fullscreen

    fun toggle() {
        if (isFullscreen) exit() else enter()
    }

    fun exit() {
        if (borderless) {
            borderless = false
            val w = window ?: return
            setCaptionStyles(w, true)
            restoreBounds?.let { w.bounds = it }
            if (restorePlacement == WindowPlacement.Maximized) windowState.placement = WindowPlacement.Maximized
        } else if (isFullscreen) {
            windowState.placement = restorePlacement
        }
    }

    private fun enter() {
        restorePlacement = windowState.placement
        val w = window
        if (!isWindows || w == null) {
            windowState.placement = WindowPlacement.Fullscreen
            return
        }
        // 最大化时先还原，记下的是还原后的浮动尺寸
        if (w is Frame && w.extendedState and Frame.MAXIMIZED_BOTH != 0) w.extendedState = Frame.NORMAL
        restoreBounds = w.bounds
        borderless = true
        setCaptionStyles(w, false)
        w.bounds = w.graphicsConfiguration.bounds
    }
}

// 主窗口为了系统动画保留着标题栏与缩放边框样式；带这些样式的窗口铺满屏幕时任务栏仍会盖在上面，全屏期间临时去掉
private fun setCaptionStyles(window: Window, enabled: Boolean) {
    if (!window.isDisplayable) return
    try {
        val hwnd = Native.getWindowPointer(window) ?: return
        val user32 = User32.INSTANCE
        val styles = User32.WS_CAPTION or User32.WS_THICKFRAME
        val current = user32.GetWindowLongPtrW(hwnd, User32.GWL_STYLE)
        user32.SetWindowLongPtrW(hwnd, User32.GWL_STYLE, if (enabled) current or styles else current and styles.inv())
        user32.SetWindowPos(
            hwnd, null, 0, 0, 0, 0,
            User32.SWP_NOMOVE or User32.SWP_NOSIZE or User32.SWP_NOZORDER or User32.SWP_NOACTIVATE or User32.SWP_FRAMECHANGED
        )
    } catch (e: RuntimeException) {
        AppLogger.w(TAG, "切换全屏窗口样式失败", e)
    } catch (e: LinkageError) {
        AppLogger.w(TAG, "切换全屏窗口样式失败", e)
    }
}

@Composable
fun rememberFullscreenState(windowState: WindowState): FullscreenState {
    val state = remember(windowState) { FullscreenState(windowState) }
    // macOS 绿灯等系统途径进入全屏时不经过 enter()，持续记录最近的非全屏形态
    LaunchedEffect(state) {
        snapshotFlow { windowState.placement }.collect { placement ->
            if (placement != WindowPlacement.Fullscreen && !state.isFullscreen) state.restorePlacement = placement
        }
    }
    return state
}

// 把主窗口交给全屏状态，用于 Windows 的无边框全屏；主窗口销毁重建时随之更新
@Composable
fun WindowScope.FullscreenWindowEffect(fullscreen: FullscreenState) {
    DisposableEffect(window, fullscreen) {
        fullscreen.window = window
        onDispose { if (fullscreen.window === window) fullscreen.window = null }
    }
}
