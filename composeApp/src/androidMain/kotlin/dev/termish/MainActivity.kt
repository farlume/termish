package dev.termish

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 显式深色系统栏样式（浅色图标），否则系统浅色模式下图标会变深色，
        // 在深色窗口背景上完全不可见（“漆黑”）。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // 通知「重新连接」动作（singleTop：App 存活时走 onNewIntent）
        dev.termish.notify.handleNotificationIntent(intent)
        setContent {
            App()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        dev.termish.notify.handleNotificationIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        AppContext.setCurrentActivity(this)
    }

    override fun onPause() {
        if (AppContext.currentActivity === this) AppContext.setCurrentActivity(null)
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (AppContext.currentActivity === this) AppContext.setCurrentActivity(null)
    }
}
