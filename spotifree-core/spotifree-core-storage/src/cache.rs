use parking_lot::RwLock;
use std::collections::HashMap;
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::PathBuf;
use std::sync::Arc;

pub struct NativeAudioCache {
    cache_dir: PathBuf,
    max_bytes: u64,
    index: Arc<RwLock<HashMap<String, Vec<(u64, u64)>>>>, // key -> list of (offset, length)
}

impl NativeAudioCache {
    pub fn new(cache_dir: String, max_bytes: i64) -> Result<Self, String> {
        let path = PathBuf::from(cache_dir);
        fs::create_dir_all(&path).map_err(|e| format!("Failed to create cache dir: {}", e))?;

        Ok(Self {
            cache_dir: path,
            max_bytes: max_bytes.max(1024 * 1024) as u64,
            index: Arc::new(RwLock::new(HashMap::new())),
        })
    }

    fn file_path(&self, key: &str) -> PathBuf {
        let sanitized: String = key
            .chars()
            .map(|c| if c.is_alphanumeric() || c == '_' || c == '-' { c } else { '_' })
            .collect();
        self.cache_dir.join(format!("{}.audio", sanitized))
    }

    pub fn write_chunk(&self, key: &str, offset: u64, data: &[u8]) -> Result<(), String> {
        if data.is_empty() {
            return Ok(());
        }

        let p = self.file_path(key);
        let mut file = OpenOptions::new()
            .create(true)
            .write(true)
            .open(&p)
            .map_err(|e| format!("Cannot open file for write: {}", e))?;

        file.seek(SeekFrom::Start(offset))
            .map_err(|e| format!("Seek failed: {}", e))?;
        file.write_all(data)
            .map_err(|e| format!("Write failed: {}", e))?;

        let mut idx = self.index.write();
        let ranges = idx.entry(key.to_string()).or_default();
        ranges.push((offset, data.len() as u64));

        Ok(())
    }

    pub fn read_range(&self, key: &str, offset: u64, length: usize) -> Result<Option<Vec<u8>>, String> {
        let p = self.file_path(key);
        if !p.exists() {
            return Ok(None);
        }

        let mut file = File::open(&p).map_err(|e| format!("Cannot open file for read: {}", e))?;
        let file_len = file.metadata().map_err(|e| e.to_string())?.len();

        if offset >= file_len {
            return Ok(None);
        }

        let to_read = ((file_len - offset) as usize).min(length);
        file.seek(SeekFrom::Start(offset))
            .map_err(|e| format!("Seek failed: {}", e))?;

        let mut buf = vec![0u8; to_read];
        file.read_exact(&mut buf)
            .map_err(|e| format!("Read failed: {}", e))?;

        Ok(Some(buf))
    }

    pub fn is_cached(&self, key: &str) -> bool {
        self.file_path(key).exists()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_cache_write_and_read() {
        let temp_dir = std::env::temp_dir().join("spotifree_cache_test_1");
        let cache = NativeAudioCache::new(temp_dir.to_str().unwrap().to_string(), 10 * 1024 * 1024).unwrap();
        let sample = b"spotifree_native_audio_chunk";
        cache.write_chunk("test_track_1", 0, sample).unwrap();

        let read = cache.read_range("test_track_1", 0, sample.len()).unwrap();
        assert_eq!(read.unwrap(), sample);
    }
}
