package dev.termish.ui

import android.app.Activity
import android.graphics.Rect
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView

/** Android：把本区域从窗口的系统手势排除列表中声明（随布局位置实时更新）。 */
@Composable
actual fun Modifier.excludeSystemBackGesture(): Modifier = composed {
    val view = LocalView.current
    this.onGloballyPositioned { coords ->
        val window = (view.context as? Activity)?.window ?: return@onGloballyPositioned
        val b = coords.boundsInWindow()
        window.setSystemGestureExclusionRects(
            listOf(Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())),
        )
    }
}
