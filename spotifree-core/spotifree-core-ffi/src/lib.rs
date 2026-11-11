pub mod error;

use std::sync::Arc;

pub use error::SpotifreeError;
pub use spotifree_core_network::NativeStreamInfo;

uniffi::setup_scaffolding!();

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
}
