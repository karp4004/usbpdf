@file:Suppress("ALL")

package ru.usb.pdf.pdfviewer.presentation

import android.content.Context
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// PdfSource.kt

interface PdfSource {
    suspend fun open(context: Context): OpenedPdf?
}

class OpenedPdf(
    val file: File,
    val descriptor: ParcelFileDescriptor,
    private val deleteOnClose: Boolean = true
) : AutoCloseable {
    override fun close() {
        descriptor.close()
        if (deleteOnClose) file.delete()
    }
}

class ByteArrayPdfSource(
    private val bytes: ByteArray
) : PdfSource {
    override suspend fun open(context: Context): OpenedPdf? =
        withContext(Dispatchers.IO) {
            val file = File.createTempFile("pdf_", ".pdf", context.cacheDir)
            file.writeBytes(bytes)

            OpenedPdf(
                file = file,
                descriptor = ParcelFileDescriptor.open(
                    file,
                    ParcelFileDescriptor.MODE_READ_ONLY
                )
            )
        }
}

class FilePdfSource(
    private val path: String
) : PdfSource {

    override suspend fun open(context: Context): OpenedPdf? =
        withContext(Dispatchers.IO) {
            val file = File(path)

            if (file.exists()) {
                OpenedPdf(
                    file = file,
                    descriptor = ParcelFileDescriptor.open(
                        file,
                        ParcelFileDescriptor.MODE_READ_ONLY
                    ),
                    deleteOnClose = false
                )
            } else {
                null
            }
        }
}
