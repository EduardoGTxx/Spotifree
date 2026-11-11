pub struct BiquadFilter {
    b0: f32,
    b1: f32,
    b2: f32,
    a1: f32,
    a2: f32,
    x1: f32,
    x2: f32,
    y1: f32,
    y2: f32,
}

impl BiquadFilter {
    pub fn new_peaking(sample_rate: f32, freq: f32, q: f32, gain_db: f32) -> Self {
        let w0 = 2.0 * std::f32::consts::PI * freq / sample_rate;
        let alpha = w0.sin() / (2.0 * q);
        let a = 10.0f32.powf(gain_db / 40.0);

        let b0 = 1.0 + alpha * a;
        let b1 = -2.0 * w0.cos();
        let b2 = 1.0 - alpha * a;
        let a0 = 1.0 + alpha / a;
        let a1 = -2.0 * w0.cos();
        let a2 = 1.0 - alpha / a;

        Self {
            b0: b0 / a0,
            b1: b1 / a0,
            b2: b2 / a0,
            a1: a1 / a0,
            a2: a2 / a0,
            x1: 0.0,
            x2: 0.0,
            y1: 0.0,
            y2: 0.0,
        }
    }

    pub fn process(&mut self, x: f32) -> f32 {
        let y = self.b0 * x + self.b1 * self.x1 + self.b2 * self.x2 - self.a1 * self.y1 - self.a2 * self.y2;
        self.x2 = self.x1;
        self.x1 = x;
        self.y2 = self.y1;
        self.y1 = y;
        y
    }
}

pub struct FiveBandEqualizer {
    sample_rate: f32,
    filters: [BiquadFilter; 5],
    pub gains: [f32; 5],
    pub enabled: bool,
}

const CENTER_FREQS: [f32; 5] = [60.0, 230.0, 910.0, 3600.0, 14000.0];

impl FiveBandEqualizer {
    pub fn new(sample_rate: f32) -> Self {
        let filters = [
            BiquadFilter::new_peaking(sample_rate, CENTER_FREQS[0], 1.0, 0.0),
            BiquadFilter::new_peaking(sample_rate, CENTER_FREQS[1], 1.0, 0.0),
            BiquadFilter::new_peaking(sample_rate, CENTER_FREQS[2], 1.0, 0.0),
            BiquadFilter::new_peaking(sample_rate, CENTER_FREQS[3], 1.0, 0.0),
            BiquadFilter::new_peaking(sample_rate, CENTER_FREQS[4], 1.0, 0.0),
        ];
        Self {
            sample_rate,
            filters,
            gains: [0.0; 5],
            enabled: false,
        }
    }

    pub fn set_band(&mut self, band: usize, gain_db: f32) {
        if band < 5 {
            self.gains[band] = gain_db;
            self.filters[band] = BiquadFilter::new_peaking(self.sample_rate, CENTER_FREQS[band], 1.0, gain_db);
            self.enabled = self.gains.iter().any(|&g| g.abs() > 0.01);
        }
    }

    pub fn process_sample(&mut self, mut x: f32) -> f32 {
        if !self.enabled {
            return x;
        }
        for f in &mut self.filters {
            x = f.process(x);
        }
        x
    }
}
