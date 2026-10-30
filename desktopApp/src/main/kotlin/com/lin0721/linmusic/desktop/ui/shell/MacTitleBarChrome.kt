package com.lin0721.linmusic.desktop.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowScope
import com.lin0721.linmusic.desktop.platform.macos.MacWindowChrome
import com.lin0721.linmusic.desktop.ui.theme.DesktopColors
import com.lin0721.linmusic.desktop.ui.theme.DesktopDimens
import java.awt.Frame

// 红绿灯左缘位置、三个按钮的总宽度（含间距）与其后留白，单位为系统逻辑点
private const val TRAFFIC_LIGHTS_X_PT = 20f
private const val TRAFFIC_LIGHTS_WIDTH_PT = 54f
private const val TRAFFIC_LIGHTS_TRAILING_PT = 12f

// 系统逻辑点与界面 dp 的换算：界面缩放改变了 dp，红绿灯却保持原生尺寸
@Composable
private fun WindowScope.pointsPerDp(): Float {
    val screenScale = window.graphicsConfiguration?.defaultTransform?.scaleX?.toFloat() ?: 1f
    return LocalDensity.current.density / screenScale
}

// 标题栏左侧为红绿灯留出的宽度
@Composable
internal fun WindowScope.macTrafficLightsInset(): Dp =
    ((TRAFFIC_LIGHTS_X_PT + TRAFFIC_LIGHTS_WIDTH_PT + TRAFFIC_LIGHTS_TRAILING_PT) / pointsPerDp()).dp

// 把红绿灯垂直居中到标题栏，并让系统窗口外观（含外框线）跟随应用深浅色
@Composable
internal fun WindowScope.MacTitleBarChrome() {
    val centerY = DesktopDimens.TitleBarHeight.value / 2 * pointsPerDp()
    val dark = DesktopColors.IsDark
    val title = (window as? Frame)?.title ?: return
    LaunchedEffect(title, centerY, dark) {
        MacWindowChrome.apply(title, TRAFFIC_LIGHTS_X_PT.toDouble(), centerY.toDouble(), dark)
    }
}
