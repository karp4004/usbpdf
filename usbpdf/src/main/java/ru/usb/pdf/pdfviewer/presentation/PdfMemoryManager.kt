package ru.usb.pdf.pdfviewer.presentation

import android.graphics.Bitmap
import android.util.LruCache

class PdfMemoryManager(
    private val maxSizeBytes: Int
) {

    private val cache = object : LruCache<String, Bitmap>(maxSizeBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.allocationByteCount
        }

        override fun entryRemoved(
            evicted: Boolean,
            key: String,
            oldValue: Bitmap,
            newValue: Bitmap?
        ) {
            // НЕЛЬЗЯ recycle() тут.
            // Bitmap может ещё использоваться Compose Image.
        }
    }

    fun get(pageIndex: Int, widthPx: Int): Bitmap? {
        val bitmap = cache.get(key(pageIndex, widthPx))
        return if (bitmap != null && !bitmap.isRecycled) bitmap else null
    }

    fun put(pageIndex: Int, widthPx: Int, bitmap: Bitmap) {
        if (!bitmap.isRecycled) {
            cache.put(key(pageIndex, widthPx), bitmap)
        }
    }

    fun clear() {
        cache.evictAll()
    }

    private fun key(pageIndex: Int, widthPx: Int): String {
        return "$pageIndex:$widthPx"
    }
}
