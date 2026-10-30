package com.lin0721.linmusic.desktop.ui.shell

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ViewSidebar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.desktop.app.DesktopPaths
import com.lin0721.linmusic.desktop.ui.components.DesktopTooltip
import com.lin0721.linmusic.desktop.ui.components.TooltipSide
import com.lin0721.linmusic.desktop.ui.theme.DesktopColors
import java.io.File
import java.util.Properties

private const val TAG = "SidePanes"
private const val FILE_NAME = "side_panes.properties"
private const val KEY_LIBRARY_HIDDEN = "library.hidden"
private const val KEY_DOCK_HIDDEN = "dock.hidden"

// 两侧栏的完全隐藏开关，叠加在上游的窄条/收起形态之上：隐藏时连窄条一起让出空间。
// 单独存一个文件而不是并入 DesktopPreferences，避免与上游的偏好结构互相冲突
object SidePanes {
    private val file: File get() = File(DesktopPaths.dataDir, FILE_NAME)
    private val stored: Properties = Properties().also { props ->
        runCatching { file.takeIf { it.isFile }?.inputStream()?.use(props::load) }
            .onFailure { AppLogger.w(TAG, "读取侧栏显示状态失败", it) }
    }

    var libraryHidden by mutableStateOf(stored.getProperty(KEY_LIBRARY_HIDDEN).toBoolean())
        private set
    var dockHidden by mutableStateOf(stored.getProperty(KEY_DOCK_HIDDEN).toBoolean())
        private set

    fun toggleLibrary() {
        libraryHidden = !libraryHidden
        save()
    }

    fun toggleDock() {
        dockHidden = !dockHidden
        save()
    }

    private fun save() {
        stored.setProperty(KEY_LIBRARY_HIDDEN, libraryHidden.toString())
        stored.setProperty(KEY_DOCK_HIDDEN, dockHidden.toString())
        runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().use { stored.store(it, null) }
        }.onFailure { AppLogger.w(TAG, "保存侧栏显示状态失败", it) }
    }
}

// 标题栏上的侧栏开关；左侧用镜像后的侧栏图标
@Composable
internal fun SidePaneToggle(left: Boolean) {
    val hidden = if (left) SidePanes.libraryHidden else SidePanes.dockHidden
    val label = when {
        left && hidden -> "显示音乐库"
        left -> "隐藏音乐库"
        hidden -> "显示右侧栏"
        else -> "隐藏右侧栏"
    }
    DesktopTooltip(label, side = TooltipSide.Bottom) {
        IconButton(
            onClick = if (left) SidePanes::toggleLibrary else SidePanes::toggleDock,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                Icons.Outlined.ViewSidebar,
                label,
                tint = if (hidden) DesktopColors.TextGray else DesktopColors.TextPrimary,
                modifier = Modifier.size(18.dp).graphicsLayer { if (left) scaleX = -1f }
            )
        }
    }
}
