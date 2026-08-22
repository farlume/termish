package dev.termish.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 全局统一 Snackbar 宿主：**品牌视觉**（深锌灰圆角卡片 + 白字 + 品牌绿 action），
 * 暗/亮主题下一致，不随页面背景漂移。全项目提示（连接失败 / 上传下载完成 /
 * 语音输入反馈 / 复制粘贴等）统一走这里，避免各处默认样式不齐。
 */
@Composable
fun TermishSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(hostState, modifier) { data ->
        BrandSnackbar(data)
    }
}

@Composable
private fun BrandSnackbar(data: SnackbarData) {
    val brand = MaterialTheme.colorScheme.primary
    Snackbar(
        snackbarData = data,
        shape = RoundedCornerShape(14.dp),
        containerColor = Color(0xFF23262D).copy(alpha = 0.98f),
        contentColor = Color(0xFFE4E4E7),
        actionColor = brand,
    )
}
