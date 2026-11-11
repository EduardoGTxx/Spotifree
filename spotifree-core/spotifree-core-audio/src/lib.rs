pub mod crossfade;
pub mod equalizer;
pub mod processor;
pub mod reverb;

pub use processor::NativeAudioProcessor;

uniffi::setup_scaffolding!();
