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
import ru.usb.pdf.pdfviewer.domain.PdfLink

// PdfViewer.kt

sealed interface PdfViewerLoadingState {
    data object Loading : PdfViewerLoadingState
    data class Ready(
        val source: PdfSource,
        val links: List<PdfLink>
    ) : PdfViewerLoadingState

    data class Error(val throwable: Throwable) : PdfViewerLoadingState
}

@Composable
fun BoxScope.PdfViewer(
    modifier: Modifier = Modifier,
    state: PdfViewerLoadingState,
    scrollMode: PdfScrollMode = PdfScrollMode.Vertical,
    minScale: Float = 1f,
    maxScale: Float = 4f,
    onLinkClick: (PdfLink) -> Unit = {},
    decorator: @Composable BoxScope.(currentPage: Int, pageCount: Int) -> Unit,
    loading: @Composable BoxScope.() -> Unit,
    error: @Composable BoxScope.() -> Unit
) {
    when (state) {
        is PdfViewerLoadingState.Loading -> loading()
        is PdfViewerLoadingState.Error -> error()
        is PdfViewerLoadingState.Ready -> PdfViewer(
            modifier,
            state.source,
            state.links,
            scrollMode,
            minScale,
            maxScale,
            onLinkClick,
            decorator
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
    decorator: @Composable BoxScope.(currentPage: Int, pageCount: Int) -> Unit
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

    LaunchedEffect(source) {
        renderer?.close()
        memoryManager.clear()

        val openedPdf = source.open(context)
        val newRenderer = PdfDocumentRenderer(openedPdf)

        renderer = newRenderer
        pageCount = newRenderer.pageCount
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
