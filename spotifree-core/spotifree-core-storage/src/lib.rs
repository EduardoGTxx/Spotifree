pub mod cache;
pub mod tags;

pub use cache::NativeAudioCache;
pub use tags::{read_audio_tags, NativeTagInfo};

uniffi::setup_scaffolding!();
