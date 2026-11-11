pub fn calculate_crossfade_gain(progress: f32) -> (f32, f32) {
    let p = progress.clamp(0.0, 1.0);
    // Equal power curve
    let gain_out = (1.0 - p).sqrt();
    let gain_in = p.sqrt();
    (gain_out, gain_in)
}
