package com.weinet.newvideoplayer.vault

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import kotlin.math.min

/** Streams decrypted bytes of a private-folder video ("vault://<id>"), with seeking. */
@OptIn(UnstableApi::class)
class VaultDataSource(private val context: Context) : BaseDataSource(/* isNetwork= */ false) {
    private var reader: VaultCrypto.Reader? = null
    private var uri: Uri? = null
    private var pos = 0L
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val id = dataSpec.uri.host ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        val r = try { VaultCrypto.Reader(context, VaultStore.file(context, id)) }
        catch (e: Exception) { throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) }
        if (dataSpec.position > r.length) {
            r.close()
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        reader = r
        pos = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else r.length - pos
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val n = reader!!.read(pos, buffer, offset, min(length.toLong(), remaining).toInt())
        if (n < 0) return C.RESULT_END_OF_INPUT
        pos += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        reader?.close()
        reader = null
        if (opened) { opened = false; transferEnded() }
    }
}

/** Sends "vault://" URIs to [VaultDataSource] and everything else to the default source. */
@OptIn(UnstableApi::class)
class AppDataSource(context: Context) : DataSource {
    private val default = DefaultDataSource.Factory(context).createDataSource()
    private val vault = VaultDataSource(context)
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        default.addTransferListener(transferListener)
        vault.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val ds = if (dataSpec.uri.scheme == "vault") vault else default
        active = ds
        return ds.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active!!.read(buffer, offset, length)
    override fun getUri(): Uri? = active?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()
    override fun close() { active?.close(); active = null }

    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource = AppDataSource(context.applicationContext)
    }
}
