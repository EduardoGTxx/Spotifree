pub struct CombFilter {
    buffer: Vec<f32>,
    index: usize,
    feedback: f32,
}

impl CombFilter {
    pub fn new(delay_samples: usize, feedback: f32) -> Self {
        Self {
            buffer: vec![0.0; delay_samples.max(1)],
            index: 0,
            feedback,
        }
    }

    pub fn process(&mut self, input: f32) -> f32 {
        let output = self.buffer[self.index];
        self.buffer[self.index] = input + output * self.feedback;
        self.index = (self.index + 1) % self.buffer.len();
        output
    }
}

pub struct SchroederReverb {
    combs: [CombFilter; 4],
    room_size: f32,
    pub enabled: bool,
}

impl SchroederReverb {
    pub fn new(sample_rate: f32) -> Self {
        let scale = sample_rate / 44100.0;
        let delays = [
            (1116.0 * scale) as usize,
            (1188.0 * scale) as usize,
            (1277.0 * scale) as usize,
            (1356.0 * scale) as usize,
        ];
        Self {
            combs: [
                CombFilter::new(delays[0], 0.7),
                CombFilter::new(delays[1], 0.72),
                CombFilter::new(delays[2], 0.75),
                CombFilter::new(delays[3], 0.78),
            ],
            room_size: 0.5,
            enabled: false,
        }
    }

    pub fn set_room_size(&mut self, size: f32) {
        self.room_size = size.clamp(0.0, 0.98);
        for (i, c) in self.combs.iter_mut().enumerate() {
            c.feedback = (0.65 + (i as f32 * 0.05)) * self.room_size;
        }
    }

    pub fn process_sample(&mut self, input: f32) -> f32 {
        if !self.enabled {
            return input;
        }
        let wet: f32 = self.combs.iter_mut().map(|c| c.process(input)).sum::<f32>() * 0.25;
        input * 0.7 + wet * 0.3
    }
}
