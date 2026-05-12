package ru.usb.pdf.pdfviewer.domain

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.MotionEvent
import android.widget.ImageView
import ru.usb.pdf.pdfviewer.domain.PdfLinkExtractor.Link
import kotlin.math.min

class PdfLinksHandler {

    fun mapTouchToPdf(
        touchX: Float,
        touchY: Float,
        viewWidth: Float,
        viewHeight: Float,
        pageIndex: Int,
        parcelFileDescriptor: ParcelFileDescriptor
    ): Pair<Float, Float> {

        val renderer = PdfRenderer(parcelFileDescriptor)
        val page = renderer.openPage(pageIndex)

        val pdfWidth = page.width.toFloat()
        val pdfHeight = page.height.toFloat()

        val scale = min(
            viewWidth / pdfWidth,
            viewHeight / pdfHeight
        )

        val offsetX = (viewWidth - pdfWidth * scale) / 2f
        val offsetY = (viewHeight - pdfHeight * scale) / 2f

        val xPdf = (touchX - offsetX) / scale
        val yPdf = pdfHeight - (touchY - offsetY) / scale

        return xPdf to yPdf
    }

    fun isInside(rect: PdfRect, x: Float, y: Float): Boolean {
        return x >= rect.x1 &&
                x <= rect.x2 &&
                y >= rect.y1 &&
                y <= rect.y2
    }

    fun handleTouch(
        imageView: ImageView,
        event: MotionEvent,
        pageIndex: Int,
        parcelFileDescriptor: ParcelFileDescriptor,
        links: List<Link>
    ) {
        if (event.action == MotionEvent.ACTION_DOWN) {
            imageView.parent.requestDisallowInterceptTouchEvent(true)
        }

        if (event.action == MotionEvent.ACTION_UP) {

            val touchX = event.x
            val touchY = event.y

            val pdfWidth = imageView.drawable.intrinsicWidth.toFloat()
            val pdfHeight = imageView.drawable.intrinsicHeight.toFloat()

            val viewWidth = imageView.width.toFloat()
            val viewHeight = imageView.height.toFloat()

            val (xPdf, yPdf) = mapTouchToPdf(
                touchX,
                touchY,
                viewWidth,
                viewHeight,
                pageIndex,
                parcelFileDescriptor
            )

            val hit = links.firstOrNull { link ->
                link.geometry.any { g ->
                    g.x1 != null &&
                            xPdf >= g.x1!! &&
                            xPdf <= g.x2!! &&
                            yPdf >= g.y1!! &&
                            yPdf <= g.y2!!
                }
            }

            if (hit != null) {
                println("OPEN: ${hit.url}")
            }
        }
    }
}
