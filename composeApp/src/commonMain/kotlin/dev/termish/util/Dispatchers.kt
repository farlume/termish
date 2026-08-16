package dev.termish.util

import kotlinx.coroutines.CoroutineDispatcher

/** 阻塞 socket/SSH 工作的调度器。 */
expect fun ioDispatcher(): CoroutineDispatcher
