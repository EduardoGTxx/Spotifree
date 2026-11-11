const INI: [&str; 19] = [
    "g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h",
];

const MED: [&str; 21] = [
    "a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i",
];

const FIN: [&str; 28] = [
    "", "k", "k", "k", "n", "n", "n", "t", "l", "k", "m", "l", "l", "l", "p", "l", "m", "p", "p", "t", "t", "ng", "t", "t", "k", "t", "p", "t",
];

fn liaison(t: usize) -> Option<&'static str> {
    match t {
        1 => Some("g"),
        4 => Some("n"),
        7 => Some("d"),
        8 => Some("r"),
        16 => Some("m"),
        17 => Some("b"),
        19 => Some("s"),
        22 => Some("j"),
        23 => Some("ch"),
        24 => Some("k"),
        25 => Some("t"),
        26 => Some("p"),
        _ => None,
    }
}

fn kana_map(c: char) -> Option<&'static str> {
    match c {
        'あ' => Some("a"), 'い' => Some("i"), 'う' => Some("u"), 'え' => Some("e"), 'お' => Some("o"),
        'か' => Some("ka"), 'き' => Some("ki"), 'く' => Some("ku"), 'け' => Some("ke"), 'こ' => Some("ko"),
        'さ' => Some("sa"), 'し' => Some("shi"), 'す' => Some("su"), 'せ' => Some("se"), 'そ' => Some("so"),
        'た' => Some("ta"), 'ち' => Some("chi"), 'つ' => Some("tsu"), 'て' => Some("te"), 'と' => Some("to"),
        'な' => Some("na"), 'に' => Some("ni"), 'ぬ' => Some("nu"), 'ね' => Some("ne"), 'の' => Some("no"),
        'は' => Some("ha"), 'ひ' => Some("hi"), 'ふ' => Some("fu"), 'へ' => Some("he"), 'ほ' => Some("ho"),
        'ま' => Some("ma"), 'み' => Some("mi"), 'む' => Some("mu"), 'め' => Some("me"), 'も' => Some("mo"),
        'や' => Some("ya"), 'ゆ' => Some("yu"), 'よ' => Some("yo"),
        'ら' => Some("ra"), 'り' => Some("ri"), 'る' => Some("ru"), 'れ' => Some("re"), 'ろ' => Some("ro"),
        'わ' => Some("wa"), 'ゐ' => Some("i"), 'ゑ' => Some("e"), 'を' => Some("o"), 'ん' => Some("n"),
        'が' => Some("ga"), 'ぎ' => Some("gi"), 'ぐ' => Some("gu"), 'げ' => Some("ge"), 'ご' => Some("go"),
        'ざ' => Some("za"), 'じ' => Some("ji"), 'ず' => Some("zu"), 'ぜ' => Some("ze"), 'ぞ' => Some("zo"),
        'だ' => Some("da"), 'ぢ' => Some("ji"), 'づ' => Some("zu"), 'で' => Some("de"), 'ど' => Some("do"),
        'ば' => Some("ba"), 'び' => Some("bi"), 'ぶ' => Some("bu"), 'べ' => Some("be"), 'ぼ' => Some("bo"),
        'ぱ' => Some("pa"), 'ぴ' => Some("pi"), 'ぷ' => Some("pu"), 'ぺ' => Some("pe"), 'ぽ' => Some("po"),
        'ぁ' => Some("a"), 'ぃ' => Some("i"), 'ぅ' => Some("u"), 'ぇ' => Some("e"), 'ぉ' => Some("o"),
        'ゎ' => Some("wa"), 'ゔ' => Some("vu"),
        _ => None,
    }
}

fn small_y(c: char) -> Option<&'static str> {
    match c {
        'ゃ' => Some("a"),
        'ゅ' => Some("u"),
        'ょ' => Some("o"),
        _ => None,
    }
}

fn cyrillic_map(c: char) -> Option<&'static str> {
    match c {
        'а' => Some("a"), 'б' => Some("b"), 'в' => Some("v"), 'г' => Some("g"), 'д' => Some("d"),
        'е' => Some("e"), 'ё' => Some("yo"), 'ж' => Some("zh"), 'з' => Some("z"), 'и' => Some("i"),
        'й' => Some("y"), 'к' => Some("k"), 'л' => Some("l"), 'м' => Some("m"), 'н' => Some("n"),
        'о' => Some("o"), 'п' => Some("p"), 'р' => Some("r"), 'с' => Some("s"), 'т' => Some("t"),
        'у' => Some("u"), 'ф' => Some("f"), 'х' => Some("kh"), 'ц' => Some("ts"), 'ч' => Some("ch"),
        'ш' => Some("sh"), 'щ' => Some("shch"), 'ъ' => Some(""), 'ы' => Some("y"), 'ь' => Some(""),
        'э' => Some("e"), 'ю' => Some("yu"), 'я' => Some("ya"), 'і' => Some("i"), 'ї' => Some("yi"),
        'є' => Some("ye"), 'ґ' => Some("g"), 'ў' => Some("u"),
        _ => None,
    }
}

fn greek_map(c: char) -> Option<&'static str> {
    match c {
        'α' | 'ά' => Some("a"),
        'β' => Some("v"),
        'γ' => Some("g"),
        'δ' => Some("d"),
        'ε' | 'έ' => Some("e"),
        'ζ' => Some("z"),
        'η' | 'ή' => Some("i"),
        'θ' => Some("th"),
        'ι' | 'ί' | 'ΐ' => Some("i"),
        'κ' => Some("k"),
        'λ' => Some("l"),
        'μ' => Some("m"),
        'ν' => Some("n"),
        'ξ' => Some("x"),
        'ο' | 'ό' => Some("o"),
        'π' => Some("p"),
        'ρ' => Some("r"),
        'σ' | 'ς' => Some("s"),
        'τ' => Some("t"),
        'υ' | 'ύ' | 'ΰ' => Some("y"),
        'φ' => Some("f"),
        'χ' => Some("ch"),
        'ψ' => Some("ps"),
        'ω' | 'ώ' => Some("o"),
        _ => None,
    }
}

fn is_hangul(c: char) -> bool {
    matches!(c as u32, 0xAC00..=0xD7A3)
}

fn is_katakana(c: char) -> bool {
    matches!(c as u32, 0x30A1..=0x30F6)
}

fn to_hiragana(c: char) -> char {
    if is_katakana(c) {
        char::from_u32((c as u32) - 0x60).unwrap_or(c)
    } else {
        c
    }
}

fn is_kana(c: char) -> bool {
    kana_map(c).is_some() || small_y(c).is_some() || c == 'っ' || c == 'ー'
}

fn map_alphabet(c: char, map_fn: fn(char) -> Option<&'static str>) -> Option<String> {
    let lower = c.to_lowercase().next()?;
    let out = map_fn(lower)?;
    if c != lower && !out.is_empty() {
        let mut chars = out.chars();
        let first = chars.next()?.to_uppercase();
        Some(format!("{}{}", first, chars.as_str()))
    } else {
        Some(out.to_string())
    }
}

fn has_supported_chars(text: &str) -> bool {
    text.chars().any(|c| {
        let code = c as u32;
        (0xAC00..=0xD7A3).contains(&code)        // Hangul
            || (0x3040..=0x309F).contains(&code) // Hiragana
            || (0x30A0..=0x30FF).contains(&code) // Katakana
            || (0x0400..=0x04FF).contains(&code) // Cyrillic
            || (0x0370..=0x03FF).contains(&code) // Greek
    })
}

pub fn romanize(text: &str) -> Option<String> {
    if text.is_empty() || !has_supported_chars(text) {
        return None;
    }

    let src: Vec<char> = text.chars().collect();
    let mut out = String::with_capacity(text.len() * 2);
    let mut pending = String::new();
    let mut geminate = false;
    let mut converted = false;

    let mut i = 0;
    while i < src.len() {
        let ch = src[i];

        if is_hangul(ch) {
            converted = true;
            let s = (ch as u32) - 0xAC00;
            let l = (s / 588) as usize;
            let v = ((s % 588) / 28) as usize;
            let t = (s % 28) as usize;

            let next = src.get(i + 1).copied();
            let next_silent = next.map_or(false, |n| is_hangul(n) && ((n as u32 - 0xAC00) / 588 == 11));

            let initial = if !pending.is_empty() && l == 11 {
                pending.clone()
            } else {
                INI[l].to_string()
            };
            pending.clear();

            if t == 27 && next_silent {
                out.push_str(&initial);
                out.push_str(MED[v]);
            } else if t > 0 && next_silent && liaison(t).is_some() {
                out.push_str(&initial);
                out.push_str(MED[v]);
                pending = liaison(t).unwrap().to_string();
            } else {
                out.push_str(&initial);
                out.push_str(MED[v]);
                out.push_str(FIN[t]);
            }
            i += 1;
            continue;
        }

        pending.clear();
        let hira = to_hiragana(ch);

        if is_kana(hira) {
            converted = true;
            if hira == 'っ' {
                geminate = true;
            } else if hira == 'ー' {
                // Duplicate last vowel in out
                if let Some(last_vowel) = out.chars().rev().find(|c| "aeiouAEIOU".contains(*c)) {
                    out.push(last_vowel.to_ascii_lowercase());
                }
            } else if let Some(small) = small_y(hira) {
                let cur = out.clone();
                if cur.ends_with("shi") || cur.ends_with("chi") || cur.ends_with("ji") {
                    out.pop(); // remove 'i'
                    out.push_str(small);
                } else if cur.ends_with('i') {
                    out.pop();
                    out.push('y');
                    out.push_str(small);
                } else {
                    out.push('y');
                    out.push_str(small);
                }
            } else if let Some(romaji) = kana_map(hira) {
                let romaji_str = if hira == 'ん' {
                    let next_romaji = src.get(i + 1).and_then(|n| kana_map(to_hiragana(*n))).unwrap_or("");
                    if next_romaji.starts_with(|c| "aeiouy".contains(c)) {
                        "n'"
                    } else {
                        romaji
                    }
                } else {
                    romaji
                };

                if geminate && !romaji_str.is_empty() {
                    if romaji_str.starts_with("ch") {
                        out.push('t');
                    } else if let Some(first_char) = romaji_str.chars().next() {
                        out.push(first_char);
                    }
                    geminate = false;
                }
                out.push_str(romaji_str);
            }
            i += 1;
            continue;
        }

        geminate = false;

        let code = ch as u32;
        if (0x0400..=0x04FF).contains(&code) {
            if let Some(cyr) = map_alphabet(ch, cyrillic_map) {
                converted = true;
                out.push_str(&cyr);
                i += 1;
                continue;
            }
        }

        if (0x0370..=0x03FF).contains(&code) {
            if let Some(gr) = map_alphabet(ch, greek_map) {
                converted = true;
                out.push_str(&gr);
                i += 1;
                continue;
            }
        }

        out.push(ch);
        i += 1;
    }

    if !converted {
        return None;
    }

    // Collapse multiple spaces and trim
    let mut collapsed = String::with_capacity(out.len());
    let mut in_space = false;
    for c in out.chars() {
        if c.is_whitespace() {
            if !in_space {
                collapsed.push(' ');
                in_space = true;
            }
        } else {
            collapsed.push(c);
            in_space = false;
        }
    }
    let trimmed = collapsed.trim();
    if trimmed.is_empty() {
        return None;
    }

    // Capitalize first character
    let mut chars = trimmed.chars();
    let first = chars.next()?.to_uppercase();
    Some(format!("{}{}", first, chars.as_str()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_korean_romanization() {
        assert_eq!(romanize("안녕하세요"), Some("Annyeonghaseyo".to_string()));
        assert_eq!(romanize("한글"), Some("Hangeul".to_string()));
    }

    #[test]
    fn test_japanese_kana_romanization() {
        assert_eq!(romanize("ありがとう"), Some("Arigatou".to_string()));
        assert_eq!(romanize("こんにちは"), Some("Konnichiha".to_string()));
        assert_eq!(romanize("トーキョー"), Some("Tookyoo".to_string()));
        assert_eq!(romanize("きっぷ"), Some("Kippu".to_string()));
    }

    #[test]
    fn test_cyrillic_romanization() {
        assert_eq!(romanize("Привет мир"), Some("Privet mir".to_string()));
    }

    #[test]
    fn test_greek_romanization() {
        assert_eq!(romanize("Καλημέρα"), Some("Kalimera".to_string()));
    }

    #[test]
    fn test_latin_unchanged_returns_none() {
        assert_eq!(romanize("Hello World"), None);
        assert_eq!(romanize("12345"), None);
    }
}
