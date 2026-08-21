package dev.termish.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.termish.ui.theme.TerminalTheme

/**
 * 画布右下角功能菜单入口（可扩展）：
 * - 待机：品牌绿圆钮 + + 图标；点击展开功能菜单（从按钮向上滑出）
 * - 菜单项：胶囊按钮（图标 + 标签），点击执行并收起菜单；后续功能往里加即可
 * - 录音中整体隐藏（录音态由中间大按钮接管，见 BigVoiceStopButton）
 */
@Composable
fun CanvasToolMenu(
    /** 菜单是否展开。 */
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    /** 菜单项（自下而上排列，第一个最靠近按钮）。 */
    items: List<CanvasMenuAction>,
    theme: TerminalTheme,
    modifier: Modifier = Modifier,
) {
    val brand = MaterialTheme.colorScheme.primary
    val onBrand = MaterialTheme.colorScheme.onPrimary
    // 展开箭头旋转：+ → ×
    val rotation by animateFloatAsState(
        targetValue = if (menuOpen) 45f else 0f,
        animationSpec = tween(160),
        label = "menuRotate",
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 功能菜单：自下而上滑出
        AnimatedVisibility(
            visible = menuOpen,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(tween(160)),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(tween(120)),
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items.forEach { action ->
                    CanvasMenuActionItem(action, theme)
                }
            }
        }

        // 主按钮
        Box(
            Modifier
                .size(44.dp)
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(brand)
                .clickable { onMenuOpenChange(!menuOpen) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                tint = onBrand,
                modifier = Modifier.size(22.dp).rotate(rotation),
            )
        }
    }
}

/** 单个菜单动作：胶囊按钮（图标 + 标签）。 */
@Composable
private fun CanvasMenuActionItem(action: CanvasMenuAction, theme: TerminalTheme) {
    Row(
        Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
            .clickable(onClick = action.onClick)
            .padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(action.badge ?: MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                action.icon,
                contentDescription = null,
                tint = if (action.badge != null) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            action.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 菜单动作定义（可扩展：后续功能往里加）。 */
data class CanvasMenuAction(
    val id: String,
    val label: String,
    val icon: ImageVector,
    /** 图标底徽颜色；null = 品牌绿。 */
    val badge: Color? = null,
    val onClick: () -> Unit,
)

/** 语音菜单动作图标（复用）。 */
val VoiceMenuIcon: ImageVector get() = Icons.Filled.Mic
