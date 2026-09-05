package dev.termish.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

@OptIn(ExperimentalCoroutinesApi::class)
class IdleResourcePoolTest {
    @Test
    fun reacquireBeforeTimeoutKeepsResource() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val closed = mutableListOf<String>()
        val pool = IdleResourcePool<String, String, String>(scope, 60_000, closed::add)
        val original = pool.acquire("host", "credentials") { "connection" }

        pool.release("host")
        scope.advanceTimeBy(59_999)
        val reused = pool.acquire("host", "credentials") { "replacement" }
        scope.advanceTimeBy(1)
        scope.runCurrent()

        assertSame(original, reused)
        assertEquals(emptyList(), closed)
    }

    @Test
    fun idleTimeoutAndSignatureChangeCloseResource() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val closed = mutableListOf<String>()
        val pool = IdleResourcePool<String, String, String>(scope, 60_000, closed::add)
        pool.acquire("host", "old") { "old connection" }

        pool.acquire("host", "new") { "new connection" }
        assertEquals(listOf("old connection"), closed)

        pool.release("host")
        scope.advanceTimeBy(60_000)
        scope.runCurrent()
        assertEquals(listOf("old connection", "new connection"), closed)
    }

    @Test
    fun busyResourceStaysAliveUntilNextIdleWindow() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val closed = mutableListOf<String>()
        var busy = true
        val pool =
            IdleResourcePool<String, String, String>(
                scope,
                60_000,
                closed::add,
                canCloseResource = { !busy },
            )
        pool.acquire("host", "credentials") { "connection" }

        pool.release("host")
        scope.advanceTimeBy(60_000)
        scope.runCurrent()
        assertEquals(emptyList(), closed)

        busy = false
        scope.advanceTimeBy(60_000)
        scope.runCurrent()
        assertEquals(listOf("connection"), closed)
    }

    @Test
    fun removeClosesImmediately() {
        val closed = mutableListOf<String>()
        val pool = IdleResourcePool<String, String, String>(TestScope(), 60_000, closed::add)
        pool.acquire("host", "credentials") { "connection" }

        pool.remove("host")

        assertEquals(listOf("connection"), closed)
    }
}
