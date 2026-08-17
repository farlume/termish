package dev.termish.util

import androidx.compose.runtime.Composable

/** iOS 退后台即挂起、回前台自动重连（AppRoot 生命周期钩子），无需单独监听网络。 */
@Composable
actual fun observeNetworkChange(onChange: (NetworkChangeKind) -> Unit): () -> Unit = {}
