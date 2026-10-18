package dev.termish.ui

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import dev.termish.ssh.SftpReadHandle
import java.io.EOFException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SftpVideoDataSourceTest {
    private class File(
        private val length: Long = 256L * 1024,
    ) : SftpReadHandle {
        override val size = length
        val reads = mutableListOf<Pair<Long, Int>>()
        var closes = 0
        var truncate = false

        override fun read(
            offset: Long,
            maxBytes: Int,
        ): ByteArray {
            reads += offset to maxBytes
            if (truncate) return byteArrayOf()
            // 模拟服务器短读，确保调用者按实际读到的字节数推进。
            return ByteArray(minOf(maxBytes.toLong(), 4096, length - offset).toInt()) { ((offset + it) % 251).toByte() }
        }

        override fun close() {
            closes++
        }
    }

    private fun spec(
        offset: Long = 0,
        length: Long = C.LENGTH_UNSET.toLong(),
    ) = DataSpec
        .Builder()
        .setUri(Uri.parse("sftp-video://file/test.mp4"))
        .setPosition(offset)
        .setLength(length)
        .build()

    @Test
    fun seeksReuseHandleAndReadOnlyRequestedRange() {
        val file = File()
        var opens = 0
        val lease =
            SftpVideoSource {
                opens++
                file
            }
        val source = SftpVideoDataSource(lease)
        assertEquals(11, source.open(spec(1234, 11)))
        val buffer = ByteArray(32)
        assertEquals(11, source.read(buffer, 3, 29))
        assertContentEquals(ByteArray(11) { ((1234 + it) % 251).toByte() }, buffer.copyOfRange(3, 14))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, buffer.size))
        source.close()
        assertEquals(0, file.closes)
        assertEquals(7, source.open(spec(file.size - 7, 100)))
        assertEquals(7, source.read(buffer, 0, buffer.size))
        source.close()
        assertEquals(1, opens)
        assertEquals(listOf(1234L to 11, (file.size - 7) to 7), file.reads)
        lease.release()
        lease.release()
        assertEquals(1, file.closes)
        assertFailsWith<IllegalStateException> { lease.size() }
    }

    @Test
    fun longOffsetsShortReadsAndBoundedRequests() {
        val file = File(5L * 1024 * 1024 * 1024)
        val lease = SftpVideoSource { file }
        val source = SftpVideoDataSource(lease)
        val start = 4L * 1024 * 1024 * 1024 + 13
        assertEquals(file.size - start, source.open(spec(start)))
        val buffer = ByteArray(128 * 1024)
        assertEquals(0, source.read(buffer, 0, 0))
        assertEquals(4096, source.read(buffer, 0, buffer.size))
        assertEquals(4096, source.read(buffer, 0, buffer.size))
        assertEquals(listOf(start to 65536, (start + 4096) to 65536), file.reads)
        source.close()
        lease.release()
    }

    @Test
    fun eofInvalidRangeAndTruncatedFile() {
        val file = File()
        val lease = SftpVideoSource { file }
        val source = SftpVideoDataSource(lease)
        assertEquals(0, source.open(spec(file.size)))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(ByteArray(1), 0, 1))
        source.close()
        assertFailsWith<DataSourceException> { source.open(spec(file.size + 1)) }
        source.open(spec())
        file.truncate = true
        assertFailsWith<EOFException> { source.read(ByteArray(1), 0, 1) }
        source.close()
        lease.release()
    }

    @Test
    fun disposingBeforeFirstReadDoesNotConnect() {
        var opened = false
        val lease =
            SftpVideoSource {
                opened = true
                File()
            }
        lease.release()
        assertFailsWith<IllegalStateException> { lease.size() }
        assertTrue(!opened)
    }
}
