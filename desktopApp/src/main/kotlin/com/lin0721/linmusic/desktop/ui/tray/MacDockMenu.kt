package com.lin0721.linmusic.desktop.ui.tray

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.lin0721.linmusic.core.log.AppLogger
import java.awt.CheckboxMenuItem
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.Taskbar

private const val TAG = "MacDockMenu"

// macOS 不放菜单栏图标，托盘菜单里的播放控制改挂到程序坞图标的右键菜单；
// 系统会在其后自动附上“显示所有窗口”“隐藏”“退出”等项
@Composable
fun MacDockMenu(entries: List<TrayMenuEntry>) {
    val menu = remember { PopupMenu() }
    val latest by rememberUpdatedState(entries)
    DisposableEffect(menu) {
        val taskbar = runCatching { Taskbar.getTaskbar() }
            .getOrNull()
            ?.takeIf { Taskbar.isTaskbarSupported() && it.isSupported(Taskbar.Feature.MENU) }
        runCatching { taskbar?.menu = menu }.onFailure { AppLogger.w(TAG, "设置程序坞菜单失败", it) }
        onDispose { runCatching { taskbar?.menu = null } }
    }
    // 系统菜单直接引用这些菜单项，打开期间整体重建会让正显示的项失效：
    // 只有项的种类或数量变化才重建，平时原地更新文字与勾选；回调按下标取最新的条目
    SideEffect {
        val structureChanged = menu.itemCount != entries.size ||
            entries.indices.any { !menu.getItem(it).matches(entries[it]) }
        if (structureChanged) {
            menu.removeAll()
            entries.forEachIndexed { index, entry ->
                when (entry) {
                    is TrayMenuEntry.Action -> menu.add(MenuItem(entry.label).apply {
                        addActionListener { (latest.getOrNull(index) as? TrayMenuEntry.Action)?.onClick?.invoke() }
                    })
                    is TrayMenuEntry.Toggle -> menu.add(CheckboxMenuItem(entry.label, entry.checked).apply {
                        addItemListener { (latest.getOrNull(index) as? TrayMenuEntry.Toggle)?.onChange?.invoke(state) }
                    })
                    TrayMenuEntry.Divider -> menu.addSeparator()
                }
            }
        } else {
            entries.forEachIndexed { index, entry ->
                val item = menu.getItem(index)
                when (entry) {
                    is TrayMenuEntry.Action -> if (item.label != entry.label) item.label = entry.label
                    is TrayMenuEntry.Toggle -> (item as CheckboxMenuItem).let {
                        if (it.label != entry.label) it.label = entry.label
                        if (it.state != entry.checked) it.state = entry.checked
                    }
                    TrayMenuEntry.Divider -> Unit
                }
            }
        }
    }
}

private fun MenuItem.matches(entry: TrayMenuEntry): Boolean = when (entry) {
    is TrayMenuEntry.Action -> this !is CheckboxMenuItem && label != "-"
    is TrayMenuEntry.Toggle -> this is CheckboxMenuItem
    TrayMenuEntry.Divider -> label == "-"
}
