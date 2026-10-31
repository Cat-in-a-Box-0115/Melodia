package com.lin0721.linmusic.desktop.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.core.ui.theme.floatingOutline
import com.lin0721.linmusic.desktop.app.DesktopPaths
import com.lin0721.linmusic.desktop.ui.components.DesktopTooltip
import com.lin0721.linmusic.desktop.ui.components.TooltipSide
import com.lin0721.linmusic.desktop.ui.components.uiTween
import com.lin0721.linmusic.desktop.ui.theme.DesktopColors
import com.lin0721.linmusic.desktop.ui.theme.DesktopDimens
import kotlinx.coroutines.delay
import java.io.File
import java.util.Properties

private const val TAG = "SidePanes"
private const val FILE_NAME = "side_panes.properties"
private const val KEY_LIBRARY_HIDDEN = "library.hidden"

// 指针进入窗口边缘的缝隙时浮出拉手，范围不越过内容区，免得挡住贴边的滚动条；离开后稍等再收回，避免沿边缘移动时闪烁
private val EDGE_REVEAL_DISTANCE = 12.dp
private const val TAB_HIDE_DELAY_MS = 250L
private const val TAB_ANIMATION_MS = 160

// 音乐库的完全隐藏开关，叠加在上游的窄条/默认/展开形态之上，显示时回到原形态。
// 单独存一个文件而不是并入 DesktopPreferences，避免与上游的偏好结构互相冲突
object SidePanes {
    private val file: File get() = File(DesktopPaths.dataDir, FILE_NAME)
    private val stored: Properties = Properties().also { props ->
        runCatching { file.takeIf { it.isFile }?.inputStream()?.use(props::load) }
            .onFailure { AppLogger.w(TAG, "读取侧栏显示状态失败", it) }
    }

    var libraryHidden by mutableStateOf(stored.getProperty(KEY_LIBRARY_HIDDEN).toBoolean())
        private set

    fun hideLibrary() = updateLibraryHidden(true)

    fun showLibrary() = updateLibraryHidden(false)

    private fun updateLibraryHidden(hidden: Boolean) {
        if (hidden == libraryHidden) return
        libraryHidden = hidden
        stored.setProperty(KEY_LIBRARY_HIDDEN, hidden.toString())
        runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().use { stored.store(it, null) }
        }.onFailure { AppLogger.w(TAG, "保存侧栏显示状态失败", it) }
    }
}

// 记录指针是否贴近工作区左右边缘；只观察不消费事件，不影响下层内容的点击与滚动
@Stable
class EdgeHoverState {
    var nearLeft by mutableStateOf(false)
        internal set
    var nearRight by mutableStateOf(false)
        internal set
}

@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.trackEdgeHover(state: EdgeHoverState): Modifier {
    val update: AwaitPointerEventScope.(PointerEvent) -> Unit = { event ->
        event.changes.firstOrNull()?.position?.x?.let { x ->
            val reveal = EDGE_REVEAL_DISTANCE.toPx()
            state.nearLeft = x < reveal
            state.nearRight = x > size.width - reveal
        }
    }
    return onPointerEvent(PointerEventType.Move, onEvent = update)
        .onPointerEvent(PointerEventType.Enter, onEvent = update)
        .onPointerEvent(PointerEventType.Exit) {
            state.nearLeft = false
            state.nearRight = false
        }
}

// 隐藏的侧栏在指针贴近边缘时浮出的拉手，外观与正在播放栏收起后的窄条一致，点击即恢复
@Composable
internal fun BoxScope.EdgeRevealTabs(
    hover: EdgeHoverState,
    leftHidden: Boolean,
    rightHidden: Boolean,
    onShowLeft: () -> Unit,
    onShowRight: () -> Unit
) {
    EdgeTab(
        left = true,
        hidden = leftHidden,
        near = hover.nearLeft,
        hint = "显示音乐库",
        onClick = onShowLeft,
        modifier = Modifier.align(Alignment.CenterStart)
    )
    EdgeTab(
        left = false,
        hidden = rightHidden,
        near = hover.nearRight,
        hint = "显示“正在播放”",
        onClick = onShowRight,
        modifier = Modifier.align(Alignment.CenterEnd)
    )
}

@Composable
private fun EdgeTab(
    left: Boolean,
    hidden: Boolean,
    near: Boolean,
    hint: String,
    onClick: () -> Unit,
    modifier: Modifier
) {
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(hidden, near, hovered) {
        if (hidden && (near || hovered)) {
            shown = true
        } else {
            if (hidden) delay(TAB_HIDE_DELAY_MS)
            shown = false
        }
    }
    val shape = RoundedCornerShape(DesktopDimens.PaneRadius)
    AnimatedVisibility(
        visible = shown,
        modifier = modifier.fillMaxHeight().padding(horizontal = DesktopDimens.PaneGap),
        enter = fadeIn(uiTween(TAB_ANIMATION_MS)) +
            slideInHorizontally(uiTween(TAB_ANIMATION_MS)) { if (left) -it else it },
        exit = fadeOut(uiTween(TAB_ANIMATION_MS)) +
            slideOutHorizontally(uiTween(TAB_ANIMATION_MS)) { if (left) -it else it }
    ) {
        DesktopTooltip(
            hint,
            modifier = Modifier.width(DesktopDimens.NowPlayingHandleWidth).fillMaxHeight(),
            side = if (left) TooltipSide.Right else TooltipSide.Left
        ) {
            Box(
                Modifier.fillMaxSize()
                    .shadow(8.dp, shape)
                    .clip(shape)
                    .background(DesktopColors.Pane)
                    .floatingOutline(shape)
                    .hoverable(hoverSource)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(interactionSource = hoverSource, indication = null) {
                        shown = false
                        onClick()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (left) Icons.Rounded.ChevronRight else Icons.Rounded.ChevronLeft,
                    null,
                    tint = if (hovered) DesktopColors.TextPrimary else DesktopColors.TextGray
                )
            }
        }
    }
}
