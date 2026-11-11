pub fn normalize_string(s: &str) -> String {
    let lower = s.to_lowercase();
    let mut out = String::with_capacity(lower.len());

    for c in lower.chars() {
        match c {
            'à' | 'á' | 'â' | 'ã' | 'ä' | 'å' => out.push('a'),
            'è' | 'é' | 'ê' | 'ë' => out.push('e'),
            'ì' | 'í' | 'î' | 'ï' => out.push('i'),
            'ò' | 'ó' | 'ô' | 'õ' | 'ö' => out.push('o'),
            'ù' | 'ú' | 'û' | 'ü' => out.push('u'),
            'ñ' => out.push('n'),
            'ç' => out.push('c'),
            c if c.is_alphanumeric() || c.is_whitespace() => out.push(c),
            _ => out.push(' '),
        }
    }

    // Collapse spaces
    let mut cleaned = String::with_capacity(out.len());
    let mut in_space = false;
    for c in out.chars() {
        if c.is_whitespace() {
            if !in_space {
                cleaned.push(' ');
                in_space = true;
            }
        } else {
            cleaned.push(c);
            in_space = false;
        }
    }
    cleaned.trim().to_string()
}

pub fn jaro_winkler_similarity(s1: &str, s2: &str) -> f32 {
    if s1 == s2 {
        return 1.0;
    }
    if s1.is_empty() || s2.is_empty() {
        return 0.0;
    }

    let a: Vec<char> = s1.chars().collect();
    let b: Vec<char> = s2.chars().collect();

    let a_len = a.len();
    let b_len = b.len();

    let match_window = (a_len.max(b_len) / 2).saturating_sub(1);

    let mut a_matches = vec![false; a_len];
    let mut b_matches = vec![false; b_len];

    let mut matches = 0;
    for i in 0..a_len {
        let start = i.saturating_sub(match_window);
        let end = (i + match_window + 1).min(b_len);

        for j in start..end {
            if b_matches[j] || a[i] != b[j] {
                continue;
            }
            a_matches[i] = true;
            b_matches[j] = true;
            matches += 1;
            break;
        }
    }

    if matches == 0 {
        return 0.0;
    }

    let mut transpositions = 0;
    let mut k = 0;
    for i in 0..a_len {
        if !a_matches[i] {
            continue;
        }
        while !b_matches[k] {
            k += 1;
        }
        if a[i] != b[k] {
            transpositions += 1;
        }
        k += 1;
    }

    let m = matches as f32;
    let jaro = ((m / a_len as f32) + (m / b_len as f32) + ((m - (transpositions as f32 / 2.0)) / m)) / 3.0;

    // Winkler prefix bonus
    let mut prefix = 0;
    for (ca, cb) in a.iter().zip(b.iter()) {
        if ca == cb && prefix < 4 {
            prefix += 1;
        } else {
            break;
        }
    }

    jaro + (prefix as f32 * 0.1 * (1.0 - jaro))
}

pub fn strip_track_decorations(title: &str) -> String {
    let mut t = title.to_string();
    let patterns = [
        "official video", "official audio", "official music video",
        "remastered", "remaster", "audio", "video", "lyrics", "lyric video",
        "hd", "4k", "hq", "live",
    ];

    let lower = t.to_lowercase();
    for p in &patterns {
        if let Some(idx) = lower.find(p) {
            // If enclosed in parentheses or brackets
            if idx > 0 {
                let bytes = lower.as_bytes();
                let before = bytes[idx - 1];
                if before == b'(' || before == b'[' {
                    if let Some(close_idx) = lower[idx..].find(|c| c == ')' || c == ']') {
                        let full_close = idx + close_idx;
                        t = format!("{}{}", &title[..idx - 1], &title[full_close + 1..]);
                    }
                }
            }
        }
    }
    t
}

pub fn fuzzy_match_track(
    target_artist: &str,
    target_title: &str,
    target_duration_sec: i32,
    cand_artist: &str,
    cand_title: &str,
    cand_duration_sec: i32,
) -> f32 {
    let clean_target_title = normalize_string(&strip_track_decorations(target_title));
    let clean_cand_title = normalize_string(&strip_track_decorations(cand_title));

    let clean_target_artist = normalize_string(target_artist.split(|c| c == ',' || c == '&').next().unwrap_or(target_artist));
    let clean_cand_artist = normalize_string(cand_artist.split(|c| c == ',' || c == '&').next().unwrap_or(cand_artist));

    let title_sim = jaro_winkler_similarity(&clean_target_title, &clean_cand_title);
    let artist_sim = jaro_winkler_similarity(&clean_target_artist, &clean_cand_artist);

    // Exact containment bonuses
    let title_containment = if clean_target_title.contains(&clean_cand_title) || clean_cand_title.contains(&clean_target_title) {
        0.15
    } else {
        0.0
    };

    let artist_containment = if clean_target_artist.contains(&clean_cand_artist) || clean_cand_artist.contains(&clean_target_artist) {
        0.15
    } else {
        0.0
    };

    let effective_title_score = (title_sim + title_containment).min(1.0);
    let effective_artist_score = (artist_sim + artist_containment).min(1.0);

    let mut score = (effective_title_score * 50.0) + (effective_artist_score * 35.0);

    // Duration score
    if target_duration_sec > 0 && cand_duration_sec > 0 {
        let diff = (target_duration_sec - cand_duration_sec).abs();
        if diff <= 3 {
            score += 15.0;
        } else if diff <= 10 {
            score += 8.0;
        } else if diff <= 20 {
            score += 2.0;
        } else if diff > 40 {
            score -= 15.0;
        }
    } else {
        score += 7.5; // neutral duration score
    }

    score.clamp(0.0, 100.0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_jaro_winkler_exact_and_similar() {
        assert_eq!(jaro_winkler_similarity("Queen", "Queen"), 1.0);
        assert!(jaro_winkler_similarity("Bohemian Rhapsody", "Bohemian Rhapsody (Official Video)") > 0.8);
        assert!(jaro_winkler_similarity("Taylor Swift", "Taylor Swift Official") > 0.8);
    }

    #[test]
    fn test_strip_decorations() {
        let stripped = strip_track_decorations("Song Name (Official Audio)");
        assert_eq!(stripped.trim(), "Song Name");
    }

    #[test]
    fn test_fuzzy_match_track_high_score() {
        let score = fuzzy_match_track(
            "Coldplay",
            "Yellow",
            269,
            "Coldplay",
            "Yellow (Official Video)",
            268,
        );
        assert!(score > 80.0, "Score was {}", score);
    }

    #[test]
    fn test_fuzzy_match_track_mismatched() {
        let score = fuzzy_match_track(
            "Adele",
            "Hello",
            295,
            "Lionel Richie",
            "Hello",
            245,
        );
        assert!(score < 60.0, "Score was {}", score);
    }
}
