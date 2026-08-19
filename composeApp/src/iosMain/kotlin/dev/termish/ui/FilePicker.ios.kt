package dev.termish.ui

import androidx.compose.runtime.Composable

@Composable
actual fun rememberFilePicker(
    onPicked: (name: String, size: Long, readChunk: () -> ByteArray?) -> Unit,
): () -> Unit = {}
