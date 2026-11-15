package com.swan1127.repland.data.importer

import android.content.Context
import android.net.Uri
import com.swan1127.repland.domain.model.ImportedCourse
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.IOException
import java.time.DayOfWeek
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the widely used Chinese university timetable layout: weekday columns and numbered periods.
 * It only creates import candidates; no course is stored until the user confirms the preview.
 */
class PdfTimetableImporter(
    private val context: Context,
) {
    suspend fun parse(uri: Uri): List<ImportedCourse> = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context.applicationContext)
        val input = context.contentResolver.openInputStream(uri)
            ?: throw TimetableImportException("无法读取所选文件")
        input.use { stream ->
            parseStream(stream)
        }
    }

    /**
     * Keeps PDF parsing separate from Android document selection so the same bounded,
     * read-only import path can be verified with a generated document on device.
     */
    internal fun parseStream(input: java.io.InputStream): List<ImportedCourse> =
        PDDocument.load(input).use { document ->
            if (document.numberOfPages > MAX_PAGES) {
                throw TimetableImportException("课表页数过多，请导入不超过 $MAX_PAGES 页的课表")
            }
            GridCourseStripper().extract(document)
        }

    private companion object {
        const val MAX_PAGES = 4
    }
}

class TimetableImportException(message: String) : IOException(message)

/**
 * 网格几何从文档里量出来，不写死边距和字号。
 *
 * 原来按固定列宽与固定字号窗口认课表：换一所学校的导出（边距不同、横向 A4、"第1节"写法、
 * 课程字号 9.5 或 11）就整片静默丢弃，用户看到的是"课表根本没导进来"。现在只认两件事——
 * 星期表头决定列，节次标签决定行——其余文字按落点归格。认不出网格就明确报错，绝不猜一个星期。
 */
private class GridCourseStripper : PDFTextStripper() {
    private val atoms = mutableListOf<Atom>()
    private var pageNumber = 0

    init {
        // 先画课程、后画节次标签的 PDF 也要能读：识别不再依赖文字吐出顺序。
        sortByPosition = true
    }

    @Throws(IOException::class)
    override fun startPage(page: PDPage) {
        pageNumber += 1
        super.startPage(page)
    }

    @Throws(IOException::class)
    override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
        // 只收字符，不收 PDFTextStripper 拼好的"行"：一行可能横跨整页，
        // 那样星期表头会被平均成一个 x，列就量不出来了。
        for (position in textPositions) {
            val glyph = position.unicode ?: continue
            if (glyph.isBlank()) continue
            atoms += Atom(pageNumber, position.xDirAdj, position.yDirAdj, position.widthDirAdj, position.fontSizeInPt, glyph)
        }
    }

    fun extract(document: PDDocument): List<ImportedCourse> {
        getText(document)
        val runs = runsFromAtoms()
        if (runs.isEmpty()) {
            throw TimetableImportException("这份 PDF 没有可读取的文字，可能是扫描件；请改用文字版课表")
        }
        return runs.groupBy(TextRun::page).values
            .flatMap(::coursesOnPage)
            .distinctBy { course ->
                listOf(course.title, course.dayOfWeek, course.startPeriod, course.endPeriod, course.weekPattern)
            }
            .sortedWith(compareBy(ImportedCourse::dayOfWeek, ImportedCourse::startPeriod, ImportedCourse::title))
            .ifEmpty {
                throw TimetableImportException("没能在这份 PDF 里认出「星期表头 + 节次」的课表网格；请换一份课表导出，或手动登记课程")
            }
    }

    /** 同一行里挨着的字符合成一个词块；跨列的大间隙就是列边界。 */
    private fun runsFromAtoms(): List<TextRun> {
        val result = mutableListOf<TextRun>()
        atoms.groupBy(Atom::page).forEach { (page, pageAtoms) ->
            var line = mutableListOf<Atom>()
            var lineY = Float.NaN
            fun flushLine() {
                var run = mutableListOf<Atom>()
                var lastRight = Float.NaN
                fun flushRun() {
                    if (run.isEmpty()) return
                    result += TextRun(
                        page = page,
                        x = (run.minOf(Atom::x) + run.maxOf { it.x + it.width }) / 2f,
                        y = run.map(Atom::y).average().toFloat(),
                        text = run.joinToString("") { it.text },
                    )
                    run = mutableListOf()
                }
                for (atom in line.sortedBy(Atom::x)) {
                    if (run.isNotEmpty() && atom.x - lastRight > atom.size * COLUMN_GAP_FACTOR) flushRun()
                    run += atom
                    lastRight = atom.x + atom.width
                }
                flushRun()
                line = mutableListOf()
            }
            for (atom in pageAtoms.sortedBy(Atom::y)) {
                if (line.isNotEmpty() && abs(atom.y - lineY) > LINE_TOLERANCE) flushLine()
                if (line.isEmpty()) lineY = atom.y
                line += atom
            }
            flushLine()
        }
        return result
    }

    private fun coursesOnPage(pageRuns: List<TextRun>): List<ImportedCourse> {
        val columns = dayColumns(pageRuns) ?: return emptyList()
        val pitch = median(columns.zipWithNext().map { (a, b) -> b.x - a.x })
        val headerY = columns.minOf(Column::y)
        val markers = pageRuns
            .filter { it.x < columns.first().x - pitch * PERIOD_COLUMN_GAP && it.y > headerY }
            .mapNotNull { run -> periodOf(run.text)?.let { Marker(run, it.first, it.second) } }
            .sortedBy { it.run.y }
        if (markers.isEmpty()) return emptyList()
        val leeway = maxOf(MIN_LEEWAY, median(markers.zipWithNext().map { (a, b) -> b.run.y - a.run.y }) * 0.2f)
        val markerRuns = markers.mapTo(mutableSetOf(), Marker::run)

        val cells = mutableMapOf<Cell, MutableList<TextRun>>()
        for (run in pageRuns) {
            if (run in markerRuns || run.y <= headerY + HEADER_BAND) continue
            val column = columns.minByOrNull { abs(it.x - run.x) } ?: continue
            if (abs(column.x - run.x) > pitch * 0.5f) continue
            val marker = markers.lastOrNull { it.run.y <= run.y + leeway } ?: continue
            cells.getOrPut(Cell(column.day, marker.start, marker.end)) { mutableListOf() } += run
        }

        var sequence = 0
        return cells.mapNotNull { (cell, cellRuns) ->
            val ordered = cellRuns.sortedWith(compareBy(TextRun::y, TextRun::x))
            val details = ordered.filter { isDetail(it.text) }
            val title = ordered.filterNot { isDetail(it.text) }.joinToString("") { it.text }
            if (title.length < MIN_TITLE_LENGTH) return@mapNotNull null
            val range = details.mapNotNull { PERIOD_RANGE.find(it.text) }.firstOrNull()
            val start = range?.groupValues?.get(1)?.toIntOrNull() ?: cell.start
            val end = range?.groupValues?.get(2)?.toIntOrNull() ?: cell.end
            if (start !in 1..MAX_PERIOD || end !in start..MAX_PERIOD) return@mapNotNull null
            ImportedCourse(
                id = "course-${pageRuns.first().page}-${sequence++}",
                title = title,
                dayOfWeek = cell.day,
                startPeriod = start,
                endPeriod = end,
                weekPattern = details.mapNotNull { WEEK_PATTERN.find(it.text)?.groupValues?.get(1)?.trim() }
                    .firstOrNull(String::isNotBlank),
            )
        }
    }

    /** 星期表头在哪一列，课程就在哪一列；列宽由表头间距量出来，横竖版都成立。 */
    private fun dayColumns(pageRuns: List<TextRun>): List<Column>? {
        val headers = pageRuns.mapNotNull { run -> dayOf(run.text)?.let { run to it } }
        if (headers.isEmpty()) return null
        val sortedY = headers.map { it.first.y }.sorted()
        val headerY = sortedY[sortedY.size / 2]
        val columns = headers.filter { abs(it.first.y - headerY) <= HEADER_BAND }
            .groupBy { it.second }
            .map { (day, group) ->
                val leftmost = group.minByOrNull { it.first.x }!!.first
                Column(day, leftmost.x, leftmost.y)
            }
            .sortedBy(Column::x)
        return columns.takeIf { it.size >= MIN_DAY_COLUMNS }
    }

    private fun periodOf(text: String): Pair<Int, Int>? {
        val cleaned = text.removePrefix("第").removeSuffix("节").removeSuffix("節")
        PERIOD_LABEL_RANGE.find(cleaned)?.let { match ->
            val start = match.groupValues[1].toIntOrNull() ?: return null
            val end = match.groupValues[2].toIntOrNull() ?: return null
            if (start in 1..MAX_PERIOD && end in start..MAX_PERIOD) return start to end
        }
        val single = cleaned.toIntOrNull() ?: return null
        return single.takeIf { it in 1..MAX_PERIOD }?.let { it to it }
    }

    private fun dayOf(text: String): DayOfWeek? {
        DAY_TOKENS.firstOrNull { (_, tokens) -> tokens.any(text::contains) }?.let { return it.first }
        val lower = text.lowercase()
        // 英文按词边界匹配：否则 "common" 里的 mon 会把普通课程行提升成表头。
        return ENGLISH_DAY_TOKENS.firstOrNull { (_, pattern) -> pattern.containsMatchIn(lower) }?.first
    }

    private fun isDetail(text: String): Boolean =
        PERIOD_RANGE.containsMatchIn(text) ||
            WEEK_PATTERN.containsMatchIn(text) ||
            DETAIL_WORDS.any(text::contains)

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return DEFAULT_PITCH
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private data class Atom(val page: Int, val x: Float, val y: Float, val width: Float, val size: Float, val text: String)
    private data class TextRun(val page: Int, val x: Float, val y: Float, val text: String)
    private data class Column(val day: DayOfWeek, val x: Float, val y: Float)
    private data class Marker(val run: TextRun, val start: Int, val end: Int)
    private data class Cell(val day: DayOfWeek, val start: Int, val end: Int)

    private companion object {
        val PERIOD_LABEL_RANGE = Regex("(\\d{1,2})[-~–—至](\\d{1,2})")
        val PERIOD_RANGE = Regex("[（(](\\d{1,2})[-~–—至](\\d{1,2})[节節]?[）)]?")
        val WEEK_PATTERN = Regex("[）)]?\\s*(\\d{1,2}\\s*[-~–—至]\\s*\\d{1,2}\\s*[周週]|[单雙双全]\\s*[周週])")
        val DETAIL_WORDS = listOf("教师", "老师", "地点", "教室", "学时", "学分")
        val DAY_TOKENS = listOf(
            DayOfWeek.MONDAY to listOf("星期一", "周一", "礼拜一"),
            DayOfWeek.TUESDAY to listOf("星期二", "周二", "礼拜二"),
            DayOfWeek.WEDNESDAY to listOf("星期三", "周三", "礼拜三"),
            DayOfWeek.THURSDAY to listOf("星期四", "周四", "礼拜四"),
            DayOfWeek.FRIDAY to listOf("星期五", "周五", "礼拜五"),
            DayOfWeek.SATURDAY to listOf("星期六", "周六", "礼拜六"),
            DayOfWeek.SUNDAY to listOf("星期日", "星期天", "周日", "周天", "礼拜日"),
        )
        val ENGLISH_DAY_TOKENS = listOf(
            DayOfWeek.MONDAY to Regex("\\bmon(day)?\\b"),
            DayOfWeek.TUESDAY to Regex("\\btue(s|sday)?\\b"),
            DayOfWeek.WEDNESDAY to Regex("\\bwed(nesday)?\\b"),
            DayOfWeek.THURSDAY to Regex("\\bthu(r|rs|rsday)?\\b"),
            DayOfWeek.FRIDAY to Regex("\\bfri(day)?\\b"),
            DayOfWeek.SATURDAY to Regex("\\bsat(urday)?\\b"),
            DayOfWeek.SUNDAY to Regex("\\bsun(day)?\\b"),
        )
        const val MAX_PERIOD = 12
        const val MIN_DAY_COLUMNS = 3
        const val MIN_TITLE_LENGTH = 2
        const val HEADER_BAND = 14f
        const val PERIOD_COLUMN_GAP = 0.4f
        const val MIN_LEEWAY = 6f
        const val DEFAULT_PITCH = 24f
        const val COLUMN_GAP_FACTOR = 1.5f
        const val LINE_TOLERANCE = 2f
    }
}
