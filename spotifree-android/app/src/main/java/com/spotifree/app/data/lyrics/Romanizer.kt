package com.spotifree.app.data.lyrics

import java.text.Normalizer

/**
 * Romanization for lyrics (port of the previous app): Korean (Revised Romanization, simplified),
 * Japanese kana (Hepburn), Cyrillic and Greek. Kanji/hanzi are left unchanged.
 * Returns null when the text has nothing to romanize, so Latin lyrics get no duplicate line.
 */
object Romanizer {
    private val INI = arrayOf("g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h")
    private val MED = arrayOf("a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i")
    private val FIN = arrayOf("", "k", "k", "k", "n", "n", "n", "t", "l", "k", "m", "l", "l", "l", "p", "l", "m", "p", "p", "t", "t", "ng", "t", "t", "k", "t", "p", "t")
    private val LIAISON = mapOf(1 to "g", 4 to "n", 7 to "d", 8 to "r", 16 to "m", 17 to "b", 19 to "s", 22 to "j", 23 to "ch", 24 to "k", 25 to "t", 26 to "p")

    private val KANA = mapOf(
        'あ' to "a", 'い' to "i", 'う' to "u", 'え' to "e", 'お' to "o",
        'か' to "ka", 'き' to "ki", 'く' to "ku", 'け' to "ke", 'こ' to "ko",
        'さ' to "sa", 'し' to "shi", 'す' to "su", 'せ' to "se", 'そ' to "so",
        'た' to "ta", 'ち' to "chi", 'つ' to "tsu", 'て' to "te", 'と' to "to",
        'な' to "na", 'に' to "ni", 'ぬ' to "nu", 'ね' to "ne", 'の' to "no",
        'は' to "ha", 'ひ' to "hi", 'ふ' to "fu", 'へ' to "he", 'ほ' to "ho",
        'ま' to "ma", 'み' to "mi", 'む' to "mu", 'め' to "me", 'も' to "mo",
        'や' to "ya", 'ゆ' to "yu", 'よ' to "yo",
        'ら' to "ra", 'り' to "ri", 'る' to "ru", 'れ' to "re", 'ろ' to "ro",
        'わ' to "wa", 'ゐ' to "i", 'ゑ' to "e", 'を' to "o", 'ん' to "n",
        'が' to "ga", 'ぎ' to "gi", 'ぐ' to "gu", 'げ' to "ge", 'ご' to "go",
        'ざ' to "za", 'じ' to "ji", 'ず' to "zu", 'ぜ' to "ze", 'ぞ' to "zo",
        'だ' to "da", 'ぢ' to "ji", 'づ' to "zu", 'で' to "de", 'ど' to "do",
        'ば' to "ba", 'び' to "bi", 'ぶ' to "bu", 'べ' to "be", 'ぼ' to "bo",
        'ぱ' to "pa", 'ぴ' to "pi", 'ぷ' to "pu", 'ぺ' to "pe", 'ぽ' to "po",
        'ぁ' to "a", 'ぃ' to "i", 'ぅ' to "u", 'ぇ' to "e", 'ぉ' to "o", 'ゎ' to "wa", 'ゔ' to "vu",
    )
    private val SMALL_Y = mapOf('ゃ' to "a", 'ゅ' to "u", 'ょ' to "o")

    private val CYRILLIC = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo", 'ж' to "zh", 'з' to "z", 'и' to "i",
        'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
        'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch", 'ъ' to "", 'ы' to "y", 'ь' to "",
        'э' to "e", 'ю' to "yu", 'я' to "ya", 'і' to "i", 'ї' to "yi", 'є' to "ye", 'ґ' to "g", 'ў' to "u",
    )
    private val GREEK = mapOf(
        'α' to "a", 'β' to "v", 'γ' to "g", 'δ' to "d", 'ε' to "e", 'ζ' to "z", 'η' to "i", 'θ' to "th", 'ι' to "i", 'κ' to "k",
        'λ' to "l", 'μ' to "m", 'ν' to "n", 'ξ' to "x", 'ο' to "o", 'π' to "p", 'ρ' to "r", 'σ' to "s", 'ς' to "s", 'τ' to "t",
        'υ' to "y", 'φ' to "f", 'χ' to "ch", 'ψ' to "ps", 'ω' to "o",
    )

    private val SUPPORTED = Regex("[가-힣ぁ-ゟァ-ヺーЀ-ӿͰ-Ͽἀ-῿]")

    private fun isHangul(c: Char) = c.code in 0xAC00..0xD7A3
    private fun isKatakana(c: Char) = c.code in 0x30A1..0x30F6
    private fun toHiragana(c: Char) = if (isKatakana(c)) (c.code - 0x60).toChar() else c
    private fun isKana(c: Char) = KANA.containsKey(c) || SMALL_Y.containsKey(c) || c == 'っ' || c == 'ー'

    private fun mapAlphabet(c: Char, table: Map<Char, String>): String? {
        val lower = c.lowercaseChar()
        val out = table[lower] ?: return null
        return if (c != lower && out.isNotEmpty()) out[0].uppercaseChar() + out.substring(1) else out
    }

    fun romanize(text: String?): String? {
        if (text.isNullOrEmpty() || !SUPPORTED.containsMatchIn(text)) return null
        val src = Normalizer.normalize(
            Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("[\\u0300-\\u036f]"), ""),
            Normalizer.Form.NFC,
        ).toCharArray()
        val out = StringBuilder()
        var pending = ""
        var geminate = false
        var converted = false

        fun appendKana(romaji: String) {
            if (geminate && romaji.isNotEmpty()) {
                out.append(if (romaji.startsWith("ch")) "t" else romaji[0].toString())
                geminate = false
            }
            out.append(romaji)
        }

        var i = 0
        while (i < src.size) {
            val ch = src[i]
            if (isHangul(ch)) {
                converted = true
                val s = ch.code - 0xAC00
                val l = s / 588
                val v = (s % 588) / 28
                val t = s % 28
                val next = src.getOrNull(i + 1)
                val nextSilent = next != null && isHangul(next) && (next.code - 0xAC00) / 588 == 11
                var initial = INI[l]
                if (pending.isNotEmpty() && l == 11) initial = pending
                pending = ""
                when {
                    t == 27 && nextSilent -> out.append(initial).append(MED[v])
                    t > 0 && nextSilent && LIAISON.containsKey(t) -> {
                        out.append(initial).append(MED[v])
                        pending = LIAISON.getValue(t)
                    }
                    else -> out.append(initial).append(MED[v]).append(FIN[t])
                }
                i++
                continue
            }
            pending = ""
            val hira = toHiragana(ch)
            if (isKana(hira)) {
                converted = true
                when {
                    hira == 'っ' -> geminate = true
                    hira == 'ー' -> Regex("[aeiou](?=[^aeiou]*$)", RegexOption.IGNORE_CASE).find(out)?.let { out.append(it.value.lowercase()) }
                    SMALL_Y.containsKey(hira) -> {
                        val small = SMALL_Y.getValue(hira)
                        val cur = out.toString()
                        when {
                            Regex("(shi|chi|ji)$").containsMatchIn(cur) -> { out.setLength(out.length - 1); out.append(small) }
                            Regex("[^aeiou]i$").containsMatchIn(cur) -> { out.setLength(out.length - 1); out.append('y').append(small) }
                            else -> out.append('y').append(small)
                        }
                    }
                    else -> {
                        var romaji = KANA.getValue(hira)
                        if (hira == 'ん') {
                            val nextRomaji = src.getOrNull(i + 1)?.let { KANA[toHiragana(it)] } ?: ""
                            if (Regex("^[aeiouy]").containsMatchIn(nextRomaji)) romaji = "n'"
                        }
                        appendKana(romaji)
                    }
                }
                i++
                continue
            }
            geminate = false
            val cyr = if (ch.code in 0x0400..0x04FF) mapAlphabet(ch, CYRILLIC) else null
            if (cyr != null) {
                converted = true
                out.append(cyr)
                i++
                continue
            }
            val gr = if (ch.code in 0x0370..0x03FF) mapAlphabet(ch, GREEK) else null
            if (gr != null) {
                converted = true
                out.append(gr)
                i++
                continue
            }
            out.append(ch)
            i++
        }
        if (!converted) return null
        val result = out.toString().replace(Regex("""\s+"""), " ").trim()
        return if (result.isEmpty()) null else result[0].uppercaseChar() + result.substring(1)
    }
}
