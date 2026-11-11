pub mod crossfade;
pub mod equalizer;
pub mod fingerprint;
pub mod loudness;
pub mod processor;
pub mod reverb;

pub use fingerprint::generate_fingerprint_hashes;
pub use loudness::{calculate_gain_adjustment, calculate_loudness_lufs, calculate_true_peak};
pub use processor::NativeAudioProcessor;

uniffi::setup_scaffolding!();
