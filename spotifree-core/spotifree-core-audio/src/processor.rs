use crate::equalizer::FiveBandEqualizer;
use crate::reverb::SchroederReverb;
use parking_lot::Mutex;

pub struct NativeAudioProcessor {
    sample_rate: i32,
    channels: i32,
    eq: Mutex<FiveBandEqualizer>,
    reverb: Mutex<SchroederReverb>,
}

impl NativeAudioProcessor {
    pub fn new(sample_rate: i32, channels: i32) -> Self {
        let sr = sample_rate.max(8000) as f32;
        Self {
            sample_rate,
            channels: channels.max(1),
            eq: Mutex::new(FiveBandEqualizer::new(sr)),
            reverb: Mutex::new(SchroederReverb::new(sr)),
        }
    }

    pub fn sample_rate(&self) -> i32 {
        self.sample_rate
    }

    pub fn channels(&self) -> i32 {
        self.channels
    }

    pub fn set_reverb_enabled(&self, enabled: bool) {
        self.reverb.lock().enabled = enabled;
    }

    pub fn set_reverb_room_size(&self, size: f32) {
        self.reverb.lock().set_room_size(size);
    }

    pub fn set_equalizer_band(&self, band: i32, gain_db: f32) {
        if band >= 0 {
            self.eq.lock().set_band(band as usize, gain_db);
        }
    }

    pub fn process_pcm(&self, input_pcm: &[u8]) -> Vec<u8> {
        if input_pcm.len() < 2 {
            return input_pcm.to_vec();
        }

        let num_samples = input_pcm.len() / 2;
        let mut output = vec![0u8; input_pcm.len()];

        let mut eq = self.eq.lock();
        let mut rev = self.reverb.lock();

        let eq_on = eq.enabled;
        let rev_on = rev.enabled;

        if !eq_on && !rev_on {
            output.copy_from_slice(input_pcm);
            return output;
        }

        for i in 0..num_samples {
            let offset = i * 2;
            let sample_i16 = i16::from_le_bytes([input_pcm[offset], input_pcm[offset + 1]]);
            let mut sample_f32 = (sample_i16 as f32) / 32768.0;

            if eq_on {
                sample_f32 = eq.process_sample(sample_f32);
            }
            if rev_on {
                sample_f32 = rev.process_sample(sample_f32);
            }

            let clamped = (sample_f32 * 32767.0).clamp(-32768.0, 32767.0) as i16;
            let bytes = clamped.to_le_bytes();
            output[offset] = bytes[0];
            output[offset + 1] = bytes[1];
        }

        output
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_pcm_processing_bypass() {
        let proc = NativeAudioProcessor::new(44100, 2);
        let input = vec![0x10, 0x20, 0x30, 0x40];
        let out = proc.process_pcm(&input);
        assert_eq!(input, out);
    }

    #[test]
    fn test_pcm_processing_with_reverb() {
        let proc = NativeAudioProcessor::new(44100, 2);
        proc.set_reverb_enabled(true);
        proc.set_reverb_room_size(0.8);
        let input = vec![0x7f, 0x7f, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00];
        let out = proc.process_pcm(&input);
        assert_eq!(input.len(), out.len());
    }
}
