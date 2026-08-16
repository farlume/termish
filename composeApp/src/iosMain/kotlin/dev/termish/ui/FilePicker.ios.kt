package dev.termish.ui

import androidx.compose.runtime.Composable

@Composable
actual fun rememberFilePicker(onPicked: (name: String, content: ByteArray) -> Unit): () -> Unit = {}
