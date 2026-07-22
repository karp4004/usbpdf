@file:Suppress("ALL")

package ru.usb.pdf.pdfviewer.domain

import android.util.Log
import java.io.ByteArrayInputStream
import java.util.zip.InflaterInputStream
import kotlin.math.abs

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
fun List<PdfLinkExtractorAnnotate.Link>.toViewerLinks(): List<PdfLink> {
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

class PdfLinkExtractorAnnotate {

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

        val coordSystem: String = "PDF_USER_SPACE"
    )

    data class Link(
        val url: String,
        val page: Int,
        val source: MutableSet<String>,
        val objIds: MutableSet<Int>,
        val geometry: MutableList<LinkGeometry> = mutableListOf()
    )

    private val TAG = "PdfExtractor"

    fun extract(pdfBytes: ByteArray): List<Link> {

        val text = String(pdfBytes, Charsets.ISO_8859_1)

        val objects = extractObjects(text)

        Log.d(TAG, "objects count = ${objects.size}")

        objects.forEach { (id, content) ->

            if (
                content.contains("/URI") ||
                content.contains("/Link") ||
                content.contains("/Annots")
            ) {
                Log.d(
                    TAG,
                    """
            ===== POSSIBLE ANNOT OBJECT $id =====
            ${content.take(1500)}
            """.trimIndent()
                )
            }
        }

        val decodedStreams = extractDecodedStreams(objects)

        Log.d(TAG, "decoded streams count = ${decodedStreams.size}")

        decodedStreams.forEach { (id, text) ->

            // игнорируем бинарные/служебные потоки
            if (
                text.contains("ICC_PROFILE", ignoreCase = true) ||
                text.contains("sRGB", ignoreCase = true) ||
                text.contains("beginbfchar") ||
                text.contains("glyf") ||
                text.contains("head")
            ) {
                return@forEach
            }

            val urls = Regex(
                """https?://[^\s<>"']+"""
            )
                .findAll(text)
                .map { it.value }
                .toList()

            if (urls.isNotEmpty()) {
                Log.d(
                    TAG,
                    "STREAM URLS object=$id $urls"
                )
            }
        }

        val cmapObjects = decodedStreams.values
            .filter {
                it.contains("begincmap")
            }

        val cmap = cmapObjects
            .flatMap {
                extractCMap(it).entries
            }
            .associate {
                it.key to it.value
            }

        val allObjects = mutableMapOf<Int, String>()

        allObjects.putAll(objects)

        decodedStreams.forEach { (id, decoded) ->
            allObjects[id * 100000] = decoded
        }

        allObjects.forEach { (id, obj) ->

            if (obj.contains("stream", ignoreCase = true)) {
                Log.d(TAG, "object $id contains stream")
            }

            if (obj.contains("FlateDecode", ignoreCase = true)) {
                Log.d(TAG, "object $id contains FlateDecode")
            }

            if (obj.contains("tvoybrok", ignoreCase = true)) {
                Log.d(TAG, "FOUND tvoybrok in object $id")
                Log.d(TAG, obj.take(3000))
            }
        }

        val pageMap = buildPageMap(allObjects)
        val annotToPageMap = buildAnnotToPageMap(allObjects, pageMap)
        val actionToAnnotMap = buildActionToAnnotMap(allObjects)

        Log.d(TAG, "ANNOT MAP $annotToPageMap")
        Log.d(TAG, "ACTION MAP $actionToAnnotMap")

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

        for ((objId, content) in allObjects) {

            val page =
                annotToPageMap[objId]
                    ?: pageMap[objId]
                    ?: -1

            val rectRaw = rectRegex.find(content)?.groupValues?.getOrNull(1)
            val rectParsed = rectRaw?.let { parseRect(it) }

            fun attach(url: String, type: String, forcedPage: Int = page) {

                val key = norm(url)

                if (rectParsed != null) {

                    addGeometry(
                        key, LinkGeometry(
                            page = forcedPage,
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
                            page = forcedPage,
                            rawRect = rectRaw,
                            confidence = 0.6f,
                            type = "PDF_NO_RECT_$type",
                            coordSystem = "PDF_CANONICAL_PARTIAL"
                        )
                    )
                }
            }

            val actionRef = Regex(
                """/A\s+(\d+)\s+0\s+R"""
            )
                .find(content)
                ?.groupValues
                ?.get(1)


            if (actionRef != null) {

                val actionObject = allObjects[actionRef.toInt()]

                val rawUri = actionObject
                    ?.let {
                        Regex(
                            """/URI\s*<([0-9A-Fa-f]+)>"""
                        )
                            .find(it)
                            ?.groupValues
                            ?.get(1)
                    }

                Log.d(
                    "PDF_PAGE",
                    "OBJ=$objId page=$page annot=${annotToPageMap[objId]} actionAnnot=${actionToAnnotMap[objId]}"
                )

                if (rawUri != null) {

                    val url = decodePdfHexString(rawUri)

                    // страница берется от annotation, а не от action
                    val annotId =
                        annotToPageMap[objId]
                            ?: actionToAnnotMap[objId]

                    val realPage =
                        when {
                            annotToPageMap.containsKey(objId) ->
                                annotToPageMap[objId]!!

                            actionToAnnotMap.containsKey(objId) ->
                                annotToPageMap[actionToAnnotMap[objId]] ?: page

                            else ->
                                page
                        }

                    addLink(
                        url,
                        realPage,
                        "ACTION",
                        objId
                    )

                    attach(
                        url,
                        "HEX_URI_ACTION",
                        realPage
                    )
                }
            }

            Regex(
                """/URI\s*(\((.*?)\)|<([^>]+)>)""",
                RegexOption.DOT_MATCHES_ALL
            )
                .findAll(content)
                .forEach {

                    val raw = it.groupValues.getOrNull(2)
                        ?: it.groupValues.getOrNull(3)

                    val url = raw?.let { value ->

                        if (value.matches(Regex("[0-9A-Fa-f]+"))) {
                            decodePdfHexString(value)
                        } else {
                            value
                        }
                    }

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

    private fun extractDecodedStreams(
        objects: Map<Int, String>
    ): Map<Int, String> {

        val result = mutableMapOf<Int, String>()

        for ((id, obj) in objects) {

            if (!obj.contains("FlateDecode")) {
                continue
            }

            val stream = Regex(
                """stream\s*(.*?)\s*endstream""",
                RegexOption.DOT_MATCHES_ALL
            )
                .find(obj)
                ?.groupValues
                ?.getOrNull(1)
                ?: continue


            try {

                val decoded = InflaterInputStream(
                    ByteArrayInputStream(
                        stream.toByteArray(Charsets.ISO_8859_1)
                    )
                )
                    .bufferedReader(Charsets.ISO_8859_1)
                    .readText()

                Log.d(
                    TAG,
                    "decoded object=$id preview=${decoded.take(500)}"
                )

                if (decoded.contains("http", ignoreCase = true) ||
                    decoded.contains("URI", ignoreCase = true)
                ) {
                    Log.d(
                        TAG,
                        "POSSIBLE LINK DATA object=$id"
                    )

                    Log.d(
                        TAG,
                        decoded.take(3000)
                    )
                }

                Log.d(
                    TAG,
                    "decoded stream object=$id size=${decoded.length}"
                )


                if (decoded.contains("http", ignoreCase = true)) {
                    Log.d(
                        TAG,
                        "FOUND http in decoded object=$id"
                    )

                    Log.d(
                        TAG,
                        decoded.take(2000)
                    )
                }


                result[id] = decoded


            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Failed inflate object=$id",
                    e
                )
            }
        }

        return result
    }

    private fun extractCMap(
        text: String
    ): Map<String, Char> {

        val result = mutableMapOf<String, Char>()

        val regex = Regex(
            """<([0-9A-Fa-f]+)>\s+<([0-9A-Fa-f]+)>"""
        )

        regex.findAll(text).forEach {

            val from = it.groupValues[1]
            val to = it.groupValues[2]

            if (to.length == 4) {

                val code = to.toInt(16)

                result[from] = code.toChar()

            }
        }

        return result
    }

    private fun buildAnnotToPageMap(
        objects: Map<Int, String>,
        pageMap: Map<Int, Int>
    ): Map<Int, Int> {

        val result = mutableMapOf<Int, Int>()

        val refRegex = Regex(
            """(\d+)\s+\d+\s+R"""
        )


        for ((pageObjId, pageIndex) in pageMap) {

            val pageObject =
                objects[pageObjId]
                    ?: continue


            val annotsRef = Regex(
                """/Annots\s+(\d+)\s+\d+\s+R"""
            )
                .find(pageObject)
                ?.groupValues
                ?.get(1)


            if (annotsRef != null) {

                val arrayObject =
                    objects[annotsRef.toInt()]
                        ?: continue


                refRegex.findAll(arrayObject)
                    .forEach {

                        val annotId =
                            it.groupValues[1].toInt()


                        result[annotId] = pageIndex

                        Log.d(
                            "PDF_PAGE",
                            "ANNOT $annotId -> PAGE $pageIndex"
                        )
                    }

            } else {

                val inline = Regex(
                    """/Annots\s*\[(.*?)\]""",
                    RegexOption.DOT_MATCHES_ALL
                )
                    .find(pageObject)
                    ?.groupValues
                    ?.get(1)


                inline?.let {

                    refRegex.findAll(it)
                        .forEach { m ->

                            val annotId =
                                m.groupValues[1].toInt()


                            result[annotId] = pageIndex

                            Log.d(
                                "PDF_PAGE",
                                "ANNOT $annotId -> PAGE $pageIndex"
                            )
                        }
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

        val result = linkedMapOf<Int, String>()

        regex.findAll(text).forEach {
            result[it.groupValues[1].toInt()] = it.groupValues[2]
        }

        return result
    }

    private fun buildPageMap(
        objects: Map<Int, String>
    ): Map<Int, Int> {

        val result = mutableMapOf<Int, Int>()

        val pagesRoot = objects.entries
            .firstOrNull {
                it.value.contains("/Type /Pages")
            }
            ?.key ?: return result


        fun walkPages(
            objId: Int,
            pageIndex: IntArray
        ) {

            val obj = objects[objId] ?: return


            val kidsMatch = Regex(
                """/Kids\s*\[(.*?)\]""",
                RegexOption.DOT_MATCHES_ALL
            )
                .find(obj)


            if (kidsMatch != null) {

                val refs = Regex(
                    """(\d+)\s+\d+\s+R"""
                )
                    .findAll(kidsMatch.groupValues[1])


                refs.forEach {
                    walkPages(
                        it.groupValues[1].toInt(),
                        pageIndex
                    )
                }

                return
            }


            if (obj.contains("/Type /Page")) {

                result[objId] = pageIndex[0]

                logPage(
                    "PAGE object=$objId index=${pageIndex[0]}"
                )

                pageIndex[0]++
            }
        }


        walkPages(
            pagesRoot,
            intArrayOf(0)
        )


        return result
    }
}

private fun decodePdfHexString(value: String): String {

    return value
        .chunked(2)
        .map {
            it.toInt(16).toChar()
        }
        .joinToString("")
}

private fun buildActionToAnnotMap(
    objects: Map<Int, String>
): Map<Int, Int> {

    val result = mutableMapOf<Int, Int>()

    val actionRegex = Regex(
        """/A\s+(\d+)\s+\d+\s+R"""
    )

    for ((annotId, content) in objects) {

        if (
            !content.contains("/Type /Annot") &&
            !content.contains("/Subtype /Link")
        ) {
            continue
        }

        val actionId = actionRegex
            .find(content)
            ?.groupValues
            ?.get(1)
            ?.toInt()
            ?: continue

        result[actionId] = annotId
    }

    return result
}
