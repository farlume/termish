package dev.termish.ui

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import dev.termish.ssh.SftpReadHandle
import java.io.EOFException
import java.io.IOException

/** 连接在第一次读取时建立，多次 seek 复用；所有访问（含释放）串行化。 */
internal class SftpVideoSource(
    private val openFile: () -> SftpReadHandle,
) {
    private var handle: SftpReadHandle? = null
    private var released = false

    @Synchronized
    fun size(): Long = file().size

    @Synchronized
    fun read(
        offset: Long,
        count: Int,
    ): ByteArray = file().read(offset, count)

    private fun file(): SftpReadHandle {
        check(!released) { "Video source released" }
        return handle ?: openFile().also { handle = it }
    }

    @Synchronized
    fun release() {
        if (released) return
        released = true
        val file = handle
        handle = null
        file?.close()
    }
}

@OptIn(UnstableApi::class)
internal class SftpVideoDataSource(
    private val source: SftpVideoSource,
) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var offset = 0L
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        try {
            val size = source.size()
            if (dataSpec.position > size) throw DataSourceException(DataSourceException.POSITION_OUT_OF_RANGE)
            offset = dataSpec.position
            remaining = size - offset
            if (dataSpec.length != C.LENGTH_UNSET.toLong()) remaining = minOf(remaining, dataSpec.length)
            uri = dataSpec.uri
            opened = true
            transferStarted(dataSpec)
            return remaining
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("SFTP video open failed", e)
        }
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val bytes =
            try {
                source.read(this.offset, minOf(length.toLong(), remaining, 64L * 1024).toInt())
            } catch (e: Exception) {
                throw IOException("SFTP video read failed", e)
            }
        if (bytes.isEmpty()) throw EOFException("SFTP video changed while playing")
        bytes.copyInto(buffer, offset)
        this.offset += bytes.size
        remaining -= bytes.size
        bytesTransferred(bytes.size)
        return bytes.size
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        remaining = 0
        if (opened) {
            opened = false
            transferEnded()
        }
        // DataSource.close 也在 seek 时调用；整个播放器销毁才关闭 source。
    }
}
