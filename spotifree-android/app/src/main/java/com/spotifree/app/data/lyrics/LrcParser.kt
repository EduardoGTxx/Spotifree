package com.spotifree.app.data.lyrics

import com.spotifree.app.data.model.LyricLine
import com.spotifree.app.data.model.LyricWord

/**
 * LRC parsing (port of the previous app's parser): multiple timestamps per line, [mm:ss],
 * [mm:ss.xx(x)], [mm:ss:xx], the [offset:] tag, enhanced word tags <mm:ss.xx> for karaoke,
 * metadata tags, and blank timestamped lines kept as an instrumental marker.
 */
object LrcParser {
    const val INSTRUMENTAL = "♪"
    private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val WORD_TAG = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val OFFSET = Regex("""^\[offset:\s*([+-]?\d+)\s*]$""", RegexOption.IGNORE_CASE)

    private fun frac(s: String?): Double = if (s.isNullOrEmpty()) 0.0 else s.padEnd(3, '0').take(3).toInt() / 1000.0

    fun decodeEntities(t: String) = t.replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
        .replace("&apos;", "'").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")

    fun parse(lrc: String?): List<LyricLine> {
        if (lrc.isNullOrBlank()) return emptyList()
        var offsetMs = 0
        val result = ArrayList<LyricLine>()
        for (raw in lrc.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            OFFSET.find(line)?.let {
                offsetMs = it.groupValues[1].toIntOrNull() ?: 0
                continue
            }
            val times = ArrayList<Double>()
            var lastIndex = 0
            var m = TIME_TAG.find(line, 0)
            while (m != null && m.range.first == lastIndex) {
                times += m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt() + frac(m.groupValues[3])
                lastIndex = m.range.last + 1
                m = TIME_TAG.find(line, lastIndex)
            }
            if (times.isEmpty()) continue
            val body = line.substring(lastIndex)
            val text = decodeEntities(body.replace(WORD_TAG, "").replace(Regex("""\s+"""), " ").trim())
            val words = if (text.isNotEmpty()) parseWords(body, times[0], text) else null
            for (t in times) {
                val time = maxOf(0.0, t - offsetMs / 1000.0)
                val shifted = words?.map { LyricWord(maxOf(0.0, it.timeSec + (t - times[0]) - offsetMs / 1000.0), it.text) }
                result += LyricLine(time, text.ifEmpty { INSTRUMENTAL }, shifted, isInstrumental = text.isEmpty())
            }
        }
        result.sortBy { it.timeSec }
        val compact = ArrayList<LyricLine>()
        for (item in result) {
            if (item.isInstrumental && compact.lastOrNull()?.isInstrumental == true) continue
            compact += item
        }
        while (compact.isNotEmpty() && compact.first().isInstrumental && compact.first().timeSec < 0.5) compact.removeAt(0)
        while (compact.isNotEmpty() && compact.last().isInstrumental) compact.removeAt(compact.size - 1)
        return compact
    }

    private fun parseWords(body: String, lineTime: Double, finalText: String): List<LyricWord>? {
        data class Tag(val start: Int, val end: Int, val seconds: Double)
        val tags = WORD_TAG.findAll(body).map {
            Tag(it.range.first, it.range.last + 1, it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() + frac(it.groupValues[3]))
        }.toList()
        if (tags.size < 2 && !(tags.size == 1 && tags[0].start > 0)) return null
        val words = ArrayList<LyricWord>()
        val firstNonSpace = body.indexOfFirst { !it.isWhitespace() }
        val startsWithTag = tags.isNotEmpty() && tags[0].start == firstNonSpace
        if (startsWithTag) {
            for (i in tags.indices) {
                val chunk = body.substring(tags[i].end, if (i + 1 < tags.size) tags[i + 1].start else body.length)
                if (chunk.isNotEmpty()) words += LyricWord(tags[i].seconds, decodeEntities(chunk))
            }
        } else {
            var prevEnd = 0
            var start = lineTime
            for (tag in tags) {
                val chunk = body.substring(prevEnd, tag.start)
                if (chunk.isNotEmpty()) words += LyricWord(start, decodeEntities(chunk))
                start = tag.seconds
                prevEnd = tag.end
            }
            val rest = body.substring(prevEnd)
            if (rest.isNotBlank()) words += LyricWord(start, decodeEntities(rest))
        }
        val cleaned = ArrayList<LyricWord>()
        for (w in words) {
            val t = w.text.replace(Regex("""\s+"""), " ")
            if (t.isEmpty()) continue
            cleaned += LyricWord(w.timeSec, if (cleaned.isEmpty()) t.trimStart() else t)
        }
        if (cleaned.isNotEmpty()) cleaned[cleaned.size - 1] = cleaned.last().copy(text = cleaned.last().text.trimEnd())
        val filtered = cleaned.filter { it.text.isNotEmpty() }
        if (filtered.size < 2) return null
        if (filtered.joinToString("") { it.text }.replace(Regex("""\s+"""), " ").trim() != finalText) return null
        for (i in 1 until filtered.size) if (filtered[i].timeSec < filtered[i - 1].timeSec) return null
        return filtered
    }

    fun parsePlain(text: String?): List<LyricLine> {
        if (text.isNullOrBlank()) return emptyList()
        return text.replace("\r\n", "\n").split('\n').map { decodeEntities(it.trim()) }.filter { it.isNotEmpty() }
            .mapIndexed { i, t -> LyricLine(i * 5.0, t) }
    }

    fun hasTimedLines(lines: List<LyricLine>) = lines.count { !it.isInstrumental } >= 3

    /** Index of the line to highlight at [timeSec] (binary search). */
    fun activeIndex(lines: List<LyricLine>, timeSec: Double): Int {
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeSec <= timeSec) {
                ans = mid
                lo = mid + 1
            } else hi = mid - 1
        }
        return ans
    }
}
