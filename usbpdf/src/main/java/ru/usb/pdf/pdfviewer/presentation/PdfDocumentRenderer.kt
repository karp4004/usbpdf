@file:Suppress("ALL")

package ru.usb.pdf.pdfviewer.presentation

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterNotNull
import ru.usb.pdf.pdfviewer.domain.PdfPageSize
import kotlin.math.roundToInt
import kotlin.math.sqrt

class PdfDocumentRenderer(
    private val openedPdf: OpenedPdf
) : AutoCloseable {

    data class PdfSystemError(
        val t: Throwable,
        val context: ErrorContext
    ) {
        enum class ErrorContext {
            OPEN_PAGE,
            CLOSE_PAGE,
            GET_PAGE_SIZE
        }
    }

    private val _errorFlow = MutableSharedFlow<PdfSystemError?>(1)
    val errorFlow = _errorFlow.filterNotNull()
    fun emitError(t: PdfSystemError) = _errorFlow.tryEmit(t)

    private companion object {
        /**
         * Максимальный размер одного bitmap страницы.
         *
         * Это именно размер пиксельных данных:
         * width * height * 4 bytes для ARGB_8888.
         */
        const val MAX_BITMAP_BYTES = 64L * 1024L * 1024L
    }

    private val renderer = PdfRenderer(openedPdf.descriptor)

    val pageCount: Int
        get() = renderer.pageCount

    @Synchronized
    fun getPageSize(pageIndex: Int): PdfPageSize {
        val page = renderer.openPage(pageIndex)

        return try {
            PdfPageSize(
                width = page.width,
                height = page.height
            )
        } finally {
            page.close()
        }
    }

    @Synchronized
    fun renderPage(
        pageIndex: Int,
        widthPx: Int
    ): Bitmap {
        val page = renderer.openPage(pageIndex)

        try {
            val safeWidthPx = calculateSafeWidth(
                pageWidth = page.width,
                pageHeight = page.height,
                requestedWidthPx = widthPx
            )

            val ratio = page.height.toFloat() / page.width.toFloat()

            val heightPx = (
                safeWidthPx * ratio
                ).roundToInt()
                .coerceAtLeast(1)

            val bitmap = Bitmap.createBitmap(
                safeWidthPx,
                heightPx,
                Bitmap.Config.ARGB_8888
            )

            page.render(
                bitmap,
                null,
                null,
                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
            )

            return bitmap
        } finally {
            page.close()
        }
    }

    private fun calculateSafeWidth(
        pageWidth: Int,
        pageHeight: Int,
        requestedWidthPx: Int
    ): Int {
        if (
            requestedWidthPx <= 0 ||
            pageWidth <= 0 ||
            pageHeight <= 0
        ) {
            return 1
        }

        val requestedHeightPx = (
            requestedWidthPx.toDouble() *
                pageHeight.toDouble() /
                pageWidth.toDouble()
            ).roundToInt().coerceAtLeast(1)

        val requestedBytes = estimateBitmapBytes(
            widthPx = requestedWidthPx,
            heightPx = requestedHeightPx
        )

        if (requestedBytes <= MAX_BITMAP_BYTES) {
            return requestedWidthPx
        }

        /*
         * Память bitmap пропорциональна площади:
         *
         * width * height
         *
         * При сохранении aspect ratio:
         *
         * memory ~ width²
         *
         * Поэтому для определения нового width
         * используется sqrt.
         */
        val scale = sqrt(
            MAX_BITMAP_BYTES.toDouble() /
                requestedBytes.toDouble()
        )

        return (
            requestedWidthPx * scale
            ).roundToInt()
            .coerceAtLeast(1)
    }

    private fun estimateBitmapBytes(
        widthPx: Int,
        heightPx: Int
    ): Long {
        return widthPx.toLong() *
            heightPx.toLong() *
            4L
    }

    override fun close() {
        try {
            renderer.close()
            openedPdf.close()
        } catch (t: Throwable) {
            emitError(PdfSystemError(t, PdfSystemError.ErrorContext.CLOSE_PAGE))
        }
    }
}
