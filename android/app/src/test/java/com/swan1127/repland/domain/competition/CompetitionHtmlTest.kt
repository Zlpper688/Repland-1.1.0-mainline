package com.swan1127.repland.domain.competition

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompetitionHtmlTest {

    @Test fun `strips script style comments and breaks lines at block tags`() {
        val html = "<html><head><style>body{color:red}</style><script>var a=1;</script></head>" +
            "<body><!-- hidden --><div>比赛报名<h1>标题</h1></div><p>第一段</p><br><p>第二段</p></body>"
        val text = CompetitionHtml.extractText(html)
        assertTrue(text.contains("比赛报名"))
        assertTrue(text.contains("第一段"))
        assertTrue(text.contains("第二段"))
        assertTrue(!text.contains("var a=1"))
        assertTrue(!text.contains("color:red"))
        assertTrue(!text.contains("hidden"))
        assertTrue(text.lines().size >= 3)
    }

    @Test fun `extracts title and decodes named entities`() {
        val title = CompetitionHtml.extractTitle("<html><title>全国大学生数学建模 &amp; AI 竞赛</title></html>")
        assertEquals("全国大学生数学建模 & AI 竞赛", title)
    }

    @Test fun `title falls back to h1 when no title tag`() {
        assertEquals("首页", CompetitionHtml.extractTitle("<div><h1>首页</h1></div>"))
    }

    @Test fun `double escaped entity stays escaped`() {
        val text = CompetitionHtml.extractText("<p>a&amp;lt;b</p>")
        assertEquals("a&lt;b", text)
    }

    @Test fun `truncates long text with marker`() {
        val html = "<p>" + "很长的正文".repeat(2000) + "</p>"
        val text = CompetitionHtml.extractText(html, maxChars = 500)
        assertTrue(text.startsWith("很长的正文"))
        assertTrue(text.endsWith("…（正文过长已截断）"))
        assertTrue(text.length < 600)
    }

    @Test fun `js rendered page yields empty text but keeps title`() {
        val html = "<html><head><title>报名系统</title></head><body><div id=\"root\"></div>" +
            "<script>document.getElementById('root').innerHTML='动态内容'</script></body></html>"
        assertEquals("", CompetitionHtml.extractText(html))
        assertEquals("报名系统", CompetitionHtml.extractTitle(html))
    }

    @Test fun `numeric entities decode`() {
        val text = CompetitionHtml.extractText("<p>报名&#26085;&#26399;：9&#26376;</p>")
        assertTrue(text.contains("报名日期：9月"))
    }

    @Test fun `charset header wins over meta declaration`() {
        val head = "<meta charset=\"UTF-8\">"
        assertEquals(Charset.forName("GBK"), CompetitionHtml.resolveCharset("text/html; charset=GBK", head))
    }

    @Test fun `charset falls back to meta declaration`() {
        val head = "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=gb2312\">"
        assertEquals(Charset.forName("GB2312"), CompetitionHtml.resolveCharset("text/html", head))
    }

    @Test fun `charset defaults to utf8 and survives unknown names`() {
        assertEquals(Charsets.UTF_8, CompetitionHtml.resolveCharset(null, "<html><body></body></html>"))
        assertEquals(Charsets.UTF_8, CompetitionHtml.resolveCharset("text/html; charset=x-not-a-charset", ""))
    }
}
