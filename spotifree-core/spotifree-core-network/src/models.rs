use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct NativeStreamInfo {
    pub video_id: String,
    pub url: String,
    pub mime_type: String,
    pub itag: i32,
    pub bitrate: i32,
    pub duration_seconds: i64,
}
