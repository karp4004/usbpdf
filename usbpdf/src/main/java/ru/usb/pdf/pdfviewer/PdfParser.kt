@file:Suppress("ALL")

package ru.usb.pdf.pdfviewer.domain

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.abs

// ===================== MODELS =====================

data class PdfLink(
    val uri: String,
    val page: Int,
    val rect: PdfRect,
    val source: Source
) {
    enum class Source { ANNOT, TEXT_FALLBACK }
}

data class PdfRect(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float
)

data class PdfPageSize(
    val width: Int,
    val height: Int
)

fun List<PdfLinkExtractor.Link>.toViewerLinks(): List<PdfLink> {
    return flatMap { link ->
        link.geometry.mapNotNull { geo ->
            val x1 = geo.x1 ?: return@mapNotNull null
            val y1 = geo.y1 ?: return@mapNotNull null
            val x2 = geo.x2 ?: return@mapNotNull null
            val y2 = geo.y2 ?: return@mapNotNull null

            PdfLink(
                uri = link.url,
                page = geo.page,
                rect = PdfRect(x1, y1, x2, y2),
                source = if (geo.type.contains("ANNOT")) {
                    PdfLink.Source.ANNOT
                } else {
                    PdfLink.Source.TEXT_FALLBACK
                }
            )
        }
    }
}

object PdfLoader {

    fun loadFromAssets(context: Context, fileName: String): ByteArray {
        val assetManager = context.assets

        assetManager.open(fileName).use { input ->
            val buffer = ByteArray(8 * 1024)
            val output = ByteArrayOutputStream()

            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
            }

            return output.toByteArray()
        }
    }
}

//📐 Правильное название системы координат
//
// Твоя текущая система — это:
//
// PDF User Space coordinates (default user space, untransformed)
//
// Можно коротко:
//
//✅ Основной термин
// PDF User Space
// 📌 Полное строгое определение
//
// Твои координаты:
//
// в единицах PDF (points, 1/72 inch)
// origin = bottom-left
// система страницы (Page coordinate system)
// до применения:
// CTM (Current Transformation Matrix)
// rotation
// viewport scaling
//💡 Как это назвать в коде (рекомендую)
//
// Текущий вариант:
//
// coordSystem = "PDF_CANONICAL"
//
// Лучше заменить на:
//
// coordSystem = "PDF_USER_SPACE"
//
// или максимально явно:
//
// coordSystem = "PDF_PAGE_USER_SPACE"
//🚀 Как использовать это в будущем чате
//
// Ты можешь просто написать:
//
// "У меня координаты ссылок в PDF User Space (bottom-left origin, untransformed), как проецировать на Android Canvas?"
//
// И я сразу пойму:
//
// ✔ нужно инвертировать Y
// ✔ учитывать MediaBox
// ✔ применить scale
// ✔ учесть rotation
class PdfLinkExtractor {

    // =====================================================
    // LOGGING
    // =====================================================

    private fun log(tag: String, msg: String) {
        Log.d(tag, msg)
    }

    private fun logGeom(msg: String) {
        Log.d("PDF_GEOMETRY", msg)
    }

    private fun logPage(msg: String) {
        Log.d("PDF_PAGE", msg)
    }

    private fun logTrace(msg: String) {
        Log.d("PDF_TRACE", msg)
    }

    private fun logSummary(msg: String) {
        Log.d("PDF_SUMMARY", msg)
    }

    // =====================================================
    // Geometry model
    // =====================================================

    data class LinkGeometry(
        val page: Int,

        val x1: Float? = null,
        val y1: Float? = null,
        val x2: Float? = null,
        val y2: Float? = null,

        val rawRect: String? = null,
        val rawQuadPoints: String? = null,

        val bx1: Float? = null,
        val by1: Float? = null,
        val bx2: Float? = null,
        val by2: Float? = null,

        val confidence: Float = 1.0f,
        val type: String = "UNKNOWN",

        val coordSystem: String = "PDF_CANONICAL"
    )

    data class Link(
        val url: String,
        val page: Int,
        val source: MutableSet<String>,
        val objIds: MutableSet<Int>,
        val geometry: MutableList<LinkGeometry> = mutableListOf()
    )

    fun extract(pdfBytes: ByteArray): List<Link> {

        val text = String(pdfBytes, Charsets.ISO_8859_1)

        val objects = extractObjects(text)
        val pageMap = buildPageMap(objects)
        val annotToPageMap = buildAnnotToPageMap(objects, pageMap)

        val linkMap = mutableMapOf<String, Link>()

        fun norm(url: String): String =
            url.trim()
                .removeSurrounding("<", ">")
                .removeSurrounding("(", ")")

        // =====================================================
        // 🔥 NEW: geometry dedupe
        // =====================================================
        fun isSameRect(a: LinkGeometry, b: LinkGeometry): Boolean {
            val eps = 0.5f

            val ax1 = a.x1 ?: return false
            val ay1 = a.y1 ?: return false
            val ax2 = a.x2 ?: return false
            val ay2 = a.y2 ?: return false

            val bx1 = b.x1 ?: return false
            val by1 = b.y1 ?: return false
            val bx2 = b.x2 ?: return false
            val by2 = b.y2 ?: return false

            return abs(ax1 - bx1) < eps &&
                    abs(ay1 - by1) < eps &&
                    abs(ax2 - bx2) < eps &&
                    abs(ay2 - by2) < eps
        }

        fun shouldAddGeometry(existing: MutableList<LinkGeometry>, new: LinkGeometry): Boolean {

            val hasRect = existing.any {
                it.x1 != null && it.y1 != null && it.x2 != null && it.y2 != null
            }

            // ❌ блок TEXT если есть нормальный rect
            if (hasRect && new.type.contains("TEXT_INFERRED")) {
                return false
            }

            // 🔥 проверка на дубликат rect
            if (new.x1 != null) {

                val duplicateIndex = existing.indexOfFirst { isSameRect(it, new) }

                if (duplicateIndex != -1) {

                    val existingGeo = existing[duplicateIndex]

                    // 🔥 ПРИОРИТЕТ ANNOT > ACTION
                    val shouldReplace =
                        new.type.contains("ANNOT") &&
                                existingGeo.type.contains("ACTION")

                    if (shouldReplace) {
                        existing[duplicateIndex] = new
                        logTrace("[REPLACE GEO ACTION→ANNOT]")
                    }

                    return false
                }
            }

            return true
        }

        fun addGeometry(key: String, geo: LinkGeometry) {

            val link = linkMap[key] ?: return
            val existing = link.geometry

            if (!shouldAddGeometry(existing, geo)) {
                logTrace("[DROP GEO] key=$key type=${geo.type}")
                return
            }

            link.geometry.add(geo)

            logGeom(
                """
ATTACH
key=$key
page=${geo.page}
type=${geo.type}
rect=(${geo.x1},${geo.y1},${geo.x2},${geo.y2})
conf=${geo.confidence}
coord=${geo.coordSystem}
""".trimIndent()
            )
        }

        fun addLink(url: String, page: Int, source: String, objId: Int) {

            val key = norm(url)
            if (key.isBlank()) return

            val existing = linkMap[key]

            if (existing != null) {
                existing.source.add(source)
                existing.objIds.add(objId) // 🔥
                logTrace("[MERGE] url=$key source=$source obj=$objId page=$page")
            } else {
                linkMap[key] = Link(
                    url = key,
                    page = page,
                    source = mutableSetOf(source),
                    objIds = mutableSetOf(objId), // 🔥
                )
                logTrace("[ADD] url=$key source=$source obj=$objId page=$page")
            }
        }

        val rectRegex = Regex("""/Rect\s*\[(.*?)\]""")

        fun parseRect(raw: String): List<Float>? =
            raw.trim()
                .split(Regex("\\s+"))
                .mapNotNull { it.toFloatOrNull() }
                .takeIf { it.size == 4 }

        fun inferTextBox(url: String, page: Int): LinkGeometry =
            LinkGeometry(
                page = page,
                confidence = 0.25f,
                type = "TEXT_INFERRED_PDF_CANDIDATE",
                coordSystem = "PDF_CANONICAL_UNRESOLVED"
            )

        for ((objId, content) in objects) {

            val page =
                pageMap[objId]
                    ?: annotToPageMap[objId]
                    ?: -1

            val rectRaw = rectRegex.find(content)?.groupValues?.getOrNull(1)
            val rectParsed = rectRaw?.let { parseRect(it) }

            fun attach(url: String, type: String) {

                val key = norm(url)

                if (rectParsed != null) {

                    addGeometry(
                        key, LinkGeometry(
                            page = page,
                            x1 = rectParsed[0],
                            y1 = rectParsed[1],
                            x2 = rectParsed[2],
                            y2 = rectParsed[3],
                            rawRect = rectRaw,
                            confidence = 1.0f,
                            type = "PDF_RECT_$type"
                        )
                    )

                } else {

                    addGeometry(
                        key, LinkGeometry(
                            page = page,
                            rawRect = rectRaw,
                            confidence = 0.6f,
                            type = "PDF_NO_RECT_$type",
                            coordSystem = "PDF_CANONICAL_PARTIAL"
                        )
                    )
                }
            }

            Regex(
                """/A\s*<<.*?/S\s*/URI.*?/URI\s*(\((.*?)\)|<([^>]+)>)""",
                RegexOption.DOT_MATCHES_ALL
            )
                .findAll(content)
                .forEach {

                    val url = it.groupValues.getOrNull(2)
                        ?: it.groupValues.getOrNull(3)

                    if (!url.isNullOrBlank()) {
                        addLink(url, page, "ACTION", objId)
                        attach(url, "ACTION")
                    }
                }

            Regex(
                """/URI\s*(\((.*?)\)|<([^>]+)>)""",
                RegexOption.DOT_MATCHES_ALL
            )
                .findAll(content)
                .forEach {

                    val url = it.groupValues.getOrNull(2)
                        ?: it.groupValues.getOrNull(3)

                    if (!url.isNullOrBlank()) {
                        addLink(url, page, "ANNOT", objId)
                        attach(url, "ANNOT")
                    }
                }

            Regex("""https?://[^\s<>"'\]\)]+""")
                .findAll(content)
                .forEach {

                    val url = it.value
                    addLink(url, page, "FALLBACK", objId)
                    addGeometry(norm(url), inferTextBox(url, page))
                }
        }

        return linkMap.values.toList()
    }

    private fun buildAnnotToPageMap(
        objects: Map<Int, String>,
        pageMap: Map<Int, Int>
    ): Map<Int, Int> {

        val result = mutableMapOf<Int, Int>()
        val annotRegex = Regex("""(\d+)\s+\d+\s+R""")

        for ((objId, content) in objects) {

            val pageIndex = pageMap[objId] ?: continue

            if (content.contains("/Annots")) {
                annotRegex.findAll(content).forEach {
                    val annotId = it.groupValues[1].toInt()
                    result[annotId] = pageIndex
                }
            }
        }

        return result
    }

    private fun extractObjects(text: String): Map<Int, String> {

        val regex = Regex(
            """(\d+)\s+\d+\s+obj(.*?)endobj""",
            RegexOption.DOT_MATCHES_ALL
        )

        return regex.findAll(text).associate {
            it.groupValues[1].toInt() to it.groupValues[2]
        }
    }

    private fun buildPageMap(objects: Map<Int, String>): Map<Int, Int> {

        val map = mutableMapOf<Int, Int>()
        var pageIndex = 0

        for ((id, content) in objects) {

            val isPage =
                content.contains("/Type") &&
                        content.contains("/Page") &&
                        (
                                content.contains("/MediaBox") ||
                                        content.contains("/Contents") ||
                                        content.contains("/Resources")
                                )

            if (isPage) {
                map[id] = pageIndex++
            }
        }

        if (map.isEmpty()) {
            var i = 0
            objects.keys.forEach { map[it] = i++ }
        }

        return map
    }
}
