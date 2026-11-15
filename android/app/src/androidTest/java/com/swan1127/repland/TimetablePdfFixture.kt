package com.swan1127.repland

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.ByteArrayOutputStream

/**
 * 生成一份真的像课表的 PDF：星期表头一行、左边节次一列、课程落在交叉格里。
 *
 * 两套版式的边距、列距、行距、字号全都不一样，横版还带周末与「1-2」式节次。
 * 解析器要是还靠写死的几何数字认课表，至少有一套会读不出来。
 *
 * 用英文星期是因为设备上没有可嵌入的中文字体；列与行是靠几何量出来的，与语种无关。
 */
internal object TimetablePdfFixture {

    /** 竖版 LETTER，五个 weekday，单节次标签，课程字号 10。 */
    val STANDARD = Layout(
        page = PDRectangle.LETTER,
        periodX = 70f,
        firstColumnX = 120f,
        columnPitch = 100f,
        headerY = 750f,
        firstRowY = 700f,
        rowPitch = 50f,
        headerSize = 12f,
        periodSize = 12f,
        titleSize = 10f,
        days = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday"),
        periods = listOf("1", "2", "3", "4"),
    )

    /** 横版 A4，七个 weekday（含周末），节次写成「1-2」，字号与间距全换。 */
    val LANDSCAPE_RANGE = Layout(
        page = PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width),
        periodX = 40f,
        firstColumnX = 130f,
        columnPitch = 95f,
        headerY = 540f,
        firstRowY = 480f,
        rowPitch = 62f,
        headerSize = 9f,
        periodSize = 8f,
        titleSize = 11.5f,
        days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"),
        periods = listOf("1-2", "3-4", "5-6"),
    )

    class Layout(
        val page: PDRectangle,
        val periodX: Float,
        val firstColumnX: Float,
        val columnPitch: Float,
        val headerY: Float,
        val firstRowY: Float,
        val rowPitch: Float,
        val headerSize: Float,
        val periodSize: Float,
        val titleSize: Float,
        val days: List<String>,
        val periods: List<String>,
    )

    /**
     * 只放一门课，落在 [dayIndex] 列 / [periodIndex] 行。
     * [detail] 是格内第二行（例如 "(3-4)" 或 "1-16周"），用来验证节次/周次是从格内读出来的。
     */
    fun singleCourse(
        title: String,
        layout: Layout = STANDARD,
        dayIndex: Int = 0,
        periodIndex: Int = 0,
        detail: String? = null,
    ): ByteArray = PDDocument().use { document ->
        val page = PDPage(layout.page)
        document.addPage(page)
        PDPageContentStream(document, page).use { content ->
            fun text(value: String, x: Float, y: Float, size: Float) {
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, size)
                content.newLineAtOffset(x, y)
                content.showText(value)
                content.endText()
            }
            layout.days.forEachIndexed { index, day ->
                text(day, layout.firstColumnX + index * layout.columnPitch, layout.headerY, layout.headerSize)
            }
            layout.periods.forEachIndexed { index, period ->
                text(period, layout.periodX, layout.firstRowY - index * layout.rowPitch, layout.periodSize)
            }
            val x = layout.firstColumnX + dayIndex * layout.columnPitch
            val y = layout.firstRowY - periodIndex * layout.rowPitch
            text(title, x, y, layout.titleSize)
            if (detail != null) text(detail, x, y - layout.titleSize - 2f, layout.titleSize - 2f)
        }
        ByteArrayOutputStream().use { output ->
            document.save(output)
            output.toByteArray()
        }
    }
}