use std::collections::HashMap;
use std::sync::Arc;
use parking_lot::RwLock;

#[derive(Default)]
pub struct MemoryCache {
    store: Arc<RwLock<HashMap<String, Vec<u8>>>>,
}

impl MemoryCache {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn insert(&self, key: &str, data: Vec<u8>) {
        self.store.write().insert(key.to_string(), data);
    }

    pub fn get(&self, key: &str) -> Option<Vec<u8>> {
        self.store.read().get(key).cloned()
    }
}
