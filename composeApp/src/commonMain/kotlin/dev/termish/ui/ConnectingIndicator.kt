package dev.termish.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.termish.ui.theme.StatusColors

/**
 * 「连接中」统一居中指示器（终端页首连/重连、SFTP 首连/重连共用）：
 * spinner + 文案胶囊，出现/退出淡入淡出——完成时平滑消失不残留浮层。
 *
 * - [bottomOverlay]：画布底部被悬浮工具栏盖住的高度（含导航条/键盘
 *   padding）。指示器在「可见画布」内居中（上移遮盖高度的一半），
 *   不会因整屏取中而视觉重心下坠；SFTP 页无底栏，保持 0 即整页居中。
 * - 颜色：默认取应用主题表面（SFTP 浅色页）；终端页深色画布必须传
 *   终端主题的背景/前景，否则应用浅色主题时会在画布上浮出亮色胶囊。
 *   spinner/描边统一走语义色 [StatusColors.Warning]。
 */
@Composable
fun ConnectingIndicator(
    visible: Boolean,
    text: String,
    modifier: Modifier = Modifier,
    bottomOverlay: Dp = 0.dp,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(220)),
        modifier = modifier.padding(bottom = bottomOverlay / 2),
    ) {
        Row(
            Modifier
                .background(containerColor.copy(alpha = 0.92f), RoundedCornerShape(12.dp))
                .border(1.dp, StatusColors.Warning.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = StatusColors.Warning,
            )
            Text(
                text,
                color = contentColor,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** 状态点旁的小 spinner（连接页会话行「正在建立」可视化；首页头像同语义）。 */
@Composable
fun StatusSpinner(modifier: Modifier = Modifier, color: Color = StatusColors.Warning) {
    CircularProgressIndicator(
        modifier = modifier,
        strokeWidth = 2.dp,
        color = color,
    )
}
