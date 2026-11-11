pub mod error;

use std::sync::Arc;

pub use error::SpotifreeError;
pub use spotifree_core_network::NativeStreamInfo;

uniffi::setup_scaffolding!();

#[derive(uniffi::Record, Clone, Debug, PartialEq)]
pub struct NativeLrcLine {
    pub time_sec: f64,
    pub text: String,
    pub is_instrumental: bool,
}

#[derive(uniffi::Record, Clone, Debug, PartialEq)]
pub struct NativeTagInfo {
    pub title: String,
    pub artist: String,
    pub album: String,
    pub year: String,
    pub duration_sec: i32,
}

#[uniffi::export]
pub fn spotifree_core_version() -> String {
    "0.1.0".to_string()
}

#[uniffi::export]
pub fn spotifree_core_ping(payload: String) -> Result<String, SpotifreeError> {
    if payload.is_empty() {
        return Err(SpotifreeError::Internal {
            reason: "Payload cannot be empty".to_string(),
        });
    }
    Ok(format!("echo: {}", payload))
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn resolve_stream_url(video_id: String) -> Result<NativeStreamInfo, SpotifreeError> {
    spotifree_core_network::resolve_stream(&video_id)
        .await
        .map_err(|e| SpotifreeError::Network { reason: e })
}

#[uniffi::export]
pub fn romanize_text(text: String) -> Option<String> {
    spotifree_core_lyrics::romanize(&text)
}

#[uniffi::export]
pub fn parse_lrc_lyrics(lrc_content: String) -> Vec<NativeLrcLine> {
    spotifree_core_lyrics::parse_lrc(&lrc_content)
        .into_iter()
        .map(|l| NativeLrcLine {
            time_sec: l.time_sec,
            text: l.text,
            is_instrumental: l.is_instrumental,
        })
        .collect()
}

#[uniffi::export]
pub fn fuzzy_match_track(
    target_artist: String,
    target_title: String,
    target_duration_sec: i32,
    cand_artist: String,
    cand_title: String,
    cand_duration_sec: i32,
) -> f32 {
    spotifree_core_network::fuzzy_match_track(
        &target_artist,
        &target_title,
        target_duration_sec,
        &cand_artist,
        &cand_title,
        cand_duration_sec,
    )
}

#[uniffi::export]
pub fn calculate_loudness_lufs(pcm: Vec<u8>, sample_rate: i32) -> f32 {
    spotifree_core_audio::calculate_loudness_lufs(&pcm, sample_rate)
}

#[uniffi::export]
pub fn calculate_gain_adjustment(target_lufs: f32, current_lufs: f32) -> f32 {
    spotifree_core_audio::calculate_gain_adjustment(target_lufs, current_lufs)
}

#[uniffi::export]
pub fn calculate_true_peak(pcm: Vec<u8>) -> f32 {
    spotifree_core_audio::calculate_true_peak(&pcm)
}

#[uniffi::export]
pub fn read_audio_tags(file_path: String) -> Result<NativeTagInfo, SpotifreeError> {
    let tags = spotifree_core_storage::read_audio_tags(&file_path)
        .map_err(|e| SpotifreeError::Storage { reason: e })?;
    Ok(NativeTagInfo {
        title: tags.title,
        artist: tags.artist,
        album: tags.album,
        year: tags.year,
        duration_sec: tags.duration_sec,
    })
}

#[uniffi::export]
pub fn generate_audio_fingerprint(pcm: Vec<u8>, sample_rate: i32) -> Vec<String> {
    spotifree_core_audio::generate_fingerprint_hashes(&pcm, sample_rate)
}

#[derive(uniffi::Object)]
pub struct AudioCacheBridge {
    inner: spotifree_core_storage::NativeAudioCache,
}

#[uniffi::export]
impl AudioCacheBridge {
    #[uniffi::constructor]
    pub fn new(cache_dir: String, max_bytes: i64) -> Result<Arc<Self>, SpotifreeError> {
        let cache = spotifree_core_storage::NativeAudioCache::new(cache_dir, max_bytes)
            .map_err(|e| SpotifreeError::Storage { reason: e })?;
        Ok(Arc::new(Self { inner: cache }))
    }

    pub fn write_chunk(&self, key: String, offset: i64, data: Vec<u8>) -> Result<(), SpotifreeError> {
        self.inner
            .write_chunk(&key, offset.max(0) as u64, &data)
            .map_err(|e| SpotifreeError::Storage { reason: e })
    }

    pub fn read_range(&self, key: String, offset: i64, length: i32) -> Result<Option<Vec<u8>>, SpotifreeError> {
        self.inner
            .read_range(&key, offset.max(0) as u64, length.max(0) as usize)
            .map_err(|e| SpotifreeError::Storage { reason: e })
    }

    pub fn is_cached(&self, key: String) -> bool {
        self.inner.is_cached(&key)
    }
}

#[derive(uniffi::Object)]
pub struct AudioProcessorBridge {
    inner: spotifree_core_audio::NativeAudioProcessor,
}

#[uniffi::export]
impl AudioProcessorBridge {
    #[uniffi::constructor]
    pub fn new(sample_rate: i32, channels: i32) -> Arc<Self> {
        Arc::new(Self {
            inner: spotifree_core_audio::NativeAudioProcessor::new(sample_rate, channels),
        })
    }

    pub fn set_reverb_enabled(&self, enabled: bool) {
        self.inner.set_reverb_enabled(enabled);
    }

    pub fn set_reverb_room_size(&self, size: f32) {
        self.inner.set_reverb_room_size(size);
    }

    pub fn set_equalizer_band(&self, band: i32, gain_db: f32) {
        self.inner.set_equalizer_band(band, gain_db);
    }

    pub fn process_pcm(&self, input_pcm: Vec<u8>) -> Vec<u8> {
        self.inner.process_pcm(&input_pcm)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_version() {
        assert_eq!(spotifree_core_version(), "0.1.0");
    }

    #[test]
    fn test_ping_success() {
        assert_eq!(
            spotifree_core_ping("hello".to_string()).unwrap(),
            "echo: hello"
        );
    }

    #[test]
    fn test_ping_empty() {
        assert!(spotifree_core_ping("".to_string()).is_err());
    }

    #[test]
    fn test_romanize_export() {
        assert_eq!(romanize_text("안녕하세요".to_string()), Some("Annyeonghaseyo".to_string()));
    }

    #[test]
    fn test_fuzzy_match_export() {
        let score = fuzzy_match_track(
            "Coldplay".to_string(),
            "Yellow".to_string(),
            260,
            "Coldplay".to_string(),
            "Yellow".to_string(),
            260,
        );
        assert!(score >= 90.0);
    }
}
