package dev.mssh.ssh

import kotlinx.coroutines.CoroutineDispatcher

/** IO dispatcher for blocking socket work. */
expect fun ioDispatcher(): CoroutineDispatcher
