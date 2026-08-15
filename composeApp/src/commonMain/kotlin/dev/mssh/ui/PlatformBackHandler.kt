package dev.mssh.ui

import androidx.compose.runtime.Composable

/** 跨平台系统返回处理（Android 返回手势/返回键等）。 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
