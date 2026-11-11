use thiserror::Error;

#[derive(Error, Debug, uniffi::Error)]
pub enum SpotifreeError {
    #[error("Network error: {reason}")]
    Network { reason: String },
    #[error("Audio DSP error: {reason}")]
    AudioDsp { reason: String },
    #[error("Storage error: {reason}")]
    Storage { reason: String },
    #[error("Internal error: {reason}")]
    Internal { reason: String },
}
