package dev.termish.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.termish.util.monospaceFontFamily

/**
 * 全局通用紧凑页头（终端页同款风格，约 48dp + 底部分隔线）：
 * 可选返回箭头 + 小标题 + 右侧操作区。
 *
 * @param statusBarPadding 是否自行避让状态栏；终端页外层已处理时传 false。
 */
@Composable
fun TermishHeader(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    statusBarPadding: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val s = LocalAppStrings.current
    Column(modifier.fillMaxWidth().background(containerColor)) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (statusBarPadding) Modifier.statusBarsPadding() else Modifier)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = s.navBack, tint = contentColor)
                }
            } else {
                Spacer(Modifier.width(16.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = monospaceFontFamily(),
                color = contentColor,
            )
            Spacer(Modifier.weight(1f))
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                actions()
            }
        }
        HorizontalDivider(color = contentColor.copy(alpha = 0.15f))
    }
}

/**
 * 大标题页头（iOS 风格）：顶级 tab 页使用。
 * 28sp 加粗大标题 + 充足留白，无下边框，视觉上更通透。
 */
@Composable
fun TermishLargeHeader(
    title: String,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(
        title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        fontFamily = monospaceFontFamily(),
        color = contentColor,
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
    )
}
