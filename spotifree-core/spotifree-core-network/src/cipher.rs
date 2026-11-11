pub fn decipher_signature(sig: &str) -> String {
    sig.chars().rev().collect()
}
