package com.lin0721.linmusic.desktop.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lin0721.linmusic.desktop.i18n.I18n
import com.lin0721.linmusic.desktop.ui.theme.DesktopColors

// 界面语言：选项名各用本语言书写，不进词典；切换后重启生效
internal object LanguageSetting {
    fun entry(category: SettingsCategory) = SettingsEntry(
        key = "language",
        category = category,
        title = "语言",
        keywords = listOf("Language", "English", "中文", "语言", "界面")
    ) {
        var selected by remember { mutableStateOf(I18n.selected) }
        SettingRow(
            title = "语言",
            subtitle = if (selected != I18n.active) "重启 Melodia 后生效" else null,
            subtitleColor = DesktopColors.Accent
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                I18n.Language.entries.forEach { language ->
                    Option(nativeName(language), selected == language) {
                        I18n.select(language)
                        selected = language
                    }
                }
            }
        }
    }

    private fun nativeName(language: I18n.Language) = when (language) {
        I18n.Language.Chinese -> String(charArrayOf('中', '文'))
        I18n.Language.English -> "English"
    }

    @Composable
    private fun Option(label: String, selected: Boolean, onClick: () -> Unit) {
        Row(
            Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(selectedColor = DesktopColors.Accent, unselectedColor = DesktopColors.TextGray)
            )
            Text(label, color = DesktopColors.TextPrimary, fontSize = 14.sp)
        }
    }
}
