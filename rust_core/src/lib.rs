//! Rust core library for the Android WebView browser.
//!
//! All public JNI functions are prefixed with the fully-qualified Java class
//! name `com.example.webviewbrowser.RustBridge`.

pub mod download_manager;
pub mod js_bridge;
pub mod tab_manager;

use std::sync::Mutex;

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use once_cell::sync::Lazy;

use download_manager::DownloadManager;
use tab_manager::TabManager;

// ---------------------------------------------------------------------------
// Global application state
// ---------------------------------------------------------------------------

struct AppState {
    tab_manager: TabManager,
    download_manager: DownloadManager,
}

impl AppState {
    fn new() -> Self {
        AppState {
            tab_manager: TabManager::new(),
            download_manager: DownloadManager::new(),
        }
    }
}

/// The single, lazily-initialised global state protected by a `Mutex`.
static APP_STATE: Lazy<Mutex<AppState>> = Lazy::new(|| Mutex::new(AppState::new()));

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/// Convert a Rust `&str` into a `jstring` owned by the JVM.
/// Returns a null `jstring` on failure.
fn str_to_jstring(env: &mut JNIEnv, s: &str) -> jstring {
    match env.new_string(s) {
        Ok(js) => js.into_raw(),
        Err(e) => {
            log::error!("str_to_jstring failed: {:?}", e);
            std::ptr::null_mut()
        }
    }
}

/// Read a `JString` argument from Java into a Rust `String`.
/// Returns an empty `String` and logs an error on failure.
fn jstring_to_string(env: &mut JNIEnv, js: JString) -> String {
    match unsafe { env.get_string_unchecked(&js) } {
        Ok(s) => s.into(),
        Err(e) => {
            log::error!("jstring_to_string failed: {:?}", e);
            String::new()
        }
    }
}

// ---------------------------------------------------------------------------
// JNI exports
// ---------------------------------------------------------------------------

/// Initialise Android logging and ensure the global `AppState` is created.
///
/// Must be called once from `RustBridge.init()` before any other native
/// method.
#[no_mangle]
pub extern "system" fn Java_com_example_webviewbrowser_RustBridge_nativeInit(
    _env: JNIEnv,
    _class: JClass,
) {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Debug)
            .with_tag("RustCore"),
    );

    // Force lazy initialisation of the global state.
    drop(APP_STATE.lock().unwrap_or_else(|p| p.into_inner()));

    log::info!("RustCore initialised");
}

/// Create a new browser tab navigating to `url`.
///
/// Returns the numeric tab id, or `-1` on failure.
#[no_mangle]
pub extern "system" fn Java_com_example_webviewbrowser_RustBridge_nativeCreateTab(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
) -> jint {
    let url = jstring_to_string(&mut env, url);

    match APP_STATE.lock() {
        Ok(mut state) => state.tab_manager.create_tab(&url) as jint,
        Err(e) => {
            log::error!("nativeCreateTab: lock poisoned: {:?}", e);
            -1
        }
    }
}

/// Close the tab identified by `tab_id`.
///
/// Returns `JNI_TRUE` when a tab was found and removed, `JNI_FALSE` otherwise.
#[no_mangle]
pub extern "system" fn Java_com_example_webviewbrowser_RustBridge_nativeCloseTab(
    _env: JNIEnv,
    _class: JClass,
    tab_id: jint,
) -> jboolean {
    match APP_STATE.lock() {
        Ok(mut state) => {
            if state.tab_manager.close_tab(tab_id as u32) {
                JNI_TRUE
            } else {
                JNI_FALSE
            }
        }
        Err(e) => {
            log::error!("nativeCloseTab: lock poisoned: {:?}", e);
            JNI_FALSE
        }
    }
}

/// Switch the active tab to the one identified by `tab_id`.
///
/// Returns `JNI_TRUE` when the tab exists, `JNI_FALSE` otherwise.
#[no_mangle]
pub extern "system" fn Java_com_example_webviewbrowser_RustBridge_nativeSwitchTab(
    _env: JNIEnv,
    _class: JClass,
    tab_id: jint,
) -> jboolean {
    match APP_STATE.lock() {
        Ok(mut state) => {
            if state.tab_manager.switch_tab(tab_id as u32) {
                JNI_TRUE
            } else {
                JNI_FALSE
            }
        }
        Err(e) => {
            log::error!("nativeSwitchTab: lock poisoned: {:?}", e);
            JNI_FALSE
        }
    }
}

/// Return the id of the currently active tab, or `-1` if there is none.
#[no_mangle]
pub extern "system" fn Java_com_example_webviewbrowser_RustBridge_nativeGetActiveTabId(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    match APP_STATE.lock() {
        Ok(state) => state
            .tab_manager
            .active_tab_id
            .map(|id| id as jint)
            .unwrap_or(-1),
        Err(e) => {
            log::error!("nativeGetActiveTabId: lock poisoned: {:?}", e);
            -1
        }
    }
}

/// Return a JSON array containing an object for every open tab.
///
/// Each object has `id`, `url`, `title`, `favicon_url`, `is_loading`, and
/// `created_at` fields.  Returns an empty array JSON `"[]"` on failure.
#[no_mangle]
pub extern "system" fn Java_com_example_webviewbrowser_RustBridge_nativeGetTabListJson(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let json = match APP_STATE.lock() {
        Ok(state) => {
            serde_json::to_string(state.tab_manager.get_all_tabs()).unwrap_or_else(|e| {
                log::error!("nativeGetTabListJson: serialization failed: {:?}", e);
                "[]".to_owned()
            })
        }
        Err(e) => {
            log::error!("nativeGetTabListJson: lock poisoned: {:?}", e);
            "[]".to_owned()
        }
    };

    str_to_jstring(&mut env, &json)
}

/// Update the URL recorded for a tab (e.g. after a redirect or navigation).
#[no_mangle]
pub extern "system" fn Java_com_example_webviewbrowser_RustBridge_nativeUpdateTabUrl(
    mut env: JNIEnv,
    _class: JClass,
    tab_id: jint,
    url: JString,
) {
    let url = jstring_to_string(&mut env, url);

    match APP_STATE.lock() {
        Ok(mut state) => state.tab_manager.update_tab_url(tab_id as u32, &url),
        Err(e) => log::error!("nativeUpdateTabUrl: lock poisoned: {:?}", e),
    }
}

/// Update the display title for a tab.
#[no_mangle]
pub extern "system" fn Java_com_example_webviewbrowser_RustBridge_nativeUpdateTabTitle(
    mut env: JNIEnv,
    _class: JClass,
    tab_id: jint,
    title: JString,
) {
    let title = jstring_to_string(&mut env, title);

    match APP_STATE.lock() {
        Ok(mut state) => state.tab_manager.update_tab_title(tab_id as u32, &title),
        Err(e) => log::error!("nativeUpdateTabTitle: lock poisoned: {:?}", e),
    }
}

/// Update the loading state for a tab.
#[no_mangle]
pub extern "system" fn Java_com_example_webviewbrowser_RustBridge_nativeUpdateTabLoading(
    _env: JNIEnv,
    _class: JClass,
    tab_id: jint,
    is_loading: jboolean,
) {
    match APP_STATE.lock() {
        Ok(mut state) => state
            .tab_manager
            .update_tab_loading(tab_id as u32, is_loading == JNI_TRUE),
        Err(e) => log::error!("nativeUpdateTabLoading: lock poisoned: {:?}", e),
    }
}

/// Process a JSON message from JavaScript running inside a WebView.
///
/// `tab_id` identifies the source tab.  `message` must be a JSON object with
/// at least a `"type"` field matching one of the variants in `JsMessage`.
///
/// Returns a JSON-encoded `JsResponse`.
#[no_mangle]
pub extern "system" fn Java_com_example_webviewbrowser_RustBridge_nativeHandleJsMessage(
    mut env: JNIEnv,
    _class: JClass,
    tab_id: jint,
    message: JString,
) -> jstring {
    let message = jstring_to_string(&mut env, message);
    log::debug!("nativeHandleJsMessage tab_id={} msg={}", tab_id, message);

    let response = match APP_STATE.lock() {
        Ok(mut state) => js_bridge::process_message(&message, &mut state.tab_manager),
        Err(e) => {
            log::error!("nativeHandleJsMessage: lock poisoned: {:?}", e);
            r#"{"type":"error","message":"internal lock error"}"#.to_owned()
        }
    };

    str_to_jstring(&mut env, &response)
}

/// Enqueue a file download and return its task id, or `-1` on failure.
#[no_mangle]
pub extern "system" fn Java_com_example_webviewbrowser_RustBridge_nativeEnqueueDownload(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
    mime_type: JString,
) -> jint {
    let url = jstring_to_string(&mut env, url);
    let mime_type = jstring_to_string(&mut env, mime_type);

    match APP_STATE.lock() {
        Ok(mut state) => state.download_manager.enqueue(&url, &mime_type) as jint,
        Err(e) => {
            log::error!("nativeEnqueueDownload: lock poisoned: {:?}", e);
            -1
        }
    }
}

/// Return the number of currently open tabs.
#[no_mangle]
pub extern "system" fn Java_com_example_webviewbrowser_RustBridge_nativeGetTabCount(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    match APP_STATE.lock() {
        Ok(state) => state.tab_manager.tab_count() as jint,
        Err(e) => {
            log::error!("nativeGetTabCount: lock poisoned: {:?}", e);
            0
        }
    }
}

/// Clear all open tabs from the Rust TabManager.
#[no_mangle]
pub extern "system" fn Java_com_example_webviewbrowser_RustBridge_nativeClearTabs(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Ok(mut state) = APP_STATE.lock() {
        state.tab_manager.clear();
        log::info!("RustCore: all tabs cleared");
    }
}
