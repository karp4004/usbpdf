package com.example.mypdf

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mypdf.ui.theme.MyPdfTheme
import kotlinx.coroutines.delay
import ru.usb.pdf.pdfviewer.domain.PdfLinkExtractor
import ru.usb.pdf.pdfviewer.domain.PdfLoader
import ru.usb.pdf.pdfviewer.domain.toViewerLinks
import ru.usb.pdf.pdfviewer.presentation.ByteArrayPdfSource
import ru.usb.pdf.pdfviewer.presentation.PdfScrollMode
import ru.usb.pdf.pdfviewer.presentation.PdfViewer
import ru.usb.pdf.pdfviewer.presentation.PdfViewerLoadingState

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyPdfTheme {
                PdfScreen()
            }
        }
    }

    @Composable
    fun PdfScreen() {
        var state by remember { mutableStateOf<PdfViewerLoadingState>(PdfViewerLoadingState.Loading) }

        LaunchedEffect("dep_komfort_plus_230326_9xl8rxss.pdf") {
            val pdfBytes =
                PdfLoader.loadFromAssets(this@MainActivity, "dep_komfort_plus_230326_9xl8rxss.pdf")

            val links = PdfLinkExtractor()
                .extract(pdfBytes)
                .toViewerLinks()

            links.forEach {
                println("$it")
            }

            delay(2000)

            state = PdfViewerLoadingState.Ready(ByteArrayPdfSource(pdfBytes), links)
        }


        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
        ) {
            Text(
                modifier = Modifier.height(52.dp),
                text = "toolbar"
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                PdfViewer(
                    state = state,
                    scrollMode = PdfScrollMode.HorizontalPager(
                        Modifier
                            .padding(
                                horizontal = 16.dp,
                                vertical = 0.dp
                            )
                            .background(Color.White)
                    ),
                    modifier = Modifier.background(Color.Transparent),
                    onLinkClick = { link ->
                        Log.d("PDF_LINK", "clicked ${link.uri}")
                    },
                    decorator = { currentPage, pageCount ->
                        PageIndicator(currentPage, pageCount)
                    },
                    loading = {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    },
                    error = {
                        Text(
                            modifier = Modifier.align(Alignment.Center),
                            text = "Error"
                        )
                    }
                )
            }

            Button(
                modifier = Modifier.height(56.dp),
                onClick = {}
            ) {
                Text(text = "Button")
            }
        }
    }

    @Composable
    private fun BoxScope.PageIndicator(
        currentPage: Int,
        pageCount: Int
    ) {
        Box(
            modifier = Modifier
                .padding(vertical = 16.dp)
                .background(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(999.dp)
                )
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .align(Alignment.BottomCenter)
        ) {
            BasicText(
                text = "$currentPage / $pageCount",
                style = TextStyle(
                    color = Color.White,
                    fontSize = 14.sp
                )
            )
        }
    }

}
