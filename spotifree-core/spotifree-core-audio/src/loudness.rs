// ITU-R BS.1770 / EBU R128 K-weighting filter and loudness metering

#[derive(Clone, Debug)]
struct Biquad {
    b0: f64,
    b1: f64,
    b2: f64,
    a1: f64,
    a2: f64,
    z1: f64,
    z2: f64,
}

impl Biquad {
    fn new(b0: f64, b1: f64, b2: f64, a1: f64, a2: f64) -> Self {
        Self {
            b0,
            b1,
            b2,
            a1,
            a2,
            z1: 0.0,
            z2: 0.0,
        }
    }

    fn process(&mut self, sample: f64) -> f64 {
        let out = self.b0 * sample + self.z1;
        self.z1 = self.b1 * sample - self.a1 * out + self.z2;
        self.z2 = self.b2 * sample - self.a2 * out;
        out
    }
}

// Coefficients for 44.1 kHz / 48 kHz standard K-weighting
fn create_k_weighting_filters(sample_rate: f64) -> (Biquad, Biquad) {
    // Stage 1: High shelf filter (boost highs ~ +4 dB)
    // Approximate coefficients for ~44.1kHz - 48kHz
    let db_gain = 3.99984385397;
    let f0 = 1681.974450955533;
    let q = 0.7071752369554196;
    let k = (std::f64::consts::PI * f0 / sample_rate).tan();
    let vh = 10.0f64.powf(db_gain / 20.0);
    let vb = vh.powf(0.4996667741545416);

    let a0 = 1.0 + k / q + k * k;
    let b0 = (vh + vb * (k / q) + k * k) / a0;
    let b1 = 2.0 * (k * k - vh) / a0;
    let b2 = (vh - vb * (k / q) + k * k) / a0;
    let a1 = 2.0 * (k * k - 1.0) / a0;
    let a2 = (1.0 - k / q + k * k) / a0;
    let stage1 = Biquad::new(b0, b1, b2, a1, a2);

    // Stage 2: High pass filter (RLB weighting ~ 100 Hz)
    let f0_hp = 38.13547087602444;
    let q_hp = 0.5003270373238773;
    let k_hp = (std::f64::consts::PI * f0_hp / sample_rate).tan();
    let a0_hp = 1.0 + k_hp / q_hp + k_hp * k_hp;
    let hp_b0 = 1.0 / a0_hp;
    let hp_b1 = -2.0 / a0_hp;
    let hp_b2 = 1.0 / a0_hp;
    let hp_a1 = 2.0 * (k_hp * k_hp - 1.0) / a0_hp;
    let hp_a2 = (1.0 - k_hp / q_hp + k_hp * k_hp) / a0_hp;
    let stage2 = Biquad::new(hp_b0, hp_b1, hp_b2, hp_a1, hp_a2);

    (stage1, stage2)
}

pub fn calculate_loudness_lufs(pcm: &[u8], sample_rate: i32) -> f32 {
    if pcm.len() < 4 {
        return -70.0;
    }

    let sr = (sample_rate.max(8000) as f64).min(192000.0);
    let (mut stage1, mut stage2) = create_k_weighting_filters(sr);

    let num_samples = pcm.len() / 2;
    let mut sum_sq = 0.0;

    for i in 0..num_samples {
        let offset = i * 2;
        let sample_i16 = i16::from_le_bytes([pcm[offset], pcm[offset + 1]]) as f64;
        let norm_sample = sample_i16 / 32768.0;

        let filtered = stage2.process(stage1.process(norm_sample));
        sum_sq += filtered * filtered;
    }

    let mean_sq = sum_sq / (num_samples as f64);
    if mean_sq <= 1e-10 {
        return -70.0;
    }

    let lufs = -0.691 + 10.0 * mean_sq.log10();
    lufs as f32
}

pub fn calculate_gain_adjustment(target_lufs: f32, current_lufs: f32) -> f32 {
    if current_lufs <= -69.0 {
        return 0.0;
    }
    // Gain in dB
    let gain_db = target_lufs - current_lufs;
    // Limit gain between -24 dB and +12 dB to prevent clipping/distortion
    gain_db.clamp(-24.0, 12.0)
}

pub fn calculate_true_peak(pcm: &[u8]) -> f32 {
    if pcm.len() < 2 {
        return -70.0;
    }

    let mut max_abs: f32 = 0.0;
    let num_samples = pcm.len() / 2;

    for i in 0..num_samples {
        let offset = i * 2;
        let sample = i16::from_le_bytes([pcm[offset], pcm[offset + 1]]) as f32 / 32768.0;
        let abs_val = sample.abs();
        if abs_val > max_abs {
            max_abs = abs_val;
        }
    }

    if max_abs <= 1e-5 {
        return -70.0;
    }

    20.0 * max_abs.log10()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_silence_lufs() {
        let silence = vec![0u8; 1000];
        let lufs = calculate_loudness_lufs(&silence, 44100);
        assert_eq!(lufs, -70.0);
    }

    #[test]
    fn test_sine_wave_lufs() {
        // Generate a 1kHz sine wave for 0.5s at full amplitude
        let sample_rate = 44100;
        let num_samples = sample_rate / 2;
        let mut pcm = Vec::with_capacity(num_samples * 2);

        for i in 0..num_samples {
            let t = i as f32 / sample_rate as f32;
            let val = (2.0 * std::f32::consts::PI * 1000.0 * t).sin();
            let sample_i16 = (val * 32767.0) as i16;
            pcm.extend_from_slice(&sample_i16.to_le_bytes());
        }

        let lufs = calculate_loudness_lufs(&pcm, sample_rate as i32);
        // Full scale 1kHz sine wave is approximately -3 LUFS
        assert!(lufs > -6.0 && lufs < 0.0, "LUFS was {}", lufs);
    }

    #[test]
    fn test_gain_adjustment() {
        // Target -14 LUFS, current -8 LUFS -> needs -6 dB
        let gain = calculate_gain_adjustment(-14.0, -8.0);
        assert!((gain - (-6.0)).abs() < 0.01);
    }

    #[test]
    fn test_true_peak() {
        let sample_i16 = 32767i16;
        let pcm = sample_i16.to_le_bytes();
        let peak = calculate_true_peak(&pcm);
        assert!((peak - 0.0).abs() < 0.1);
    }
}
