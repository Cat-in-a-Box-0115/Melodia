package com.lin0721.linmusic.desktop.platform.macos

import com.lin0721.linmusic.desktop.platform.AutoStartService
import com.lin0721.linmusic.desktop.platform.BrowserLocator
import com.lin0721.linmusic.desktop.platform.GlobalHotkeyService
import com.lin0721.linmusic.desktop.platform.MediaSessionService
import com.lin0721.linmusic.desktop.platform.TaskbarThumbnailService
import com.lin0721.linmusic.desktop.platform.WindowEffects
import com.lin0721.linmusic.desktop.platform.fallback.NoopGlobalHotkeys
import com.lin0721.linmusic.desktop.platform.fallback.NoopTaskbarThumbnail
import com.lin0721.linmusic.desktop.platform.fallback.NoopWindowEffects
import com.lin0721.linmusic.desktop.platform.fallback.UnsupportedAutoStart
import org.koin.dsl.module
import java.io.File

val macPlatformModule = module {
    single<MediaSessionService> { MacNowPlayingMediaSession() }
    single<GlobalHotkeyService> { NoopGlobalHotkeys() }
    single<AutoStartService> { UnsupportedAutoStart() }
    single<WindowEffects> { NoopWindowEffects() }
    single<TaskbarThumbnailService> { NoopTaskbarThumbnail() }
    single<BrowserLocator> { BrowserLocator(::findChromiumBrowser) }
}

// 网页登录需要 Chromium 内核浏览器，按系统级、用户级应用目录依次查找
private fun findChromiumBrowser(): File? {
    val bundles = listOf(
        "Google Chrome.app/Contents/MacOS/Google Chrome",
        "Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
        "Brave Browser.app/Contents/MacOS/Brave Browser",
        "Chromium.app/Contents/MacOS/Chromium"
    )
    val roots = listOf(File("/Applications"), File(System.getProperty("user.home"), "Applications"))
    return roots.flatMap { root -> bundles.map { File(root, it) } }
        .firstOrNull { it.isFile && it.canExecute() }
}
