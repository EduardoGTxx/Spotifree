use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::Path;

#[derive(Clone, Debug, PartialEq, Default)]
pub struct NativeTagInfo {
    pub title: String,
    pub artist: String,
    pub album: String,
    pub year: String,
    pub duration_sec: i32,
}

pub fn read_audio_tags(file_path: &str) -> Result<NativeTagInfo, String> {
    let path = Path::new(file_path);
    if !path.exists() {
        return Err(format!("File does not exist: {}", file_path));
    }

    let mut file = File::open(path).map_err(|e| format!("Failed to open file: {}", e))?;
    let mut header = [0u8; 12];
    let bytes_read = file.read(&mut header).map_err(|e| e.to_string())?;

    if bytes_read >= 3 && &header[0..3] == b"ID3" {
        return parse_id3v2(&mut file, &header);
    } else if bytes_read >= 4 && &header[0..4] == b"fLaC" {
        return parse_flac(&mut file);
    } else if bytes_read >= 8 && (&header[4..8] == b"ftyp" || &header[4..8] == b"moov") {
        return parse_mp4(&mut file);
    }

    // Fallback: extract title & artist from filename
    fallback_from_filename(path)
}

fn synchsafe_to_u32(bytes: &[u8]) -> u32 {
    let mut val = 0u32;
    for &b in bytes.iter().take(4) {
        val = (val << 7) | ((b & 0x7F) as u32);
    }
    val
}

fn parse_id3v2(file: &mut File, header: &[u8; 12]) -> Result<NativeTagInfo, String> {
    let version = header[3];
    let tag_size = synchsafe_to_u32(&header[6..10]) as usize;

    let _ = file.seek(SeekFrom::Start(10));
    let mut tag_data = vec![0u8; tag_size.min(1024 * 1024)]; // read up to 1MB
    let _ = file.read_exact(&mut tag_data);

    let mut info = NativeTagInfo::default();
    let mut pos = 0;

    while pos + 10 < tag_data.len() {
        let frame_id = &tag_data[pos..pos + 4];
        if frame_id[0] == 0 {
            break; // padding reached
        }

        let frame_size = if version == 4 {
            synchsafe_to_u32(&tag_data[pos + 4..pos + 8]) as usize
        } else {
            u32::from_be_bytes([
                tag_data[pos + 4],
                tag_data[pos + 5],
                tag_data[pos + 6],
                tag_data[pos + 7],
            ]) as usize
        };

        pos += 10;
        if pos + frame_size > tag_data.len() {
            break;
        }

        let frame_bytes = &tag_data[pos..pos + frame_size];
        let text = decode_id3_text(frame_bytes);

        match frame_id {
            b"TIT2" => info.title = text,
            b"TPE1" => info.artist = text,
            b"TALB" => info.album = text,
            b"TYER" | b"TDRC" => {
                if info.year.is_empty() {
                    info.year = text.chars().take(4).collect();
                }
            }
            b"TLEN" => {
                if let Ok(ms) = text.parse::<i32>() {
                    info.duration_sec = ms / 1000;
                }
            }
            _ => {}
        }

        pos += frame_size;
    }

    Ok(info)
}

fn decode_id3_text(bytes: &[u8]) -> String {
    if bytes.is_empty() {
        return String::new();
    }
    let encoding = bytes[0];
    let data = &bytes[1..];

    match encoding {
        0 => {
            // ISO-8859-1 / ASCII
            data.iter()
                .take_while(|&&b| b != 0)
                .map(|&b| b as char)
                .collect::<String>()
                .trim()
                .to_string()
        }
        1 | 2 => {
            // UTF-16
            let u16s: Vec<u16> = data
                .chunks_exact(2)
                .map(|chunk| u16::from_le_bytes([chunk[0], chunk[1]]))
                .take_while(|&u| u != 0 && u != 0xFEFF)
                .collect();
            String::from_utf16_lossy(&u16s).trim().to_string()
        }
        3 => {
            // UTF-8
            String::from_utf8_lossy(data)
                .trim_matches('\0')
                .trim()
                .to_string()
        }
        _ => String::from_utf8_lossy(data).trim().to_string(),
    }
}

fn parse_flac(file: &mut File) -> Result<NativeTagInfo, String> {
    let mut info = NativeTagInfo::default();
    let mut buf = [0u8; 4];

    loop {
        if file.read_exact(&mut buf).is_err() {
            break;
        }
        let is_last = (buf[0] & 0x80) != 0;
        let block_type = buf[0] & 0x7F;
        let block_len = (((buf[1] as usize) << 16) | ((buf[2] as usize) << 8) | (buf[3] as usize)) as usize;

        if block_type == 0 {
            // STREAMINFO: 34 bytes
            let mut stream_info = vec![0u8; block_len];
            if file.read_exact(&mut stream_info).is_ok() && block_len >= 18 {
                let sample_rate = ((stream_info[10] as u32) << 12)
                    | ((stream_info[11] as u32) << 4)
                    | ((stream_info[12] as u32) >> 4);
                let total_samples = (((stream_info[13] as u64) & 0x0F) << 32)
                    | ((stream_info[14] as u64) << 24)
                    | ((stream_info[15] as u64) << 16)
                    | ((stream_info[16] as u64) << 8)
                    | (stream_info[17] as u64);
                if sample_rate > 0 {
                    info.duration_sec = (total_samples / (sample_rate as u64)) as i32;
                }
            }
        } else if block_type == 4 {
            // VORBIS_COMMENT
            let mut comment_data = vec![0u8; block_len];
            if file.read_exact(&mut comment_data).is_ok() {
                parse_vorbis_comment(&comment_data, &mut info);
            }
        } else {
            if file.seek(SeekFrom::Current(block_len as i64)).is_err() {
                break;
            }
        }

        if is_last {
            break;
        }
    }

    Ok(info)
}

fn parse_vorbis_comment(data: &[u8], info: &mut NativeTagInfo) {
    if data.len() < 8 {
        return;
    }
    let vendor_len = u32::from_le_bytes([data[0], data[1], data[2], data[3]]) as usize;
    let mut pos = 4 + vendor_len;
    if pos + 4 > data.len() {
        return;
    }

    let comment_count = u32::from_le_bytes([data[pos], data[pos + 1], data[pos + 2], data[pos + 3]]) as usize;
    pos += 4;

    for _ in 0..comment_count {
        if pos + 4 > data.len() {
            break;
        }
        let length = u32::from_le_bytes([data[pos], data[pos + 1], data[pos + 2], data[pos + 3]]) as usize;
        pos += 4;
        if pos + length > data.len() {
            break;
        }
        let comment_str = String::from_utf8_lossy(&data[pos..pos + length]);
        if let Some(eq_idx) = comment_str.find('=') {
            let key = comment_str[..eq_idx].to_uppercase();
            let val = comment_str[eq_idx + 1..].trim();
            match key.as_str() {
                "TITLE" => info.title = val.to_string(),
                "ARTIST" => info.artist = val.to_string(),
                "ALBUM" => info.album = val.to_string(),
                "DATE" | "YEAR" => info.year = val.chars().take(4).collect(),
                _ => {}
            }
        }
        pos += length;
    }
}

fn parse_mp4(file: &mut File) -> Result<NativeTagInfo, String> {
    let mut info = NativeTagInfo::default();
    let mut buf = vec![0u8; 128 * 1024]; // inspect initial 128KB
    let _ = file.seek(SeekFrom::Start(0));
    let read_bytes = file.read(&mut buf).unwrap_or(0);
    buf.truncate(read_bytes);

    // Look for mvhd to find duration
    if let Some(mvhd_idx) = find_subsequence(&buf, b"mvhd") {
        if mvhd_idx + 24 <= buf.len() {
            let version = buf[mvhd_idx + 4];
            if version == 0 && mvhd_idx + 20 <= buf.len() {
                let timescale = u32::from_be_bytes([
                    buf[mvhd_idx + 12],
                    buf[mvhd_idx + 13],
                    buf[mvhd_idx + 14],
                    buf[mvhd_idx + 15],
                ]);
                let duration = u32::from_be_bytes([
                    buf[mvhd_idx + 16],
                    buf[mvhd_idx + 17],
                    buf[mvhd_idx + 18],
                    buf[mvhd_idx + 19],
                ]);
                if timescale > 0 {
                    info.duration_sec = (duration / timescale) as i32;
                }
            }
        }
    }

    Ok(info)
}

fn find_subsequence(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    haystack.windows(needle.len()).position(|window| window == needle)
}

fn fallback_from_filename(path: &Path) -> Result<NativeTagInfo, String> {
    let stem = path
        .file_stem()
        .and_then(|s| s.to_str())
        .unwrap_or("Unknown");

    let parts: Vec<&str> = stem.split(" - ").collect();
    let (artist, title) = if parts.len() >= 2 {
        (parts[0].trim().to_string(), parts[1].trim().to_string())
    } else {
        (String::new(), stem.trim().to_string())
    };

    Ok(NativeTagInfo {
        title,
        artist,
        album: String::new(),
        year: String::new(),
        duration_sec: 0,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    #[test]
    fn test_id3_decode() {
        let text = decode_id3_text(&[3, b'T', b'e', b's', b't']);
        assert_eq!(text, "Test");
    }

    #[test]
    fn test_fallback_filename() {
        let path = Path::new("Pink Floyd - Comfortably Numb.mp3");
        let tags = fallback_from_filename(path).unwrap();
        assert_eq!(tags.artist, "Pink Floyd");
        assert_eq!(tags.title, "Comfortably Numb");
    }

    #[test]
    fn test_id3_parsing_simulated() {
        // Construct a synthetic ID3v2.3 tag
        let mut data = Vec::new();
        // ID3 header: "ID3", ver 3, rev 0, flags 0, size synchsafe
        data.extend_from_slice(b"ID3\x03\x00\x00");
        // Tag size: 100 bytes (synchsafe: 0, 0, 0, 100)
        data.extend_from_slice(&[0, 0, 0, 100]);

        // Frame TIT2: Title
        data.extend_from_slice(b"TIT2");
        data.extend_from_slice(&(6u32.to_be_bytes())); // size
        data.extend_from_slice(&[0, 0]); // flags
        data.extend_from_slice(&[0]); // encoding ISO-8859-1
        data.extend_from_slice(b"Hello");

        let temp_dir = std::env::temp_dir();
        let file_path = temp_dir.join("test_synthetic_id3.mp3");
        {
            let mut f = File::create(&file_path).unwrap();
            f.write_all(&data).unwrap();
        }

        let tags = read_audio_tags(file_path.to_str().unwrap()).unwrap();
        let _ = std::fs::remove_file(file_path);

        assert_eq!(tags.title, "Hello");
    }
}
