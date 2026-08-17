package dev.termish.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.statusBarsPadding
import dev.termish.ui.theme.TerminalTheme
import dev.termish.ui.theme.TerminalThemes
import dev.termish.util.TerminalFont
import dev.termish.util.monospaceFontFamily

/**
 * 终端主题与字体二级页面（Termius 风格）：
 * 顶部字体下拉框，下方主题双列卡片（真实配色预览：背景 + 16 色 + 文字样本）。
 */
@Composable
fun SettingsTerminalScreen(
    currentThemeIndex: Int,
    currentFontId: String,
    onChangeTheme: (Int) -> Unit,
    onChangeFont: (String) -> Unit,
    onBack: () -> Unit,
) {
    val s = LocalAppStrings.current
    var fontMenuOpen by remember { mutableStateOf(false) }
    val selectedFont = TerminalFont.byId(currentFontId)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 页头：返回 + 标题
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = s.navBack)
            }
            Text(
                s.settingsTerminalPageTitle,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = monospaceFontFamily(),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        // 字体区固定，主题网格自身滚动（LazyVerticalGrid 不能嵌在 verticalScroll 内）
        Column(Modifier.fillMaxSize()) {
            // 字体选择下拉框
            Text(
                s.settingsTerminalFontLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 6.dp),
            )
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                        .clickable { fontMenuOpen = true }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        selectedFont.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = if (selectedFont == TerminalFont.JETBRAINS) monospaceFontFamily() else FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
                DropdownMenu(expanded = fontMenuOpen, onDismissRequest = { fontMenuOpen = false }) {
                    TerminalFont.entries.forEach { font ->
                        DropdownMenuItem(
                            text = { Text(font.label) },
                            onClick = {
                                fontMenuOpen = false
                                onChangeFont(font.id)
                            },
                        )
                    }
                }
            }

            // 主题双列网格
            Text(
                s.settingsTerminalPalette,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(TerminalThemes.ALL, key = { it.name }) { theme ->
                    ThemeCard(
                        theme = theme,
                        selected = TerminalThemes.ALL.indexOf(theme) == currentThemeIndex,
                        onClick = { onChangeTheme(TerminalThemes.ALL.indexOf(theme)) },
                    )
                }
            }
        }
    }
}

/** 主题预览卡片：真实配色（背景/前景/16 色块）+ 名称 + 选中高亮。 */
@Composable
private fun ThemeCard(theme: TerminalTheme, selected: Boolean, onClick: () -> Unit) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(if (selected) 2.dp else 1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        // 预览区：背景 + 前景文字样本 + 16 色块
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(theme.background())
                .padding(10.dp),
        ) {
            Text(
                "Aa 123",
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                color = theme.foreground(),
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                theme.ansi.forEach { c ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(c)),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            theme.name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
        )
    }
}
