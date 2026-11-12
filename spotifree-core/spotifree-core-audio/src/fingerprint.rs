// Acoustic Audio Fingerprint Generation (Constellation Map / Peak Hashing)

use std::f32::consts::PI;

const WINDOW_SIZE: usize = 1024;
const HOP_SIZE: usize = 512;

// Four perceptual frequency ranges (in FFT bins assuming 44.1 kHz, 1024 FFT => ~43.06 Hz/bin)
// Band 0: 40 - 250 Hz (bins 1..6)
// Band 1: 250 - 500 Hz (bins 6..12)
// Band 2: 500 - 1500 Hz (bins 12..35)
// Band 3: 1500 - 4000 Hz (bins 35..93)
const FREQ_BANDS: [(usize, usize); 4] = [
    (1, 6),
    (6, 12),
    (12, 35),
    (35, 93),
];

struct TwiddleTable {
    cos: [f32; WINDOW_SIZE],
    sin: [f32; WINDOW_SIZE],
}

impl TwiddleTable {
    fn get() -> &'static Self {
        use std::sync::OnceLock;
        static TABLE: OnceLock<TwiddleTable> = OnceLock::new();
        TABLE.get_or_init(|| {
            let mut cos = [0.0f32; WINDOW_SIZE];
            let mut sin = [0.0f32; WINDOW_SIZE];
            for m in 0..WINDOW_SIZE {
                let angle = 2.0 * PI * (m as f32) / (WINDOW_SIZE as f32);
                cos[m] = angle.cos();
                sin[m] = angle.sin();
            }
            TwiddleTable { cos, sin }
        })
    }
}

fn compute_dft_magnitudes(window: &[f32]) -> Vec<f32> {
    let n = window.len();
    let num_bins = 100.min(n / 2);
    let mut magnitudes = vec![0.0f32; num_bins];
    let table = TwiddleTable::get();

    for (k, mag) in magnitudes.iter_mut().enumerate() {
        let mut real = 0.0f32;
        let mut imag = 0.0f32;
        let mut angle_idx = 0;

        for &sample in window.iter() {
            real += sample * table.cos[angle_idx];
            imag -= sample * table.sin[angle_idx];
            angle_idx = (angle_idx + k) & (WINDOW_SIZE - 1);
        }

        *mag = (real * real + imag * imag).sqrt();
    }

    magnitudes
}

pub fn generate_fingerprint_hashes(pcm: &[u8], _sample_rate: i32) -> Vec<String> {
    if pcm.len() < WINDOW_SIZE * 2 {
        return Vec::new();
    }

    let num_samples = pcm.len() / 2;
    let mut float_samples = Vec::with_capacity(num_samples);

    for i in 0..num_samples {
        let offset = i * 2;
        let s = i16::from_le_bytes([pcm[offset], pcm[offset + 1]]) as f32 / 32768.0;
        float_samples.push(s);
    }

    let mut hashes = Vec::new();
    let mut offset = 0;

    // Hanning window
    let mut window = vec![0.0f32; WINDOW_SIZE];
    for (i, w) in window.iter_mut().enumerate() {
        *w = 0.5 * (1.0 - (2.0 * PI * i as f32 / (WINDOW_SIZE as f32 - 1.0)).cos());
    }

    while offset + WINDOW_SIZE <= float_samples.len() {
        let mut frame = vec![0.0f32; WINDOW_SIZE];
        for i in 0..WINDOW_SIZE {
            frame[i] = float_samples[offset + i] * window[i];
        }

        let magnitudes = compute_dft_magnitudes(&frame);

        // Find peak bin in each band
        let mut peaks = [0u8; 4];
        for (band_idx, &(start_bin, end_bin)) in FREQ_BANDS.iter().enumerate() {
            let mut max_mag = 0.0f32;
            let mut max_bin = start_bin;

            for bin in start_bin..end_bin.min(magnitudes.len()) {
                if magnitudes[bin] > max_mag {
                    max_mag = magnitudes[bin];
                    max_bin = bin;
                }
            }

            peaks[band_idx] = (max_bin as u8) & 0xFF;
        }

        let hash_val = ((peaks[0] as u32) << 24)
            | ((peaks[1] as u32) << 16)
            | ((peaks[2] as u32) << 8)
            | (peaks[3] as u32);

        hashes.push(format!("{:08x}", hash_val));
        offset += HOP_SIZE;
    }

    hashes
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_fingerprint_generation() {
        // Generate 1 second of 44.1kHz audio with a 440 Hz tone
        let sample_rate = 44100;
        let mut pcm = Vec::with_capacity(sample_rate * 2);

        for i in 0..sample_rate {
            let t = i as f32 / sample_rate as f32;
            let val = (2.0 * PI * 440.0 * t).sin();
            let s16 = (val * 20000.0) as i16;
            pcm.extend_from_slice(&s16.to_le_bytes());
        }

        let hashes = generate_fingerprint_hashes(&pcm, sample_rate as i32);
        assert!(!hashes.is_empty());
        assert_eq!(hashes[0].len(), 8); // 8 hex characters
    }

    #[test]
    fn test_fingerprint_short_audio() {
        let short_pcm = vec![0u8; 100];
        let hashes = generate_fingerprint_hashes(&short_pcm, 44100);
        assert!(hashes.is_empty());
    }
}
