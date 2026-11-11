#[derive(Debug, Clone, Default)]
pub struct Equalizer {
    pub bands: [f32; 5],
}

impl Equalizer {
    pub fn set_band(&mut self, band: usize, gain_db: f32) {
        if band < self.bands.len() {
            self.bands[band] = gain_db;
        }
    }
}
