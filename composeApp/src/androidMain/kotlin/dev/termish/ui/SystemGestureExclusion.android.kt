package dev.termish.ui

import android.app.Activity
import android.graphics.Rect
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView

/** Android：把本区域从窗口的系统手势排除列表中声明（随布局位置实时更新）。 */
@Composable
actual fun Modifier.excludeSystemBackGesture(): Modifier =
    composed {
        val view = LocalView.current
        this.onGloballyPositioned { coords ->
            val window = (view.context as? Activity)?.window ?: return@onGloballyPositioned
            // setSystemGestureExclusionRects 是 API 29+（minSdk 26）：
            // 低版本无预测性返回手势，无需排除（lint NewApi 要求条件执行）
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@onGloballyPositioned
            val b = coords.boundsInWindow()
            window.setSystemGestureExclusionRects(
                listOf(Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())),
            )
        }
    }
