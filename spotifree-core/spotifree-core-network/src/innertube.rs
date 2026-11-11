use crate::cipher::{decipher_signature, parse_query_string};
use crate::models::NativeStreamInfo;
use serde_json::Value;

pub async fn resolve_stream(video_id: &str) -> Result<NativeStreamInfo, String> {
    if video_id.is_empty() {
        return Err("Video ID cannot be empty".to_string());
    }

    let client = reqwest::Client::builder()
        .user_agent("com.google.android.apps.youtube.music/6.42.52 (Linux; U; Android 14)")
        .build()
        .map_err(|e| e.to_string())?;

    let payload = serde_json::json!({
        "context": {
            "client": {
                "clientName": "ANDROID_MUSIC",
                "clientVersion": "6.42.52",
                "androidSdkVersion": 34,
                "hl": "en",
                "gl": "US"
            }
        },
        "videoId": video_id
    });

    let resp = client
        .post("https://music.youtube.com/youtubei/v1/player")
        .json(&payload)
        .send()
        .await
        .map_err(|e| format!("HTTP request failed: {}", e))?;

    if !resp.status().is_success() {
        return Err(format!("InnerTube returned status: {}", resp.status()));
    }

    let json: Value = resp
        .json()
        .await
        .map_err(|e| format!("Failed to parse JSON: {}", e))?;

    parse_player_response(video_id, &json)
}

pub fn parse_player_response(video_id: &str, json: &Value) -> Result<NativeStreamInfo, String> {
    let formats = json["streamingData"]["adaptiveFormats"]
        .as_array()
        .ok_or_else(|| "No adaptiveFormats found in streamingData".to_string())?;

    // Find the best audio format (mimeType starts with audio/)
    for fmt in formats {
        let mime = fmt["mimeType"].as_str().unwrap_or("");
        if mime.starts_with("audio/") {
            let itag = fmt["itag"].as_i64().unwrap_or(140) as i32;
            let bitrate = fmt["bitrate"].as_i64().unwrap_or(128000) as i32;
            let duration = fmt["approxDurationMs"]
                .as_str()
                .and_then(|s| s.parse::<i64>().ok())
                .unwrap_or(0)
                / 1000;

            if let Some(direct_url) = fmt["url"].as_str() {
                return Ok(NativeStreamInfo {
                    video_id: video_id.to_string(),
                    url: direct_url.to_string(),
                    mime_type: mime.to_string(),
                    itag,
                    bitrate,
                    duration_seconds: duration,
                });
            } else if let Some(sig_cipher) = fmt["signatureCipher"].as_str() {
                let params = parse_query_string(sig_cipher);
                if let (Some(u), Some(s)) = (params.get("url"), params.get("s")) {
                    let sig = decipher_signature(s);
                    let sp = params.get("sp").map(|s| s.as_str()).unwrap_or("sig");
                    let final_url = format!("{}&{}={}", u, sp, sig);
                    return Ok(NativeStreamInfo {
                        video_id: video_id.to_string(),
                        url: final_url,
                        mime_type: mime.to_string(),
                        itag,
                        bitrate,
                        duration_seconds: duration,
                    });
                }
            }
        }
    }

    Err("No compatible audio stream found".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_player_response_with_direct_url() {
        let json = serde_json::json!({
            "streamingData": {
                "adaptiveFormats": [
                    {
                        "itag": 140,
                        "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                        "bitrate": 131072,
                        "approxDurationMs": "210000",
                        "url": "https://googlevideo.com/videoplayback?id=123"
                    }
                ]
            }
        });

        let res = parse_player_response("test_video", &json).unwrap();
        assert_eq!(res.video_id, "test_video");
        assert_eq!(res.url, "https://googlevideo.com/videoplayback?id=123");
        assert_eq!(res.itag, 140);
        assert_eq!(res.duration_seconds, 210);
    }
}
