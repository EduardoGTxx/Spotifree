use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct NativeLrcLine {
    pub time_sec: f64,
    pub text: String,
    pub is_instrumental: bool,
}

fn parse_frac(s: &str) -> f64 {
    if s.is_empty() {
        return 0.0;
    }
    let mut padded = s.to_string();
    while padded.len() < 3 {
        padded.push('0');
    }
    let millis: f64 = padded[..3].parse().unwrap_or(0.0);
    millis / 1000.0
}

fn parse_time_tag(tag: &str) -> Option<(f64, usize)> {
    // Looks for [mm:ss] or [mm:ss.xx]
    if !tag.starts_with('[') {
        return None;
    }
    let end_idx = tag.find(']')?;
    let content = &tag[1..end_idx];

    let parts: Vec<&str> = content.split(':').collect();
    if parts.len() < 2 {
        return None;
    }

    let minutes: f64 = parts[0].parse().ok()?;
    let (seconds, frac) = if let Some(dot_idx) = parts[1].find(|c| c == '.' || c == ':') {
        let sec: f64 = parts[1][..dot_idx].parse().ok()?;
        let fraction = parse_frac(&parts[1][dot_idx + 1..]);
        (sec, fraction)
    } else {
        let sec: f64 = parts[1].parse().ok()?;
        (sec, 0.0)
    };

    Some((minutes * 60.0 + seconds + frac, end_idx + 1))
}

pub fn parse_lrc(lrc: &str) -> Vec<NativeLrcLine> {
    if lrc.is_empty() {
        return Vec::new();
    }

    let mut offset_ms: i64 = 0;
    let mut result: Vec<NativeLrcLine> = Vec::new();

    let clean = lrc.replace('\u{FEFF}', "").replace("\r\n", "\n").replace('\r', "\n");

    for raw in clean.lines() {
        let line = raw.trim();
        if line.is_empty() {
            continue;
        }

        // Check [offset: +/-123]
        if line.to_lowercase().starts_with("[offset:") && line.ends_with(']') {
            let inner = &line[8..line.len() - 1].trim();
            if let Ok(val) = inner.parse::<i64>() {
                offset_ms = val;
            }
            continue;
        }

        let mut times = Vec::new();
        let mut curr_idx = 0;

        while curr_idx < line.len() {
            if let Some((time_val, tag_len)) = parse_time_tag(&line[curr_idx..]) {
                times.push(time_val);
                curr_idx += tag_len;
            } else {
                break;
            }
        }

        if times.is_empty() {
            continue;
        }

        let body = line[curr_idx..].trim();
        let is_inst = body.is_empty();
        let text = if is_inst { "♪".to_string() } else { body.to_string() };

        for t in times {
            let adjusted_time = (t - (offset_ms as f64) / 1000.0).max(0.0);
            result.push(NativeLrcLine {
                time_sec: adjusted_time,
                text: text.clone(),
                is_instrumental: is_inst,
            });
        }
    }

    result.sort_by(|a, b| a.time_sec.partial_cmp(&b.time_sec).unwrap_or(std::cmp::Ordering::Equal));

    // Deduplicate consecutive instrumental lines
    let mut compact: Vec<NativeLrcLine> = Vec::with_capacity(result.len());
    for item in result {
        if item.is_instrumental && compact.last().map_or(false, |last| last.is_instrumental) {
            continue;
        }
        compact.push(item);
    }

    compact
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_lrc_standard() {
        let lrc = r#"
[00:01.00]Line 1
[00:04.50]Line 2
[00:10.00]
"#;
        let lines = parse_lrc(lrc);
        assert_eq!(lines.len(), 3);
        assert_eq!(lines[0].time_sec, 1.0);
        assert_eq!(lines[0].text, "Line 1");
        assert!(!lines[0].is_instrumental);

        assert_eq!(lines[1].time_sec, 4.5);
        assert_eq!(lines[1].text, "Line 2");

        assert_eq!(lines[2].time_sec, 10.0);
        assert_eq!(lines[2].text, "♪");
        assert!(lines[2].is_instrumental);
    }

    #[test]
    fn test_multiple_timestamps_per_line() {
        let lrc = "[00:02.00][00:05.00]Chorus line";
        let lines = parse_lrc(lrc);
        assert_eq!(lines.len(), 2);
        assert_eq!(lines[0].time_sec, 2.0);
        assert_eq!(lines[0].text, "Chorus line");
        assert_eq!(lines[1].time_sec, 5.0);
        assert_eq!(lines[1].text, "Chorus line");
    }
}
