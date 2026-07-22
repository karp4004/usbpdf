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
fun List<PdfParserAnnotate.Link>.toViewerLinks(): List<PdfLink> {
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

class PdfParserAnnotate {

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

        val totalStartedNs = System.nanoTime()
        var stageStartedNs = totalStartedNs
        fun perf(stage: String, details: String = "") {
            val now = System.nanoTime()
            val stageMs = (now - stageStartedNs) / 1_000_000.0
            val totalMs = (now - totalStartedNs) / 1_000_000.0
            Log.d(
                "PDF_PERF",
                "stage=$stage stageMs=${"%.2f".format(java.util.Locale.US, stageMs)} " +
                        "totalMs=${"%.2f".format(java.util.Locale.US, totalMs)} $details"
            )
            stageStartedNs = now
        }

        val text = String(pdfBytes, Charsets.ISO_8859_1)
        perf("bytes_to_text", "pdfBytes=${pdfBytes.size} chars=${text.length}")

        val objects = extractObjects(text)
        perf("extract_objects", "objects=${objects.size}")

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

        // Decoding every Flate stream is very expensive and unnecessary.
        // Embedded fonts, images, ICC profiles and xref/object streams can be
        // hundreds of kilobytes or megabytes. Links only need page Contents
        // and ToUnicode CMaps.
        val requiredStreamIds = collectRequiredStreamIds(objects)
        perf(
            "collect_required_streams",
            "required=${requiredStreamIds.size} ids=$requiredStreamIds"
        )
        val decodedStreams = extractDecodedStreams(objects, requiredStreamIds)
        perf(
            "decode_required_streams",
            "decoded=${decodedStreams.size} decodedChars=${decodedStreams.values.sumOf { it.length }}"
        )

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
        perf("build_cmap", "cmapStreams=${cmapObjects.size} cmapEntries=${cmap.size}")

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
        perf(
            "build_page_annotation_maps",
            "pages=${pageMap.size} annotPages=${annotToPageMap.size} actions=${actionToAnnotMap.size}"
        )

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

            // Do not treat URLs from XMP metadata, schemas, ICC profiles, etc.
            // as visible page links. Visible text is handled below by
            // extractTextFallbackLinks(), with a real page and rectangle.
            if (page >= 0) {
                Regex("""https?://[^\s<>"'\]\)]+""")
                    .findAll(content)
                    .forEach {
                        val url = it.value
                        addLink(url, page, "FALLBACK", objId)
                        addGeometry(norm(url), inferTextBox(url, page))
                    }
            }
        }

        // Text printed on a page is not necessarily a PDF /Link annotation.
        // Decode text-showing operators (Tj/TJ) through ToUnicode and create
        // fallback links with real PDF User Space rectangles.
        perf("scan_pdf_annotations", "links=${linkMap.size}")

        val textFallbackLinks = extractTextFallbackLinks(
            objects = objects,
            decodedStreams = decodedStreams,
            pageMap = pageMap,
            cmap = cmap
        )
        perf("extract_text_fallback", "textLinks=${textFallbackLinks.size}")

        textFallbackLinks.forEach { found ->
            addLink(found.url, found.page, "TEXT_FALLBACK", found.streamObjId)
            addGeometry(
                norm(found.url),
                LinkGeometry(
                    page = found.page,
                    x1 = found.x1,
                    y1 = found.y1,
                    x2 = found.x2,
                    y2 = found.y2,
                    confidence = 0.90f,
                    type = "TEXT_FALLBACK",
                    coordSystem = "PDF_USER_SPACE"
                )
            )
        }

        val result = linkMap.values.toList()
        val totalMs = (System.nanoTime() - totalStartedNs) / 1_000_000.0
        Log.d(
            "PDF_PERF",
            "DONE totalMs=${"%.2f".format(java.util.Locale.US, totalMs)} " +
                    "resultLinks=${result.size} geometries=${result.sumOf { it.geometry.size }}"
        )
        return result
    }

    private data class TextFallbackLink(
        val url: String,
        val page: Int,
        val streamObjId: Int,
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float
    )

    private data class FontMetrics(
        val firstChar: Int,
        val widths: List<Float>
    ) {
        fun width(code: Int): Float = widths.getOrNull(code - firstChar) ?: 500f
    }

    private data class Glyph(
        val char: Char,
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val streamObjId: Int
    )

    private data class PdfNameToken(val value: String)
    private data class PdfWordToken(val value: String)

    /**
     * Finds visible URLs, even when the PDF has no /Annots at all.
     * Supported beginnings: http://, https://, www. and web.
     */
    private fun extractTextFallbackLinks(
        objects: Map<Int, String>,
        decodedStreams: Map<Int, String>,
        pageMap: Map<Int, Int>,
        cmap: Map<String, Char>
    ): List<TextFallbackLink> {
        Log.d(
            "PDF_TEXT_LINK",
            "start pages=$pageMap cmap=${cmap.size} decodedStreams=${decodedStreams.keys}"
        )
        val fontRefs = mutableMapOf<String, Int>()
        val fontRefRegex = Regex("""/(\w+)\s+(\d+)\s+\d+\s+R""")

        objects.values.forEach { obj ->
            Regex("""/Font\s*<<(.*?)>>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(obj)
                .forEach { fontDict ->
                    fontRefRegex.findAll(fontDict.groupValues[1]).forEach { ref ->
                        fontRefs[ref.groupValues[1]] = ref.groupValues[2].toInt()
                    }
                }

            // Ghostscript commonly stores /Font as an indirect dictionary.
            // Its object then contains only entries such as /R12 12 0 R.
            Regex("""/(R\d+)\s+(\d+)\s+\d+\s+R""")
                .findAll(obj)
                .forEach { ref -> fontRefs[ref.groupValues[1]] = ref.groupValues[2].toInt() }
        }

        val metrics = fontRefs.mapValues { (_, objId) ->
            val fontObject = objects[objId].orEmpty()
            val first = Regex("""/FirstChar\s+(\d+)""")
                .find(fontObject)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val widths = Regex("""/Widths\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
                .find(fontObject)?.groupValues?.get(1)
                ?.let { raw ->
                    Regex("""[-+]?(?:\d+\.?\d*|\.\d+)""")
                        .findAll(raw).map { it.value.toFloat() }.toList()
                }.orEmpty()
            FontMetrics(first, widths)
        }
        Log.d(
            "PDF_TEXT_LINK",
            "fonts=$fontRefs metrics=${metrics.mapValues { it.value.widths.size }}"
        )

        val contentToPage = mutableMapOf<Int, Int>()
        pageMap.forEach { (pageObjId, pageIndex) ->
            val pageObj = objects[pageObjId].orEmpty()
            val contents = Regex(
                """/Contents\s*(?:\[(.*?)]|(\d+)\s+\d+\s+R)""",
                RegexOption.DOT_MATCHES_ALL
            ).find(pageObj)
            if (contents != null) {
                val refsText = contents.groupValues[1].ifBlank { contents.groupValues[2] }
                Regex("""(\d+)(?:\s+\d+\s+R)?""").findAll(refsText).forEach {
                    val id = it.groupValues[1].toInt()
                    contentToPage[id] = pageIndex

                    // /Contents may point to an indirect array object.
                    objects[id]?.let { contentsObject ->
                        Regex("""(\d+)\s+\d+\s+R""").findAll(contentsObject).forEach { child ->
                            contentToPage[child.groupValues[1].toInt()] = pageIndex
                        }
                    }
                }
            }
        }
        Log.d("PDF_TEXT_LINK", "contentToPage=$contentToPage")

        val pageGlyphs = mutableMapOf<Int, MutableList<Glyph>>()
        val codeByteLengths = cmap.keys
            .map { (it.length + 1) / 2 }
            .distinct()
            .sortedDescending()
        contentToPage.forEach { (streamId, page) ->
            val stream = decodedStreams[streamId] ?: return@forEach
            val glyphs = pageGlyphs.getOrPut(page) { mutableListOf() }
            Log.d("PDF_TEXT_LINK", "parse stream=$streamId page=$page size=${stream.length}")

            var fontName = ""
            var fontSize = 12f
            var lineX = 0f
            var lineY = 0f
            var textX = 0f
            var textY = 0f
            val operands = mutableListOf<Any>()

            fun show(bytes: ByteArray) {
                val fm = metrics[fontName]
                var offset = 0

                while (offset < bytes.size) {
                    var matchedLength = 0
                    var matchedCode = 0
                    var char: Char? = null

                    for (byteLength in codeByteLengths) {
                        if (offset + byteLength > bytes.size) continue
                        val key = buildString(byteLength * 2) {
                            for (index in offset until offset + byteLength) {
                                append(
                                    (bytes[index].toInt() and 0xff).toString(16).padStart(2, '0')
                                )
                            }
                        }.uppercase()
                        val decoded = cmap[key] ?: continue
                        matchedLength = byteLength
                        matchedCode = key.toIntOrNull(16) ?: 0
                        char = decoded
                        break
                    }

                    // Unknown code: always move forward, otherwise malformed
                    // content could stall the parser indefinitely.
                    if (char == null) {
                        offset++
                        continue
                    }

                    val code = matchedCode
                    val advance = (fm?.width(code) ?: 500f) * fontSize / 1000f
                    glyphs.add(
                        Glyph(
                            char = char,
                            x1 = textX,
                            y1 = textY - fontSize * 0.25f,
                            x2 = textX + advance,
                            y2 = textY + fontSize * 0.85f,
                            streamObjId = streamId
                        )
                    )
                    textX += advance
                    offset += matchedLength
                }
            }

            tokenizePdfContent(stream.toByteArray(Charsets.ISO_8859_1)).forEach { token ->
                if (token !is PdfWordToken) {
                    operands.add(token)
                    return@forEach
                }

                fun number(indexFromEnd: Int): Float? =
                    (operands.getOrNull(operands.size - indexFromEnd) as? Number)?.toFloat()

                when (token.value) {
                    "Tf" -> {
                        fontName =
                            (operands.getOrNull(operands.size - 2) as? PdfNameToken)?.value.orEmpty()
                        fontSize = number(1) ?: fontSize
                    }

                    "Tm" -> {
                        lineX = number(2) ?: lineX
                        lineY = number(1) ?: lineY
                        textX = lineX
                        textY = lineY
                    }

                    "Td", "TD" -> {
                        lineX += number(2) ?: 0f
                        lineY += number(1) ?: 0f
                        textX = lineX
                        textY = lineY
                    }

                    "Tj", "'", "\"" -> (operands.lastOrNull() as? ByteArray)?.let(::show)
                    "TJ" -> {
                        @Suppress("UNCHECKED_CAST")
                        (operands.lastOrNull() as? List<Any>)?.forEach { item ->
                            when (item) {
                                is ByteArray -> show(item)
                                is Number -> textX -= item.toFloat() * fontSize / 1000f
                            }
                        }
                    }
                }
                operands.clear()
            }
        }

        // Trailing punctuation is deliberately excluded from a visible URL.
        val urlRegex = Regex(
            """(?i)(?:https?://|www\.|web\.)[^\s<>\[\]{}\"']*[^\s<>\[\]{}\"'.,;:!?)]"""
        )
        val result = mutableListOf<TextFallbackLink>()

        pageGlyphs.forEach { (page, glyphs) ->
            Log.d("PDF_TEXT_LINK", "page=$page glyphs=${glyphs.size}")
            // Glyphs from separate TJ/Tj operators on the same baseline form one line.
            glyphs.groupBy { kotlin.math.round(it.y1 * 2f) / 2f }.values.forEach { rawLine ->
                val line = rawLine.sortedBy { it.x1 }
                val text = line.joinToString("") { it.char.toString() }
                if (text.contains("http", true) || text.contains(
                        "www.",
                        true
                    ) || text.contains("web.", true)
                ) {
                    Log.d("PDF_TEXT_LINK", "candidate page=$page text=$text")
                }
                urlRegex.findAll(text).forEach { match ->
                    val selected = line.subList(match.range.first, match.range.last + 1)
                    val displayed = match.value
                    val clickable = when {
                        displayed.startsWith("http://", true) || displayed.startsWith(
                            "https://",
                            true
                        ) -> displayed

                        else -> "https://$displayed"
                    }
                    result.add(
                        TextFallbackLink(
                        url = clickable,
                        page = page,
                        streamObjId = selected.first().streamObjId,
                        x1 = selected.minOf { it.x1 },
                        y1 = selected.minOf { it.y1 },
                        x2 = selected.maxOf { it.x2 },
                        y2 = selected.maxOf { it.y2 }
                    ))
                    Log.d(
                        "PDF_TEXT_LINK",
                        "found page=$page url=$clickable rect=${selected.minOf { it.x1 }},${selected.minOf { it.y1 }},${selected.maxOf { it.x2 }},${selected.maxOf { it.y2 }}"
                    )
                }
            }
        }
        Log.d("PDF_TEXT_LINK", "done found=${result.size}")
        return result
    }

    /** Minimal PDF content lexer: names, numbers, literal/hex strings and arrays. */
    private fun tokenizePdfContent(bytes: ByteArray): List<Any> {
        var i = 0
        fun white(b: Int) = b == 0 || b == 9 || b == 10 || b == 12 || b == 13 || b == 32
        fun delimiter(b: Int) = white(b) || b in listOf(
            '('.code,
            ')'.code,
            '<'.code,
            '>'.code,
            '['.code,
            ']'.code,
            '/'.code
        )

        fun literal(): ByteArray {
            i++
            var depth = 1
            val out = java.io.ByteArrayOutputStream()
            while (i < bytes.size && depth > 0) {
                var b = bytes[i++].toInt() and 0xff
                when (b) {
                    '\\'.code -> {
                        if (i >= bytes.size) break
                        b = bytes[i++].toInt() and 0xff
                        when (b) {
                            'n'.code -> out.write('\n'.code)
                            'r'.code -> out.write('\r'.code)
                            't'.code -> out.write('\t'.code)
                            'b'.code -> out.write('\b'.code)
                            'f'.code -> out.write(12)
                            '\n'.code -> Unit
                            '\r'.code -> if (i < bytes.size && bytes[i].toInt() == '\n'.code) i++
                            in '0'.code..'7'.code -> {
                                var oct = b - '0'.code
                                repeat(2) {
                                    if (i < bytes.size && (bytes[i].toInt() and 0xff) in '0'.code..'7'.code) {
                                        oct = oct * 8 + ((bytes[i++].toInt() and 0xff) - '0'.code)
                                    }
                                }
                                out.write(oct and 0xff)
                            }

                            else -> out.write(b)
                        }
                    }

                    '('.code -> {
                        depth++; out.write(b)
                    }

                    ')'.code -> {
                        depth--; if (depth > 0) out.write(b)
                    }

                    else -> out.write(b)
                }
            }
            return out.toByteArray()
        }

        fun hexString(): ByteArray {
            i++ // '<'
            val hex = StringBuilder()
            while (i < bytes.size) {
                val char = (bytes[i++].toInt() and 0xff).toChar()
                if (char == '>') break
                if (char.isDigit() || char.lowercaseChar() in 'a'..'f') {
                    hex.append(char)
                }
            }
            // PDF pads an odd final hex nibble with zero.
            if (hex.length % 2 != 0) hex.append('0')
            return ByteArray(hex.length / 2) { index ->
                hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }

        fun array(): List<Any> {
            i++
            val result = mutableListOf<Any>()
            while (i < bytes.size) {
                while (i < bytes.size && white(bytes[i].toInt() and 0xff)) i++
                if (i >= bytes.size || bytes[i].toInt().toChar() == ']') {
                    i++; break
                }
                val b = bytes[i].toInt() and 0xff
                when (b.toChar()) {
                    '(' -> result.add(literal())
                    '<' -> result.add(hexString())
                    '[' -> result.add(array())
                    else -> {
                        val start = i
                        while (i < bytes.size && !delimiter(bytes[i].toInt() and 0xff)) i++
                        if (i == start) {
                            // Unsupported delimiter/name in an array. Consume it
                            // to guarantee progress even on malformed PDFs.
                            i++
                        } else {
                            String(bytes, start, i - start, Charsets.ISO_8859_1)
                                .toFloatOrNull()?.let(result::add)
                        }
                    }
                }
            }
            return result
        }

        val result = mutableListOf<Any>()
        while (i < bytes.size) {
            while (i < bytes.size && white(bytes[i].toInt() and 0xff)) i++
            if (i >= bytes.size) break
            when (bytes[i].toInt().toChar()) {
                '%' -> while (i < bytes.size && bytes[i].toInt().toChar() !in listOf(
                        '\n',
                        '\r'
                    )
                ) i++

                '(' -> result.add(literal())
                '<' -> {
                    // A single '<' is a hex string. '<<' starts a dictionary;
                    // dictionaries are irrelevant to text-showing operators.
                    if (i + 1 < bytes.size && bytes[i + 1].toInt().toChar() == '<') {
                        i += 2
                    } else {
                        result.add(hexString())
                    }
                }

                '[' -> result.add(array())
                '/' -> {
                    i++
                    val start = i
                    while (i < bytes.size && !delimiter(bytes[i].toInt() and 0xff)) i++
                    result.add(PdfNameToken(String(bytes, start, i - start, Charsets.ISO_8859_1)))
                }

                else -> {
                    val start = i
                    while (i < bytes.size && !delimiter(bytes[i].toInt() and 0xff)) i++
                    if (i == start) {
                        i++; continue
                    }
                    val value = String(bytes, start, i - start, Charsets.ISO_8859_1)
                    result.add(value.toFloatOrNull() ?: PdfWordToken(value))
                }
            }
        }
        return result
    }

    private fun collectRequiredStreamIds(objects: Map<Int, String>): Set<Int> {
        val result = mutableSetOf<Int>()
        val refRegex = Regex("""(\d+)\s+\d+\s+R""")

        // Every ToUnicode stream is needed to decode visible text.
        objects.values.forEach { obj ->
            Regex("""/ToUnicode\s+(\d+)\s+\d+\s+R""")
                .findAll(obj)
                .forEach { result.add(it.groupValues[1].toInt()) }
        }

        // Follow /Contents references. A reference may point directly to a
        // stream or to an indirect array containing several stream refs.
        val queue = java.util.ArrayDeque<Int>()
        objects.values.forEach { obj ->
            Regex(
                """/Contents\s*(?:\[(.*?)]|(\d+)\s+\d+\s+R)""",
                RegexOption.DOT_MATCHES_ALL
            ).findAll(obj).forEach { match ->
                val refs = match.groupValues[1]
                if (refs.isNotBlank()) {
                    refRegex.findAll(refs).forEach { queue.add(it.groupValues[1].toInt()) }
                } else {
                    match.groupValues[2].toIntOrNull()?.let(queue::add)
                }
            }
        }

        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!result.add(id)) continue
            val obj = objects[id] ?: continue
            // Indirect /Contents arrays have no stream keyword.
            if (!obj.contains("stream")) {
                refRegex.findAll(obj).forEach { queue.add(it.groupValues[1].toInt()) }
            }
        }
        return result
    }

    private fun extractDecodedStreams(
        objects: Map<Int, String>,
        requiredIds: Set<Int>
    ): Map<Int, String> {

        val result = mutableMapOf<Int, String>()

        for ((id, obj) in objects) {

            if (id !in requiredIds) {
                continue
            }

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

                val startedNs = System.nanoTime()

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

                Log.d(
                    "PDF_PERF",
                    "inflate object=$id compressedChars=${stream.length} " +
                            "decodedChars=${decoded.length} ms=${
                                "%.2f".format(
                                    java.util.Locale.US,
                                    (System.nanoTime() - startedNs) / 1_000_000.0
                                )
                            }"
                )


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

        // bfrange may legally contain no spaces:
        // <20><20><0068>
        Regex(
            """<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>"""
        ).findAll(text).forEach { match ->
            val sourceStart = match.groupValues[1].toInt(16)
            val sourceEnd = match.groupValues[2].toInt(16)
            val unicodeStartHex = match.groupValues[3]
            val unicodeStart = unicodeStartHex.toIntOrNull(16) ?: return@forEach
            val sourceWidth = match.groupValues[1].length

            for (source in sourceStart..sourceEnd) {
                val unicode = unicodeStart + (source - sourceStart)
                if (unicode <= Char.MAX_VALUE.code) {
                    result[source.toString(16).padStart(sourceWidth, '0').uppercase()] =
                        unicode.toChar()
                }
            }
        }

        // bfchar form: <20><0068>. Restrict it to beginbfchar blocks so
        // the first two values of bfrange are not misinterpreted.
        Regex(
            """beginbfchar(.*?)endbfchar""",
            RegexOption.DOT_MATCHES_ALL
        ).findAll(text).forEach { block ->
            Regex(
                """<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]{4})>"""
            ).findAll(block.groupValues[1]).forEach { match ->
                val unicode = match.groupValues[2].toIntOrNull(16) ?: return@forEach
                result[match.groupValues[1].uppercase()] = unicode.toChar()
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
                Regex("""/Type\s*/Pages\b""").containsMatchIn(it.value)
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


            if (Regex("""/Type\s*/Page\b""").containsMatchIn(obj)) {

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
