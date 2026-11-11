use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StreamInfo {
    pub url: String,
    pub mime_type: String,
    pub bitrate: i32,
    pub duration_seconds: i64,
}
