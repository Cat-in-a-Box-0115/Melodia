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

val macPlatformModule = module {
    single<MediaSessionService> { MacNowPlayingMediaSession() }
    single<GlobalHotkeyService> { NoopGlobalHotkeys() }
    single<AutoStartService> { UnsupportedAutoStart() }
    single<WindowEffects> { NoopWindowEffects() }
    single<TaskbarThumbnailService> { NoopTaskbarThumbnail() }
    single<BrowserLocator> { BrowserLocator { null } }
}
