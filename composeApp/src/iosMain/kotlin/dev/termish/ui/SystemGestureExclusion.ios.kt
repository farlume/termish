package dev.termish.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun Modifier.excludeSystemBackGesture(): Modifier = this
