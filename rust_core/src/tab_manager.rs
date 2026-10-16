use serde::{Deserialize, Serialize};
use std::time::{SystemTime, UNIX_EPOCH};

/// A single browser tab.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Tab {
    pub id: u32,
    pub url: String,
    pub title: String,
    pub favicon_url: Option<String>,
    pub is_loading: bool,
    /// Unix timestamp (seconds) when the tab was created.
    pub created_at: u64,
}

impl Tab {
    fn new(id: u32, url: &str) -> Self {
        let created_at = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();

        Tab {
            id,
            url: url.to_owned(),
            title: url.to_owned(), // default title equals the URL until the page sets one
            favicon_url: None,
            is_loading: true, // newly opened tabs start loading
            created_at,
        }
    }
}

/// Manages the collection of open browser tabs.
pub struct TabManager {
    tabs: Vec<Tab>,
    pub active_tab_id: Option<u32>,
    next_id: u32,
}

impl TabManager {
    /// Create an empty `TabManager`.
    pub fn new() -> Self {
        TabManager {
            tabs: Vec::new(),
            active_tab_id: None,
            next_id: 1,
        }
    }

    /// Open a new tab with the given URL.  
    /// The new tab immediately becomes the active tab.  
    /// Returns the new tab's id.
    pub fn create_tab(&mut self, url: &str) -> u32 {
        let id = self.next_id;
        self.next_id += 1;

        let tab = Tab::new(id, url);
        self.tabs.push(tab);
        self.active_tab_id = Some(id);
        id
    }

    /// Close the tab with `id`.  
    /// If the closed tab was active the most-recently-opened remaining tab is
    /// activated (if any).  
    /// Returns `true` when a tab was found and removed.
    pub fn close_tab(&mut self, id: u32) -> bool {
        let Some(pos) = self.tabs.iter().position(|t| t.id == id) else {
            return false;
        };

        self.tabs.remove(pos);

        // Repair active_tab_id if we just closed the active tab.
        if self.active_tab_id == Some(id) {
            self.active_tab_id = self.tabs.last().map(|t| t.id);
        }

        true
    }

    /// Make the tab with `id` the active tab.  
    /// Returns `true` when the tab exists.
    pub fn switch_tab(&mut self, id: u32) -> bool {
        if self.tabs.iter().any(|t| t.id == id) {
            self.active_tab_id = Some(id);
            true
        } else {
            false
        }
    }

    /// Return a reference to the currently active tab, or `None`.
    pub fn get_active_tab(&self) -> Option<&Tab> {
        let id = self.active_tab_id?;
        self.tabs.iter().find(|t| t.id == id)
    }

    /// Return all open tabs as a slice.
    pub fn get_all_tabs(&self) -> &[Tab] {
        &self.tabs
    }

    /// Update the URL stored for a tab (e.g. after a redirect).
    pub fn update_tab_url(&mut self, id: u32, url: &str) {
        if let Some(tab) = self.tabs.iter_mut().find(|t| t.id == id) {
            tab.url = url.to_owned();
        }
    }

    /// Update the display title for a tab.
    pub fn update_tab_title(&mut self, id: u32, title: &str) {
        if let Some(tab) = self.tabs.iter_mut().find(|t| t.id == id) {
            tab.title = title.to_owned();
        }
    }

    /// Update the loading state for a tab.
    pub fn update_tab_loading(&mut self, id: u32, is_loading: bool) {
        if let Some(tab) = self.tabs.iter_mut().find(|t| t.id == id) {
            tab.is_loading = is_loading;
        }
    }

    /// Update the favicon URL for a tab.
    pub fn update_tab_favicon(&mut self, id: u32, favicon_url: Option<&str>) {
        if let Some(tab) = self.tabs.iter_mut().find(|t| t.id == id) {
            tab.favicon_url = favicon_url.map(str::to_owned);
        }
    }

    /// Number of currently open tabs.
    pub fn tab_count(&self) -> usize {
        self.tabs.len()
    }

    /// Clear all tabs and reset the manager.
    pub fn clear(&mut self) {
        self.tabs.clear();
        self.active_tab_id = None;
        self.next_id = 1;
    }
}

impl Default for TabManager {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn create_and_close_tab() {
        let mut mgr = TabManager::new();

        let id1 = mgr.create_tab("https://example.com");
        let id2 = mgr.create_tab("https://rust-lang.org");

        assert_eq!(mgr.tab_count(), 2);
        assert_eq!(mgr.active_tab_id, Some(id2));

        assert!(mgr.close_tab(id2));
        assert_eq!(mgr.tab_count(), 1);
        // active should fall back to id1 (last remaining)
        assert_eq!(mgr.active_tab_id, Some(id1));

        // Closing a non-existent tab returns false.
        assert!(!mgr.close_tab(999));
    }

    #[test]
    fn switch_tab() {
        let mut mgr = TabManager::new();
        let id1 = mgr.create_tab("https://a.com");
        let _id2 = mgr.create_tab("https://b.com");

        assert!(mgr.switch_tab(id1));
        assert_eq!(mgr.active_tab_id, Some(id1));

        assert!(!mgr.switch_tab(999));
    }

    #[test]
    fn update_fields() {
        let mut mgr = TabManager::new();
        let id = mgr.create_tab("https://a.com");

        mgr.update_tab_title(id, "A Site");
        mgr.update_tab_url(id, "https://a.com/page");
        mgr.update_tab_loading(id, false);

        let tab = mgr.get_active_tab().unwrap();
        assert_eq!(tab.title, "A Site");
        assert_eq!(tab.url, "https://a.com/page");
        assert!(!tab.is_loading);
    }
}
