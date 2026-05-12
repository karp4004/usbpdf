package ru.usb.pdf.pdfviewer.presentation

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import ru.usb.pdf.pdfviewer.domain.PdfPageSize
import kotlin.math.roundToInt

// PdfDocumentRenderer.kt

class PdfDocumentRenderer(
    private val openedPdf: OpenedPdf
) : AutoCloseable {

    private val renderer = PdfRenderer(openedPdf.descriptor)

    val pageCount: Int
        get() = renderer.pageCount

    @Synchronized
    fun getPageSize(pageIndex: Int): PdfPageSize {
        val page = renderer.openPage(pageIndex)
        val size = PdfPageSize(
            width = page.width,
            height = page.height
        )
        page.close()
        return size
    }

    @Synchronized
    fun renderPage(
        pageIndex: Int,
        widthPx: Int
    ): Bitmap {
        val page = renderer.openPage(pageIndex)

        val ratio = page.height.toFloat() / page.width.toFloat()
        val heightPx = (widthPx * ratio).roundToInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(
            widthPx.coerceAtLeast(1),
            heightPx,
            Bitmap.Config.ARGB_8888
        )

        page.render(
            bitmap,
            null,
            null,
            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
        )

        page.close()
        return bitmap
    }

    override fun close() {
        renderer.close()
        openedPdf.close()
    }
}
