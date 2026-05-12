package ru.usb.pdf.pdfviewer.presentation

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.usb.pdf.pdfviewer.domain.PdfLink
import ru.usb.pdf.pdfviewer.domain.PdfPageSize
import ru.usb.pdf.pdfviewer.domain.PdfRect
import kotlin.math.roundToInt

@Composable
fun PdfPage(
    modifier: Modifier = Modifier,
    renderer: PdfDocumentRenderer,
    pageIndex: Int,
    memoryManager: PdfMemoryManager,
    lodScale: Float,
    links: List<PdfLink>,
    onLinkClick: (PdfLink) -> Unit
) {
    var bitmap by remember(pageIndex) { mutableStateOf<Bitmap?>(null) }
    var pageSize by remember(pageIndex) { mutableStateOf<PdfPageSize?>(null) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.White)
            .pointerInput(pageIndex, links, pageSize) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val up = waitForUpOrCancellation()

                    if (up != null) {
                        val tap = up.position

                        val size = pageSize ?: return@awaitEachGesture
                        if (links.isEmpty()) return@awaitEachGesture

                        val viewWidth = this.size.width.toFloat()
                        val viewHeight = this.size.height.toFloat()

                        if (viewWidth <= 0f || viewHeight <= 0f) {
                            return@awaitEachGesture
                        }

                        val pdfX = tap.x / viewWidth * size.width
                        val pdfY = size.height - (tap.y / viewHeight * size.height)

                        val hit = links.firstOrNull { link ->
                            link.rect.containsPdfPoint(pdfX, pdfY)
                        }

                        if (hit != null) {
                            onLinkClick(hit)
                        }
                    }
                }
            }
    ) {
        val widthPx = with(LocalDensity.current) {
            maxWidth.roundToPx()
        }

        val safeLodScale = remember(lodScale) {
            lodScale.coerceAtLeast(1f).coerceAtMost(3f)
        }

        val renderWidthPx = remember(widthPx, safeLodScale) {
            if (widthPx <= 0) {
                0
            } else {
                (widthPx * safeLodScale)
                    .roundToInt()
                    .coerceAtLeast(widthPx)
                    .coerceAtMost(widthPx * 3)
            }
        }

        LaunchedEffect(pageIndex) {
            pageSize = withContext(Dispatchers.IO) {
                renderer.getPageSize(pageIndex)
            }
        }

        LaunchedEffect(pageIndex, renderWidthPx) {
            if (renderWidthPx <= 0) return@LaunchedEffect

            val cached = memoryManager.get(pageIndex, renderWidthPx)

            if (cached != null && !cached.isRecycled) {
                bitmap = cached
                return@LaunchedEffect
            }

            runCatching {
                withContext(Dispatchers.IO) {
                    renderer.renderPage(pageIndex, renderWidthPx)
                }
            }.onSuccess { rendered ->
                memoryManager.put(pageIndex, renderWidthPx, rendered)
                bitmap = rendered
            }
        }

        bitmap?.let { safeBitmap ->
            if (!safeBitmap.isRecycled) {
                Image(
                    bitmap = safeBitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private fun PdfRect.containsPdfPoint(
    x: Float,
    y: Float
): Boolean {
    val left = minOf(x1, x2)
    val right = maxOf(x1, x2)
    val bottom = minOf(y1, y2)
    val top = maxOf(y1, y2)

    return x in left..right && y in bottom..top
}
