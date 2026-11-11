pub mod error;

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
