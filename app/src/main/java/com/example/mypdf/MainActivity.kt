package com.example.mypdf

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import ru.usb.pdf.pdfviewer.domain.PdfLoader
import ru.usb.pdf.pdfviewer.domain.PdfParserAnnotate
import ru.usb.pdf.pdfviewer.domain.toViewerLinks
import ru.usb.pdf.pdfviewer.presentation.FilePdfSource
import ru.usb.pdf.pdfviewer.presentation.PdfScrollMode
import ru.usb.pdf.pdfviewer.presentation.PdfViewer
import ru.usb.pdf.pdfviewer.presentation.PdfViewerLoadingState
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

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
        var assetFileName by remember { mutableStateOf("") }
        if (assetFileName.isBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
            ) {
                listOf(
                    "agreement.pdf",
                    "corrupt_links.pdf",
                    "dep_dohod_010426_r4mvie3j.pdf",
                    "dep_kluchevoy_plus_230326_noqsifxj.pdf",
                    "dep_komfort_plus_230326_9xl8rxss.pdf",
                    "dep_vigodny_010426_hvxyyzey.pdf",
                    "pdf_link_types_demo.pdf"
                ).forEach {
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        modifier = Modifier.clickable {
                            assetFileName = it
                        },
                        text = it
                    )
                }
            }

            return
        }

        var state by remember { mutableStateOf<PdfViewerLoadingState>(PdfViewerLoadingState.Loading) }

        val uri = getOrCopyAssetToCache(this, assetFileName)
        LaunchedEffect(uri) {
            val pdfBytes =
                PdfLoader.loadFromAssets(this@MainActivity, assetFileName)

            val links =
//                PdfLinkExtractor()
//                .extract(pdfBytes)
//                .toViewerLinks() +
                PdfParserAnnotate()
                        .extract(pdfBytes)
                        .toViewerLinks()

            links.forEach {
                println("$it")
            }

            state = PdfViewerLoadingState.Ready(FilePdfSource(uri), links)
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
                    scrollMode = PdfScrollMode.VerticalPager(
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

    @Throws(
        FileNotFoundException::class,
        IOException::class,
        SecurityException::class
    )
    fun getOrCopyAssetToCache(
        context: Context,
        assetFileName: String
    ): String {
        require(assetFileName.isNotBlank()) {
            "Asset file name is blank"
        }

        val outFile = File(context.cacheDir, assetFileName)

        if (!outFile.exists()) {
            try {
                context.assets.open(assetFileName).use { input ->
                    outFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: Exception) {
                outFile.delete()
                throw e
            }
        }

        if (!outFile.exists()) {
            throw FileNotFoundException(
                "Failed to create cache file: ${outFile.absolutePath}"
            )
        }

        if (!outFile.canRead()) {
            throw SecurityException(
                "Cache file is not readable: ${outFile.absolutePath}"
            )
        }

        return outFile.absolutePath
    }
}
