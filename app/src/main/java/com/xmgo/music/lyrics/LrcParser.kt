package com.xmgo.music.lyrics

/** 一行歌词：时间轴 + 原文 + 可选翻译。 */
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val translation: String? = null
)

/**
 * LRC 解析器。
 * 支持 [mm:ss] / [mm:ss.x] / [mm:ss.xx] / [mm:ss.xxx] 多时间标签行；
 * [ar:] [ti:] 等元数据行自动跳过（无时间标签）。
 * 增强 LRC 逐字标签 <mm:ss.xxx>（酷狗 KRC 等）：展示层不渲染逐字高亮，
 * 无 [] 行标签时取首个 <> 作行时间，全部 <> 从文本剥离。
 */
object LrcParser {

    private val timeTag = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private val wordTag = Regex("""<(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?>""")

    fun parse(lrc: String?): List<LyricLine> {
        if (lrc.isNullOrBlank()) return emptyList()
        val entries = mutableListOf<Pair<Long, String>>()

        for (raw in lrc.lineSequence()) {
            var tags = timeTag.findAll(raw).toList()
            if (tags.isEmpty()) {
                // 只有逐字时间戳：取首个作行时间（全部取会把一行拆成逐字多行）
                wordTag.find(raw)?.let { tags = listOf(it) }
            }
            if (tags.isEmpty()) continue
            val text = timeTag.replace(raw, "")
                .replace(wordTag, "")
                .trim()
            for (tag in tags) {
                val minutes = tag.groupValues[1].toLongOrNull() ?: continue
                val seconds = tag.groupValues[2].toLongOrNull() ?: continue
                val fraction = tag.groupValues[3]
                val millis = when {
                    fraction.isEmpty() -> 0L
                    fraction.length == 1 -> fraction.toLong() * 100
                    fraction.length == 2 -> fraction.toLong() * 10
                    else -> fraction.take(3).toLong()
                }
                entries += (minutes * 60_000 + seconds * 1_000 + millis) to text
            }
        }

        return entries
            .sortedBy { it.first }
            .map { (time, text) -> LyricLine(timeMs = time, text = text) }
            .filter { it.text.isNotEmpty() || it.timeMs > 0 }
    }

    /**
     * 合并翻译：优先按时间戳配对，数量一致时回退按行序配对。
     */
    fun merge(original: List<LyricLine>, translation: List<LyricLine>): List<LyricLine> {
        if (translation.isEmpty()) return original
        val byTime = translation.associateBy { it.timeMs }
        val useIndex = byTime.isEmpty() || original.count { it.timeMs in byTime } < original.size / 2
        return original.mapIndexed { index, line ->
            val t = if (useIndex && index < translation.size) {
                translation[index].text
            } else {
                byTime[line.timeMs]?.text
            }
            line.copy(translation = t?.takeIf { it.isNotBlank() })
        }
    }

    /** 解析 + 合并翻译的一步入口。 */
    fun parseWithTranslation(original: String?, translation: String?): List<LyricLine> =
        merge(parse(original), parse(translation))

    // ---------------------------------------------------------------- 格式转换

    private val yrcTimeTag = Regex("""\[\d{1,2}:\d{2}(?:[.:]\d{1,3})?]""")

    /**
     * 网易 YRC（逐字，`[00:33.260](择一城[00:33.420]居...)`）→ 行级 LRC：
     * 保留每行首个时间戳，剥掉内嵌时间戳与括号，保留全部文字。
     */
    fun yrcToLrc(yrc: String): String = buildString {
        for (line in yrc.lineSequence()) {
            val first = yrcTimeTag.find(line) ?: continue
            val text = line
                .replace(first.value, "")
                .replace(yrcTimeTag, "")
                .replace("(", "")
                .replace(")", "")
                .trim()
            if (text.isNotEmpty()) {
                append(first.value).append(text).append('\n')
            }
        }
    }

    /** TTML 时间 → ms。支持 `12.34s` / `1234ms` / `mm:ss(.xxx)` / 裸秒；帧格式不支持返回 null。 */
    private fun ttmlTimeToMs(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val s = value.trim()
        return try {
            val seconds = when {
                s.endsWith("ms") -> s.removeSuffix("ms").toDoubleOrNull()?.div(1000.0)
                s.endsWith("s") -> s.removeSuffix("s").toDoubleOrNull()
                s.endsWith("f") -> null // 帧时间需要 frameRate 上下文，AMLL 不用
                s.contains(":") -> {
                    var total = 0.0
                    for (part in s.split(':')) {
                        val v = part.toDoubleOrNull() ?: return null
                        total = total * 60 + v
                    }
                    total
                }
                else -> s.toDoubleOrNull()
            } ?: return null
            (seconds * 1000).toLong()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * TTML（AMLL ncm-lyrics，行级 `<p begin=...>文本</p>`）→ LRC。
     * 行内 span 的逐字文本拼接为整行。解析失败返回 null。
     */
    fun ttmlToLrc(ttml: String): String? = runCatching {
        val parser = android.util.Xml.newPullParser()
        parser.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(java.io.StringReader(ttml))
        val out = StringBuilder()
        var inP = false
        var begin: String? = null
        val text = StringBuilder()
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (event) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> if (parser.name == "p") {
                    inP = true
                    begin = parser.getAttributeValue(null, "begin")
                    text.setLength(0)
                }
                org.xmlpull.v1.XmlPullParser.TEXT -> if (inP) text.append(parser.text)
                org.xmlpull.v1.XmlPullParser.END_TAG -> if (parser.name == "p" && inP) {
                    inP = false
                    val ms = ttmlTimeToMs(begin)
                    val line = text.toString().replace(Regex("\\s+"), " ").trim()
                    if (ms != null && line.isNotEmpty()) {
                        out.append(formatLrcTime(ms)).append(line).append('\n')
                    }
                }
            }
            event = parser.next()
        }
        out.toString()
    }.getOrNull()

    private fun formatLrcTime(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val sec = totalSec % 60
        val frac = ms % 1000
        return String.format("[%02d:%02d.%02d]", m, sec, frac / 10)
    }

    /**
     * 根据播放位置找当前行索引（最后一条 time <= position）。
     * 返回 -1 表示尚未开始（第一行之前）。
     */
    fun currentIndex(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var low = 0
        var high = lines.size - 1
        var result = -1
        while (low <= high) {
            val mid = (low + high) / 2
            if (lines[mid].timeMs <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }
}
