package ru.usb.pdf.pdfviewer.presentation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.usb.pdf.pdfviewer.domain.PdfLink

@Composable
fun ZoomablePdfContent(
    modifier: Modifier = Modifier,
    renderer: PdfDocumentRenderer,
    pageCount: Int,
    memoryManager: PdfMemoryManager,
    scrollMode: PdfScrollMode,
    minScale: Float,
    maxScale: Float,
    linksByPage: Map<Int, List<PdfLink>>,
    onLinkClick: (PdfLink) -> Unit,
    decorator: @Composable BoxScope.(currentPage: Int, pageCount: Int) -> Unit
) {
    val zoomLevels = remember(minScale, maxScale) {
        listOf(
            minScale,
            2f.coerceIn(minScale, maxScale),
            3f.coerceIn(minScale, maxScale)
        ).distinct()
    }

    var scale by remember { mutableFloatStateOf(minScale) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    var activeLodScale by remember { mutableFloatStateOf(1f) }

    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var contentSize by remember { mutableStateOf(IntSize.Zero) }

    val scope = rememberCoroutineScope()

    fun lodForScale(value: Float): Float {
        if (value <= minScale + 0.05f) return 1f

        return when {
            value <= 1.75f -> 1f
            else -> 2f
        }
    }

    LaunchedEffect(scale) {
        delay(180)
        activeLodScale = lodForScale(scale)
    }

    fun nextZoomLevel(current: Float): Float {
        return zoomLevels.firstOrNull { it > current + 0.05f } ?: minScale
    }

    fun clampedOffset(
        rawOffset: Offset,
        targetScale: Float
    ): Offset {
        if (targetScale <= minScale + 0.05f) return Offset.Zero
        if (viewportSize.width <= 0 || viewportSize.height <= 0) return Offset.Zero
        if (contentSize.width <= 0 || contentSize.height <= 0) return Offset.Zero

        val scaledWidth = contentSize.width * targetScale
        val scaledHeight = contentSize.height * targetScale

        val maxX = ((scaledWidth - viewportSize.width) / 2f).coerceAtLeast(0f)
        val maxY = ((scaledHeight - viewportSize.height) / 2f).coerceAtLeast(0f)

        return Offset(
            x = rawOffset.x.coerceIn(-maxX, maxX),
            y = rawOffset.y.coerceIn(-maxY, maxY)
        )
    }

    fun offsetForZoom(
        centroid: Offset,
        oldScale: Float,
        newScale: Float,
        currentOffset: Offset
    ): Offset {
        if (newScale <= minScale + 0.05f) return Offset.Zero

        val viewportCenter = Offset(
            viewportSize.width / 2f,
            viewportSize.height / 2f
        )

        val centroidFromCenter = centroid - viewportCenter

        val rawOffset =
            (currentOffset - centroidFromCenter) *
                    (newScale / oldScale) +
                    centroidFromCenter

        return clampedOffset(rawOffset, newScale)
    }

    suspend fun animateZoomAround(
        centroid: Offset,
        targetScale: Float
    ) {
        val oldScale = scale
        val newScale = targetScale.coerceIn(minScale, maxScale)

        if (oldScale == newScale) return

        val startOffset = offset
        val endOffset = offsetForZoom(
            centroid = centroid,
            oldScale = oldScale,
            newScale = newScale,
            currentOffset = startOffset
        )

        coroutineScope {
            launch {
                animate(
                    initialValue = oldScale,
                    targetValue = newScale,
                    animationSpec = tween(
                        durationMillis = 220,
                        easing = FastOutSlowInEasing
                    )
                ) { value, _ ->
                    scale = value
                }
            }

            launch {
                animate(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = 220,
                        easing = FastOutSlowInEasing
                    )
                ) { fraction, _ ->
                    offset = Offset(
                        x = startOffset.x + (endOffset.x - startOffset.x) * fraction,
                        y = startOffset.y + (endOffset.y - startOffset.y) * fraction
                    )
                }
            }
        }

        scale = newScale
        offset = endOffset
        activeLodScale = lodForScale(newScale)
    }

    val lazyListState = rememberLazyListState()
    val pagerState = rememberPagerState { pageCount }

    val currentPage by remember(scrollMode) {
        derivedStateOf {
            when (scrollMode) {
                PdfScrollMode.Vertical -> lazyListState.firstVisibleItemIndex
                is PdfScrollMode.HorizontalPager -> pagerState.currentPage
                is PdfScrollMode.VerticalPager -> pagerState.currentPage
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewportSize = it }
            .pointerInput(scrollMode, viewportSize, contentSize) {
                detectTapGestures(
                    onDoubleTap = { tapOffset ->
                        scope.launch {
                            animateZoomAround(
                                centroid = tapOffset,
                                targetScale = nextZoomLevel(scale)
                            )
                        }
                    }
                )
            }
            .pointerInput(scrollMode, viewportSize, contentSize) {
                detectTransformGestures(
                    panZoomLock = true
                ) { centroid, pan, zoom, _ ->
                    val oldScale = scale
                    val newScale = (oldScale * zoom).coerceIn(minScale, maxScale)

                    val zoomedOffset = offsetForZoom(
                        centroid = centroid,
                        oldScale = oldScale,
                        newScale = newScale,
                        currentOffset = offset
                    )

                    val newOffset =
                        if (newScale <= minScale + 0.05f) {
                            Offset.Zero
                        } else {
                            clampedOffset(zoomedOffset + pan, newScale)
                        }

                    scale = newScale
                    offset = newOffset
                }
            }
    ) {
        val isAtMinScale = scale <= minScale + 0.05f

        val zoomModifier = Modifier
            .fillMaxSize()
            .onSizeChanged { contentSize = it }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
                transformOrigin = TransformOrigin.Center
            }

        when (scrollMode) {
            PdfScrollMode.Vertical ->
                LazyColumn(
                    state = lazyListState,
                    modifier = zoomModifier,
                    contentPadding = PaddingValues(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(pageCount) { pageIndex ->
                        PdfPage(
                            renderer = renderer,
                            pageIndex = pageIndex,
                            memoryManager = memoryManager,
                            lodScale = activeLodScale,
                            links = linksByPage[pageIndex].orEmpty(),
                            onLinkClick = onLinkClick
                        )
                    }
                }

            is PdfScrollMode.HorizontalPager ->
                Box(
                    modifier = scrollMode.modifier
                        .fillMaxSize()
                        .clipToBounds()
                ) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = zoomModifier,
                        userScrollEnabled = isAtMinScale,
                        beyondViewportPageCount = 1
                    ) { pageIndex ->
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            PdfPage(
                                modifier = Modifier.fillMaxWidth(),
                                renderer = renderer,
                                pageIndex = pageIndex,
                                memoryManager = memoryManager,
                                lodScale = activeLodScale,
                                links = linksByPage[pageIndex].orEmpty(),
                                onLinkClick = onLinkClick
                            )
                        }
                    }
                }

            is PdfScrollMode.VerticalPager ->
                Box(
                    modifier = scrollMode.modifier
                        .fillMaxSize()
                        .clipToBounds()
                ) {
                    VerticalPager(
                        state = pagerState,
                        modifier = zoomModifier,
                        userScrollEnabled = isAtMinScale,
                        beyondViewportPageCount = 1
                    ) { pageIndex ->
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            PdfPage(
                                modifier = Modifier.fillMaxWidth(),
                                renderer = renderer,
                                pageIndex = pageIndex,
                                memoryManager = memoryManager,
                                lodScale = activeLodScale,
                                links = linksByPage[pageIndex].orEmpty(),
                                onLinkClick = onLinkClick
                            )
                        }
                    }
                }
        }

        if (pageCount > 0) {
            decorator(currentPage, pageCount)
        }
    }
}
