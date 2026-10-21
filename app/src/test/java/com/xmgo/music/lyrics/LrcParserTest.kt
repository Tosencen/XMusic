package com.xmgo.music.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun `parse basic mm-ss and fractions`() {
        val lrc = """
            [ti:测试]
            [ar:某人]
            [00:01.50]第一句
            [00:02]第二句
            [00:03.1]第三句
            [01:04.123]第四句
        """.trimIndent()
        val lines = LrcParser.parse(lrc)
        assertEquals(4, lines.size)
        assertEquals(1500L, lines[0].timeMs)
        assertEquals("第一句", lines[0].text)
        assertEquals(2000L, lines[1].timeMs)
        assertEquals(3100L, lines[2].timeMs)
        assertEquals(64123L, lines[3].timeMs)
    }

    @Test
    fun `parse multi time tags on one line`() {
        val lrc = "[00:10.00][01:20.00]副歌重复"
        val lines = LrcParser.parse(lrc)
        assertEquals(2, lines.size)
        assertEquals(10000L, lines[0].timeMs)
        assertEquals("副歌重复", lines[0].text)
        assertEquals(80000L, lines[1].timeMs)
        assertEquals("副歌重复", lines[1].text)
    }

    @Test
    fun `parse sorts by time`() {
        val lrc = """
            [00:30.00]后
            [00:10.00]前
            [00:20.00]中
        """.trimIndent()
        val lines = LrcParser.parse(lrc)
        assertEquals(listOf("前", "中", "后"), lines.map { it.text })
    }

    @Test
    fun `parse null blank garbage returns empty`() {
        assertTrue(LrcParser.parse(null).isEmpty())
        assertTrue(LrcParser.parse("").isEmpty())
        assertTrue(LrcParser.parse("   \n  ").isEmpty())
        assertTrue(LrcParser.parse("没有时间标签的文本").isEmpty())
    }

    @Test
    fun `merge by matching timestamps`() {
        val original = listOf(
            LyricLine(1000, "hello"),
            LyricLine(2000, "world")
        )
        val translation = listOf(
            LyricLine(1000, "你好"),
            LyricLine(2000, "世界")
        )
        val merged = LrcParser.merge(original, translation)
        assertEquals("你好", merged[0].translation)
        assertEquals("世界", merged[1].translation)
    }

    @Test
    fun `merge falls back to index when timestamps differ`() {
        val original = listOf(
            LyricLine(1000, "a"),
            LyricLine(2000, "b"),
            LyricLine(3000, "c")
        )
        val translation = listOf(
            LyricLine(9999, "甲"),
            LyricLine(9998, "乙"),
            LyricLine(9997, "丙")
        )
        val merged = LrcParser.merge(original, translation)
        assertEquals("甲", merged[0].translation)
        assertEquals("乙", merged[1].translation)
        assertEquals("丙", merged[2].translation)
    }

    @Test
    fun `merge empty translation returns original unchanged`() {
        val original = listOf(LyricLine(1000, "a"))
        assertEquals(original, LrcParser.merge(original, emptyList()))
    }

    @Test
    fun `parseWithTranslation end to end`() {
        val original = "[00:01.00]hello\n[00:02.00]world"
        val translation = "[00:01.00]你好\n[00:02.00]世界"
        val merged = LrcParser.parseWithTranslation(original, translation)
        assertEquals(2, merged.size)
        assertEquals("你好", merged[0].translation)
        assertEquals("世界", merged[1].translation)
    }

    @Test
    fun `yrcToLrc strips inner tags and keeps text`() {
        val yrc = "[00:33.260](择一城[00:33.420]居[00:33.600]生)"
        val lrc = LrcParser.yrcToLrc(yrc)
        assertEquals("[00:33.260]择一城居生\n", lrc)
        val parsed = LrcParser.parse(lrc)
        assertEquals(1, parsed.size)
        assertEquals(33260L, parsed[0].timeMs)
        assertEquals("择一城居生", parsed[0].text)
    }

    @Test
    fun `currentIndex binary search`() {
        val lines = listOf(
            LyricLine(1000, "a"),
            LyricLine(2000, "b"),
            LyricLine(3000, "c")
        )
        assertEquals(-1, LrcParser.currentIndex(lines, 0))
        assertEquals(0, LrcParser.currentIndex(lines, 1000))
        assertEquals(0, LrcParser.currentIndex(lines, 1500))
        assertEquals(1, LrcParser.currentIndex(lines, 2000))
        assertEquals(2, LrcParser.currentIndex(lines, 3000))
        assertEquals(2, LrcParser.currentIndex(lines, 99999))
        assertEquals(-1, LrcParser.currentIndex(emptyList(), 1000))
    }
}
