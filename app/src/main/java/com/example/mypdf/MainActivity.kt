package com.example.mypdf

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.mypdf.ui.theme.MyPdfTheme
import ru.usb.pdf.pdfviewer.domain.PdfLinkExtractor
import ru.usb.pdf.pdfviewer.domain.PdfLoader
import ru.usb.pdf.pdfviewer.domain.toViewerLinks
import ru.usb.pdf.pdfviewer.presentation.ByteArrayPdfSource
import ru.usb.pdf.pdfviewer.presentation.PdfScrollMode
import ru.usb.pdf.pdfviewer.presentation.PdfViewer

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyPdfTheme {
                PdfScreen()
            }
        }

        val pdfBytes = PdfLoader.loadFromAssets(this, "dep_komfort_plus_230326_9xl8rxss.pdf")
        val links = PdfLinkExtractor().extract(pdfBytes)
        links.forEach {
            println("$it")
        }
    }

    @Composable
    fun PdfScreen() {
        val pdfBytes = PdfLoader.loadFromAssets(this, "dep_komfort_plus_230326_9xl8rxss.pdf")

        val links = PdfLinkExtractor()
            .extract(pdfBytes)
            .toViewerLinks()

        PdfViewer(
            source = ByteArrayPdfSource(pdfBytes),
            links = links,
            scrollMode = PdfScrollMode.HorizontalPager,
            modifier = Modifier.fillMaxSize(),
            onLinkClick = { link ->
                Log.d("PDF_LINK", "clicked ${link.uri}")
            }
        )
    }
}
