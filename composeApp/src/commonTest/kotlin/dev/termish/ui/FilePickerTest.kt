package dev.termish.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class FilePickerTest {
    @Test
    fun duplicateSourceIsRejectedAndClosed() {
        var duplicateCloseCount = 0
        val original = pickedFile("content://docs/report")
        val duplicate = pickedFile("content://docs/report") { duplicateCloseCount++ }

        val result = appendUniquePickedFile(listOf(original), duplicate)

        assertEquals(1, result.size)
        assertSame(original, result.single())
        assertEquals(1, duplicateCloseCount)
    }

    @Test
    fun differentSourcesWithSameMetadataAreKept() {
        val first = pickedFile("content://one/report")
        val second = pickedFile("content://two/report")

        val result = appendUniquePickedFile(listOf(first), second)

        assertEquals(listOf(first, second), result)
    }

    @Test
    fun removingSourceClosesAndRemovesIt() {
        var closeCount = 0
        val removed = pickedFile("content://docs/report") { closeCount++ }

        val result = removePickedFile(listOf(removed), removed)

        assertEquals(emptyList(), result)
        assertEquals(1, closeCount)
    }

    private fun pickedFile(
        sourceId: String,
        close: () -> Unit = {},
    ): PickedFile =
        PickedFile(
            sourceId = sourceId,
            name = "report.txt",
            size = 42L,
            readChunk = { null },
            close = close,
        )
}
