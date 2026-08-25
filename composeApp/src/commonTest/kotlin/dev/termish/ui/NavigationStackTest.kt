package dev.termish.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationStackTest {
    @Test
    fun backReturnsToActualPreviousPage() {
        val stack = NavigationStack("home")

        stack.push("terminal")
        assertEquals(NavigationDirection.FORWARD, stack.direction)
        stack.push("agent")

        assertTrue(stack.pop())
        assertEquals(NavigationDirection.BACKWARD, stack.direction)
        assertEquals("terminal", stack.current)
        assertTrue(stack.pop())
        assertEquals("home", stack.current)
        assertFalse(stack.pop())
    }

    @Test
    fun duplicateAsyncNavigationDoesNotAddDuplicatePage() {
        val stack = NavigationStack("home")

        stack.push("terminal")
        stack.push("terminal")

        assertEquals(listOf("home", "terminal"), stack.snapshot())
        assertEquals(NavigationDirection.FORWARD, stack.direction)
    }

    @Test
    fun replaceAndResetKeepAValidRoot() {
        val stack = NavigationStack("home")

        stack.push("edit")
        stack.replace("agent")
        assertEquals(listOf("home", "agent"), stack.snapshot())

        stack.reset("home")
        assertEquals(1, stack.size)
        assertEquals("home", stack.current)
        assertFalse(stack.canPop)
    }
}
