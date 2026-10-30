package com.lin0721.linmusic.desktop.di

import com.lin0721.linmusic.desktop.platform.fallback.fallbackPlatformModule
import com.lin0721.linmusic.desktop.platform.macos.macPlatformModule
import com.lin0721.linmusic.desktop.platform.windows.windowsPlatformModule
import org.koin.core.module.Module

// 按当前系统选择平台能力的实现；osName 取自 os.name
fun platformModuleFor(osName: String): Module =
    when {
        osName.startsWith("Windows", ignoreCase = true) -> windowsPlatformModule
        osName.startsWith("Mac", ignoreCase = true) -> macPlatformModule
        else -> fallbackPlatformModule
    }
