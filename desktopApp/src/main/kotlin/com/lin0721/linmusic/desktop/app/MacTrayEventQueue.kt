package com.lin0721.linmusic.desktop.app

import com.lin0721.linmusic.core.log.AppLogger
import java.awt.AWTEvent
import java.awt.Component
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.event.MouseEvent

private const val TAG = "MacTrayEventQueue"

// macOS 上按住菜单栏图标时指针稍有移动就会产生拖动事件：它先广播给全局 AWTEventListener，
// Swing 的 LightweightDispatcher 按 Component 处理 TrayIcon 来源的拖动事件时空指针；
// 同时系统不再为这次按下生成单击，托盘的“单击打开主窗口”随之失效。
// 这里丢弃菜单栏图标的拖动事件，并在发生过拖动的左键松开后补发一次单击，其余事件照常分发
internal class MacTrayEventQueue : EventQueue() {

    private var draggedSincePress = false

    override fun dispatchEvent(event: AWTEvent) {
        val icon = event.source as? TrayIcon
        if (icon == null || event !is MouseEvent) {
            super.dispatchEvent(event)
            return
        }
        when (event.id) {
            MouseEvent.MOUSE_DRAGGED -> draggedSincePress = true
            MouseEvent.MOUSE_PRESSED -> {
                draggedSincePress = false
                super.dispatchEvent(event)
            }
            MouseEvent.MOUSE_RELEASED -> {
                super.dispatchEvent(event)
                if (draggedSincePress && event.button == MouseEvent.BUTTON1 && !event.isPopupTrigger) {
                    super.dispatchEvent(clickFrom(icon, event))
                }
                draggedSincePress = false
            }
            else -> super.dispatchEvent(event)
        }
    }

    // MouseEvent 构造要求 Component 来源，系统的托盘事件同样是先挂占位组件再改来源
    private fun clickFrom(icon: TrayIcon, released: MouseEvent): MouseEvent =
        MouseEvent(
            PLACEHOLDER,
            MouseEvent.MOUSE_CLICKED,
            released.`when`,
            released.modifiersEx,
            released.x,
            released.y,
            released.xOnScreen,
            released.yOnScreen,
            1,
            false,
            MouseEvent.BUTTON1
        ).apply { source = icon }

    companion object {
        private val PLACEHOLDER = object : Component() {}

        fun install() {
            runCatching { Toolkit.getDefaultToolkit().systemEventQueue.push(MacTrayEventQueue()) }
                .onFailure { AppLogger.w(TAG, "安装菜单栏图标事件修复失败", it) }
        }
    }
}
