use std::collections::HashMap;

pub fn parse_query_string(query: &str) -> HashMap<String, String> {
    let mut map = HashMap::new();
    for pair in query.split('&') {
        let mut parts = pair.splitn(2, '=');
        if let (Some(k), Some(v)) = (parts.next(), parts.next()) {
            map.insert(k.to_string(), v.to_string());
        }
    }
    map
}

pub fn decipher_signature(sig: &str) -> String {
    // Basic signature transformation reverse algorithm
    sig.chars().rev().collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_query() {
        let q = "s=abcdef&sp=sig&url=http%3A%2F%2Fexample.com";
        let map = parse_query_string(q);
        assert_eq!(map.get("s").unwrap(), "abcdef");
        assert_eq!(map.get("sp").unwrap(), "sig");
    }

    #[test]
    fn test_decipher() {
        let sig = "abcdef";
        assert_eq!(decipher_signature(sig), "fedcba");
    }
}
