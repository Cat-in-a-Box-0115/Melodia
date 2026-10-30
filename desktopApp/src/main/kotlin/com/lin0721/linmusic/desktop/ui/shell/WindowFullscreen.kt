package com.lin0721.linmusic.desktop.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState

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
