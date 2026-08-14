package dev.mssh.ssh

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual fun ioDispatcher(): CoroutineDispatcher = Dispatchers.Default
