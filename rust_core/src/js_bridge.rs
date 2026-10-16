use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::tab_manager::TabManager;

// ---------------------------------------------------------------------------
// Wire types
// ---------------------------------------------------------------------------

/// Messages that JavaScript running inside a WebView can send to Rust.
#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum JsMessage {
    /// Health-check ping.
    Ping,
    /// Request the current list of tabs.
    GetTabList,
    /// Ask Rust to close a specific tab.
    CloseTab { tab_id: u32 },
    /// Navigate a specific tab to a URL.
    Navigate { tab_id: u32, url: String },
    /// Extensible custom message for app-specific JS↔Rust communication.
    Custom { action: String, payload: Value },
}

/// Serializable summary of a tab sent to JavaScript.
#[derive(Debug, Serialize)]
pub struct TabInfo {
    pub id: u32,
    pub url: String,
    pub title: String,
    pub is_active: bool,
}

/// Responses Rust sends back to JavaScript.
#[derive(Debug, Serialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum JsResponse {
    /// Reply to `Ping`.
    Pong,
    /// Full tab list.
    TabList { tabs: Vec<TabInfo> },
    /// Generic success.
    Ok,
    /// Generic failure with a human-readable message.
    Error { message: String },
}

// ---------------------------------------------------------------------------
// Message processor
// ---------------------------------------------------------------------------

/// Parse a JSON `JsMessage`, execute it against `tab_manager`, and return a
/// JSON-encoded `JsResponse`.
///
/// This function never panics; any error (parse failure, bad tab id, etc.) is
/// returned as a JSON `{"type":"error","message":"..."}` object.
pub fn process_message(message_json: &str, tab_manager: &mut TabManager) -> String {
    let msg: JsMessage = match serde_json::from_str(message_json) {
        Ok(m) => m,
        Err(e) => {
            let response = JsResponse::Error {
                message: format!("Failed to parse message: {}", e),
            };
            return serialize_response(&response);
        }
    };

    let response = handle_message(msg, tab_manager);
    serialize_response(&response)
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

fn handle_message(msg: JsMessage, tab_manager: &mut TabManager) -> JsResponse {
    match msg {
        JsMessage::Ping => JsResponse::Pong,

        JsMessage::GetTabList => {
            let active_id = tab_manager.active_tab_id;
            let tabs = tab_manager
                .get_all_tabs()
                .iter()
                .map(|t| TabInfo {
                    id: t.id,
                    url: t.url.clone(),
                    title: t.title.clone(),
                    is_active: Some(t.id) == active_id,
                })
                .collect();
            JsResponse::TabList { tabs }
        }

        JsMessage::CloseTab { tab_id } => {
            if tab_manager.close_tab(tab_id) {
                JsResponse::Ok
            } else {
                JsResponse::Error {
                    message: format!("No tab with id {}", tab_id),
                }
            }
        }

        JsMessage::Navigate { tab_id, url } => {
            // Validate the tab exists before updating its URL.
            if tab_manager.get_all_tabs().iter().any(|t| t.id == tab_id) {
                tab_manager.update_tab_url(tab_id, &url);
                tab_manager.update_tab_loading(tab_id, true);
                JsResponse::Ok
            } else {
                JsResponse::Error {
                    message: format!("No tab with id {}", tab_id),
                }
            }
        }

        JsMessage::Custom { action, payload } => {
            // Log the custom action and acknowledge.  Application code can
            // extend this branch to dispatch to additional handlers.
            log::info!("JS custom action '{}': {:?}", action, payload);
            JsResponse::Ok
        }
    }
}

/// Serialize a `JsResponse` to JSON.  Serialization should never fail for our
/// simple types; fall back to a static error string just in case.
fn serialize_response(response: &JsResponse) -> String {
    serde_json::to_string(response)
        .unwrap_or_else(|_| r#"{"type":"error","message":"serialization failure"}"#.to_owned())
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tab_manager::TabManager;

    fn make_mgr_with_tab() -> (TabManager, u32) {
        let mut mgr = TabManager::new();
        let id = mgr.create_tab("https://example.com");
        (mgr, id)
    }

    #[test]
    fn ping_pong() {
        let mut mgr = TabManager::new();
        let resp = process_message(r#"{"type":"ping"}"#, &mut mgr);
        assert!(resp.contains("pong"));
    }

    #[test]
    fn get_tab_list() {
        let (mut mgr, _) = make_mgr_with_tab();
        let resp = process_message(r#"{"type":"get_tab_list"}"#, &mut mgr);
        assert!(resp.contains("tab_list"));
        assert!(resp.contains("example.com"));
    }

    #[test]
    fn close_existing_tab() {
        let (mut mgr, id) = make_mgr_with_tab();
        let msg = format!(r#"{{"type":"close_tab","tab_id":{}}}"#, id);
        let resp = process_message(&msg, &mut mgr);
        assert!(resp.contains("\"ok\""));
        assert_eq!(mgr.tab_count(), 0);
    }

    #[test]
    fn close_missing_tab_returns_error() {
        let mut mgr = TabManager::new();
        let resp = process_message(r#"{"type":"close_tab","tab_id":999}"#, &mut mgr);
        assert!(resp.contains("error"));
    }

    #[test]
    fn navigate_existing_tab() {
        let (mut mgr, id) = make_mgr_with_tab();
        let msg = format!(
            r#"{{"type":"navigate","tab_id":{},"url":"https://rust-lang.org"}}"#,
            id
        );
        let resp = process_message(&msg, &mut mgr);
        assert!(resp.contains("\"ok\""));
        assert_eq!(mgr.get_active_tab().unwrap().url, "https://rust-lang.org");
    }

    #[test]
    fn malformed_json_returns_error() {
        let mut mgr = TabManager::new();
        let resp = process_message("not json at all", &mut mgr);
        assert!(resp.contains("error"));
    }

    #[test]
    fn custom_message() {
        let mut mgr = TabManager::new();
        let resp = process_message(
            r#"{"type":"custom","action":"doSomething","payload":{"key":1}}"#,
            &mut mgr,
        );
        assert!(resp.contains("\"ok\""));
    }
}
