package dev.mssh

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "MSSH",
        state = rememberWindowState(width = 480.dp, height = 800.dp),
    ) {
        App()
    }
}
