@file:Suppress("ALL")

package ru.usb.pdf.pdfviewer.presentation

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import ru.usb.pdf.pdfviewer.domain.PdfLink
import ru.usb.pdf.pdfviewer.presentation.PdfDocumentRenderer.PdfSystemError
import java.io.IOException

// PdfViewer.kt

sealed interface UsbPdfState {
    data object Loading : UsbPdfState
    data class Ready(
        val source: PdfSource,
        val links: List<PdfLink>
    ) : UsbPdfState

    data class Error(val throwable: Throwable) : UsbPdfState
}

@Composable
fun BoxScope.PdfViewer(
    modifier: Modifier = Modifier,
    state: UsbPdfState,
    scrollMode: PdfScrollMode = PdfScrollMode.Vertical,
    minScale: Float = 1f,
    maxScale: Float = 4f,
    onLinkClick: (PdfLink) -> Unit = {},
    decorator: @Composable BoxScope.(currentPage: Int, pageCount: Int) -> Unit,
    loading: @Composable BoxScope.() -> Unit,
    error: @Composable BoxScope.() -> Unit,
    systemErrors: (e: PdfSystemError) -> Unit
) {
    when (state) {
        is UsbPdfState.Loading -> loading()
        is UsbPdfState.Error -> error()
        is UsbPdfState.Ready -> PdfViewer(
            modifier,
            state.source,
            state.links,
            scrollMode,
            minScale,
            maxScale,
            onLinkClick,
            decorator,
            systemErrors
        )
    }
}

@Composable
fun PdfViewer(
    modifier: Modifier = Modifier,
    source: PdfSource,
    links: List<PdfLink> = emptyList(),
    scrollMode: PdfScrollMode = PdfScrollMode.Vertical,
    minScale: Float = 1f,
    maxScale: Float = 4f,
    onLinkClick: (PdfLink) -> Unit = {},
    decorator: @Composable BoxScope.(currentPage: Int, pageCount: Int) -> Unit,
    systemErrors: (e: PdfSystemError) -> Unit
) {
    val context = LocalContext.current

    var renderer by remember { mutableStateOf<PdfDocumentRenderer?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }

    val memoryManager = remember {
        PdfMemoryManager(maxSizeBytes = 64 * 1024 * 1024)
    }

    val linksByPage = remember(links) {
        links.groupBy { it.page }
    }

    LaunchedEffect(Unit) {
        renderer
            ?.errorFlow
            ?.onEach { systemErrors(it) }
            ?.collect()
    }

    LaunchedEffect(source) {
        renderer?.close()
        memoryManager.clear()

        try {
            source.open(context)?.let {
                val newRenderer = PdfDocumentRenderer(it)

                renderer = newRenderer
                pageCount = newRenderer.pageCount
            }
        } catch (ex: IOException) {
            renderer?.emitError(PdfSystemError(ex, PdfSystemError.ErrorContext.OPEN_PAGE))
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            memoryManager.clear()
            renderer?.close()
        }
    }

    renderer?.let { safeRenderer ->
        ZoomablePdfContent(
            renderer = safeRenderer,
            pageCount = pageCount,
            memoryManager = memoryManager,
            linksByPage = linksByPage,
            scrollMode = scrollMode,
            minScale = minScale,
            maxScale = maxScale,
            modifier = modifier,
            onLinkClick = onLinkClick,
            decorator = decorator
        )
    }
}
