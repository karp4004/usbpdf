package ru.usb.pdf.pdfviewer.presentation

import androidx.compose.ui.Modifier

sealed class PdfScrollMode {
    object Vertical : PdfScrollMode()
    class VerticalPager(val modifier: Modifier) : PdfScrollMode()
    class HorizontalPager(val modifier: Modifier) : PdfScrollMode()
}
