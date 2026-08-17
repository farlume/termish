package dev.termish.util

import androidx.compose.runtime.Composable

@Composable
actual fun observeNetworkChange(onChange: (NetworkChangeKind) -> Unit): () -> Unit = {}
