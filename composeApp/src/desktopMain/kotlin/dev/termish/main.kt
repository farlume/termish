package dev.termish

import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Taskbar
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/** 桌面端应用图标：复用 Android 的品牌图标（ic_launcher → icon.png）。 */
private fun loadAppIcon(): BufferedImage? = try {
    val stream = object {}.javaClass.getResourceAsStream("/icon.png") ?: return null
    stream.use { ImageIO.read(it) }
} catch (_: Exception) {
    null
}

fun main() {
    // macOS Dock/菜单栏应用名：必须在 AWT 初始化前设置，否则显示 "java"
    System.setProperty("apple.awt.application.name", "Termish")
    app()
}

private fun app() = application {
    val icon = loadAppIcon()
    icon?.let {
        // macOS Dock / Windows 任务栏图标（Java 默认图标太难看了）
        if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
            Taskbar.getTaskbar().setIconImage(it)
        }
    }
    Window(
        onCloseRequest = ::exitApplication,
        title = "Termish",
        state = rememberWindowState(width = 480.dp, height = 800.dp),
        icon = icon?.toComposeImageBitmap()?.let { BitmapPainter(it) },
    ) {
        App()
    }
}
