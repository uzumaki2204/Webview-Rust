package com.example.webviewbrowser;

import android.content.Intent;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.content.pm.PackageManager;
import android.Manifest;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Main Activity — hosts all browser tabs and the surrounding Chrome UI.
 *
 * <p>Architecture overview:</p>
 * <pre>
 *  ┌─────────────────────────────────────────┐
 *  │  Toolbar (back | forward | urlBar | ··· │ tabs-btn)
 *  ├─────────────────────────────────────────┤
 *  │  ProgressBar (INVISIBLE when idle)      │
 *  ├─────────────────────────────────────────┤
 *  │                                         │
 *  │         contentFrame (FrameLayout)      │
 *  │    active WebView fills this area       │
 *  │                                         │
 *  └─────────────────────────────────────────┘
 * </pre>
 *
 * <p>All open {@link WebTabView} instances are kept in {@link #tabViews}; only the
 * active one is attached to {@link #contentFrame} at any given time.</p>
 */
public class MainActivity extends AppCompatActivity
        implements WebTabView.TabEventListener {

    // -------------------------------------------------------------------------
    // Fields — UI
    // -------------------------------------------------------------------------

    private Toolbar toolbar;
    private EditText urlBar;
    private ImageButton btnBack;
    private ImageButton btnForward;
    private ImageButton btnMenu;
    private ImageButton btnTabs;
    private TextView tabCountBadge;
    private ProgressBar progressBar;
    private FrameLayout contentFrame;

    // -------------------------------------------------------------------------
    // Fields — Tab management
    // -------------------------------------------------------------------------

    /** All open tabs, keyed by the Rust-assigned tab ID. */
    private final Map<Integer, WebTabView> tabViews = new HashMap<>();

    /** Currently displayed tab ID, or -1 if no tabs are open. */
    private int activeTabId = -1;

    /** Counter of pending snapshot captures (used to know when all are ready). */
    private final AtomicInteger pendingSnapshots = new AtomicInteger(0);

    /** Guard flag — suppresses {@link #saveSession()} while restoring tabs from disk. */
    private boolean isRestoringSession = false;

    // -------------------------------------------------------------------------
    // Fields — Fullscreen
    // -------------------------------------------------------------------------

    private LinearLayout mainContentLayout;
    private FrameLayout customViewContainer;
    private View customView;
    private android.webkit.WebChromeClient.CustomViewCallback customViewCallback;
    private boolean isBrowserFullscreen = false;
    private ImageButton btnExitFullscreen;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Window appearance: ensure status bar is dark and fits system bars
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(0xFF303134);
        }
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.setAppearanceLightStatusBars(false);
        }

        // Initialise the Rust runtime first.
        RustBridge.nativeInit();

        // Enable cookies globally.
        CookieManager.getInstance().setAcceptCookie(true);

        // Build UI.
        buildContentView();
        setupListeners();

        // Extract launch URL if app was opened via external link or pinned shortcut
        String launchUrl = null;
        if (getIntent() != null && getIntent().getData() != null) {
            launchUrl = getIntent().getData().toString().trim();
        }

        // Restore previous session or open launch/default tab
        restoreOrInitSession(launchUrl);

        // Request Camera and Audio permissions if not yet granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                    WebTabView.CAMERA_PERMISSION_REQUEST_CODE);
        }
    }

    /**
     * Called when the Activity is already running and receives a new Intent
     * (e.g. from a deep-link, notification tap, or home screen pinned shortcut).
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
        saveSession();
    }

    @Override
    protected void onStop() {
        super.onStop();
        saveSession();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        saveSession();
        for (WebTabView tab : tabViews.values()) {
            tab.destroy();
        }
        tabViews.clear();
        RustBridge.nativeClearTabs();
    }

    // -------------------------------------------------------------------------
    // UI construction (programmatic — no XML layout required)
    // -------------------------------------------------------------------------

    private void buildContentView() {
        // Root: vertical LinearLayout.
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(0xFF303134); // Match toolbar color behind status bar / cutout

        // Apply system window insets so toolbar is never cut off by notch or status bar
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sysInsets = insets.getInsets(
                    WindowInsetsCompat.Type.statusBars()
                    | WindowInsetsCompat.Type.displayCutout()
            );
            v.setPadding(sysInsets.left, sysInsets.top, sysInsets.right, 0);
            return insets;
        });

        // --- Toolbar ---
        toolbar = new Toolbar(this);
        toolbar.setBackgroundColor(0xFF303134);
        toolbar.setContentInsetStartWithNavigation(0);
        toolbar.setContentInsetsAbsolute(dpToPx(4), dpToPx(4));
        LinearLayout.LayoutParams toolbarParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(56));
        root.addView(toolbar, toolbarParams);

        // Build the toolbar's inner layout (all in a single horizontal row).
        LinearLayout toolbarRow = new LinearLayout(this);
        toolbarRow.setOrientation(LinearLayout.HORIZONTAL);
        toolbarRow.setGravity(Gravity.CENTER_VERTICAL);
        toolbarRow.setLayoutParams(new Toolbar.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Back button
        btnBack = makeIconButton(android.R.drawable.ic_media_previous, "Go back");
        toolbarRow.addView(btnBack, iconButtonParams());

        // Forward button
        btnForward = makeIconButton(android.R.drawable.ic_media_next, "Go forward");
        toolbarRow.addView(btnForward, iconButtonParams());

        // URL bar (weight=1 to fill remaining space)
        urlBar = new EditText(this);
        urlBar.setHint("Search or enter URL");
        urlBar.setHintTextColor(0xFFAAAAAA);
        urlBar.setTextColor(Color.WHITE);
        urlBar.setBackgroundColor(0xFF505054);
        urlBar.setSingleLine(true);
        urlBar.setInputType(InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_CLASS_TEXT);
        urlBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlBar.setSelectAllOnFocus(true);
        urlBar.setPadding(dpToPx(8), 0, dpToPx(8), 0);
        int urlBarRadius = dpToPx(4);
        urlBar.setBackground(createRoundedBackground(0xFF505054, urlBarRadius));
        LinearLayout.LayoutParams urlBarParams = new LinearLayout.LayoutParams(
                0, dpToPx(36), 1f);
        urlBarParams.setMargins(dpToPx(6), 0, dpToPx(6), 0);
        toolbarRow.addView(urlBar, urlBarParams);

        // Tabs button + badge overlay
        FrameLayout tabsBtnFrame = new FrameLayout(this);
        tabsBtnFrame.setLayoutParams(iconButtonParams());

        btnTabs = makeIconButton(android.R.drawable.ic_menu_sort_by_size, "Tabs");
        FrameLayout.LayoutParams tabsBtnInnerParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        tabsBtnFrame.addView(btnTabs, tabsBtnInnerParams);

        tabCountBadge = new TextView(this);
        tabCountBadge.setTextColor(Color.WHITE);
        tabCountBadge.setTextSize(9f);
        tabCountBadge.setTypeface(null, Typeface.BOLD);
        tabCountBadge.setGravity(Gravity.CENTER);
        tabCountBadge.setText("1");
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                dpToPx(14), dpToPx(14));
        badgeParams.gravity = Gravity.TOP | Gravity.END;
        badgeParams.setMargins(0, dpToPx(4), dpToPx(4), 0);
        tabCountBadge.setBackground(createCircleBackground(0xFF4285F4));
        tabsBtnFrame.addView(tabCountBadge, badgeParams);

        toolbarRow.addView(tabsBtnFrame, iconButtonParams());

        // Menu button (⋮)
        btnMenu = makeIconButton(android.R.drawable.ic_menu_more, "Menu");
        toolbarRow.addView(btnMenu, iconButtonParams());

        toolbar.addView(toolbarRow);

        // --- Progress bar ---
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.INVISIBLE);
        progressBar.setIndeterminate(false);
        LinearLayout.LayoutParams pbParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(3));
        root.addView(progressBar, pbParams);

        // --- Content frame (WebViews go here) ---
        contentFrame = new FrameLayout(this);
        contentFrame.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        contentFrame.setBackgroundColor(Color.WHITE);
        root.addView(contentFrame);

        mainContentLayout = root;

        // --- Custom view container (for HTML5 video / canvas fullscreen) ---
        customViewContainer = new FrameLayout(this);
        customViewContainer.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        customViewContainer.setBackgroundColor(Color.BLACK);
        customViewContainer.setVisibility(View.GONE);

        // --- Exit fullscreen floating button (discreet pill for browser fullscreen) ---
        btnExitFullscreen = makeIconButton(android.R.drawable.ic_menu_close_clear_cancel, "Thoát toàn màn hình");
        FrameLayout.LayoutParams exitBtnParams = new FrameLayout.LayoutParams(dpToPx(44), dpToPx(44));
        exitBtnParams.gravity = Gravity.BOTTOM | Gravity.END;
        exitBtnParams.setMargins(0, 0, dpToPx(16), dpToPx(24));
        btnExitFullscreen.setBackground(createCircleBackground(0xCC303134));
        btnExitFullscreen.setVisibility(View.GONE);

        // Master root hosting main content, fullscreen video overlay, and floating controls
        FrameLayout masterRoot = new FrameLayout(this);
        masterRoot.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        masterRoot.addView(mainContentLayout);
        masterRoot.addView(customViewContainer);
        masterRoot.addView(btnExitFullscreen, exitBtnParams);

        setContentView(masterRoot);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
    }

    // -------------------------------------------------------------------------
    // Button / listener setup
    // -------------------------------------------------------------------------

    private void setupListeners() {
        // URL bar — user presses Enter / "Go" on keyboard.
        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                navigateToUrl(v.getText().toString().trim());
                return true;
            }
            return false;
        });

        // Back
        btnBack.setOnClickListener(v -> {
            WebTabView tab = tabViews.get(activeTabId);
            if (tab != null && tab.canGoBack()) {
                tab.goBack();
            }
        });

        // Forward
        btnForward.setOnClickListener(v -> {
            WebTabView tab = tabViews.get(activeTabId);
            if (tab != null && tab.canGoForward()) {
                tab.goForward();
            }
        });

        // Tabs switcher
        btnTabs.setOnClickListener(v -> launchTabSwitcher());

        // Exit fullscreen floating button
        btnExitFullscreen.setOnClickListener(v -> exitBrowserFullscreen());

        // Menu (⋮) → PopupMenu
        btnMenu.setOnClickListener(v -> {
            // Use a dark theme wrapper so menu text is visible on the dark toolbar
            Context wrapper = new android.view.ContextThemeWrapper(
                    this, com.google.android.material.R.style.ThemeOverlay_MaterialComponents_Dark);
            PopupMenu popup = new PopupMenu(wrapper, btnMenu);
            popup.getMenu().add(0, 1, 0, "Tab mới");
            popup.getMenu().add(0, 2, 1, "Tải lại");
            popup.getMenu().add(0, 4, 2, isBrowserFullscreen ? "Thoát toàn màn hình" : "Toàn màn hình");
            popup.getMenu().add(0, 3, 3, "Tìm trong trang");
            popup.setOnMenuItemClickListener(item -> {
                switch (item.getItemId()) {
                    case 1:
                        openNewTab("https://www.google.com");
                        return true;
                    case 2:
                        WebTabView tab = tabViews.get(activeTabId);
                        if (tab != null) tab.reload();
                        return true;
                    case 4:
                        if (isBrowserFullscreen) {
                            exitBrowserFullscreen();
                        } else {
                            enterBrowserFullscreen();
                        }
                        return true;
                    case 3:
                        Toast.makeText(this, "Tính năng tìm kiếm đang phát triển", Toast.LENGTH_SHORT).show();
                        return true;
                    default:
                        return false;
                }
            });
            popup.show();
        });
    }

    // -------------------------------------------------------------------------
    // Tab management
    // -------------------------------------------------------------------------

    /**
     * Create a new tab, load the given URL, and switch to it immediately.
     *
     * @param url The URL to open.
     */
    public void openNewTab(String url) {
        int tabId = RustBridge.nativeCreateTab(url);
        WebTabView tabView = new WebTabView(this, tabId, url, this);

        // Attach download handler for this tab.
        tabView.getWebView().setDownloadListener(
                DownloadHandler.createFor(this, tabId));

        // Enable third-party cookies for this WebView.
        CookieManager.getInstance().setAcceptThirdPartyCookies(tabView.getWebView(), true);

        tabViews.put(tabId, tabView);
        showTab(tabId);
        updateTabCountBadge();
        saveSession();
    }

    /**
     * Make the given tab the active (visible) tab.
     *
     * @param tabId The tab to show.
     */
    public void showTab(int tabId) {
        WebTabView newTab = tabViews.get(tabId);
        if (newTab == null) return;

        // Detach the currently visible WebView (keep it alive in tabViews).
        if (activeTabId != -1 && activeTabId != tabId) {
            WebTabView currentTab = tabViews.get(activeTabId);
            if (currentTab != null) {
                contentFrame.removeView(currentTab.getWebView());
            }
        }

        // Attach new WebView if not already a child.
        if (newTab.getWebView().getParent() == null) {
            contentFrame.addView(newTab.getWebView(),
                    new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
        }

        activeTabId = tabId;
        RustBridge.nativeSwitchTab(tabId);

        // Sync URL bar.
        String currentUrl = newTab.getWebView().getUrl();
        urlBar.setText(currentUrl != null ? currentUrl : "");

        updateNavButtons(newTab);
        updateTabCountBadge();
        saveSession();
    }

    /**
     * Close a tab permanently, freeing its WebView resources.
     * If the closed tab was active, another tab is chosen (or a new one opened).
     *
     * @param tabId The tab to close.
     */
    public void closeTab(int tabId) {
        WebTabView tab = tabViews.remove(tabId);
        if (tab != null) {
            contentFrame.removeView(tab.getWebView());
            tab.destroy();
        }
        RustBridge.nativeCloseTab(tabId);

        if (activeTabId == tabId) {
            activeTabId = -1;
            if (tabViews.isEmpty()) {
                openNewTab("https://www.google.com");
            } else {
                // Show the last tab in the map.
                int nextId = tabViews.keySet().iterator().next();
                showTab(nextId);
            }
        }
        updateTabCountBadge();
        saveSession();
    }

    // -------------------------------------------------------------------------
    // Tab switcher
    // -------------------------------------------------------------------------

    private void launchTabSwitcher() {
        // Kick off thumbnail capture for every tab asynchronously.
        // We track how many are still pending; once all finish we start the Activity.
        List<Integer> tabIds = new ArrayList<>(tabViews.keySet());
        TabSwitcherActivity.thumbnails.clear();

        if (tabIds.isEmpty()) {
            startTabSwitcherActivity();
            return;
        }

        pendingSnapshots.set(tabIds.size());

        for (int tabId : tabIds) {
            WebTabView tab = tabViews.get(tabId);
            if (tab == null) {
                TabSwitcherActivity.thumbnails.put(tabId, null);
                if (pendingSnapshots.decrementAndGet() == 0) {
                    startTabSwitcherActivity();
                }
                continue;
            }
            final int id = tabId;
            tab.captureSnapshot(bitmap -> {
                TabSwitcherActivity.thumbnails.put(id, bitmap);
                if (pendingSnapshots.decrementAndGet() == 0) {
                    startTabSwitcherActivity();
                }
            });
        }
    }

    private void startTabSwitcherActivity() {
        Intent intent = new Intent(this, TabSwitcherActivity.class);
        intent.putExtra(TabSwitcherActivity.EXTRA_TAB_LIST_JSON,
                RustBridge.nativeGetTabListJson());
        intent.putExtra(TabSwitcherActivity.EXTRA_ACTIVE_TAB_ID, activeTabId);
        //noinspection deprecation
        startActivityForResult(intent, TabSwitcherActivity.REQUEST_CODE);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == WebTabView.FILE_CHOOSER_REQUEST_CODE) {
            WebTabView currentTab = tabViews.get(activeTabId);
            if (currentTab != null) {
                currentTab.onFileChooserResult(resultCode, data);
            }
            return;
        }

        if (requestCode == TabSwitcherActivity.REQUEST_CODE && data != null) {
            // 1. Remove all tabs closed in TabSwitcher from MainActivity.tabViews
            ArrayList<Integer> closedIds = data.getIntegerArrayListExtra(TabSwitcherActivity.EXTRA_CLOSED_TAB_IDS);
            if (closedIds != null && !closedIds.isEmpty()) {
                for (int closedId : closedIds) {
                    WebTabView tab = tabViews.remove(closedId);
                    if (tab != null) {
                        contentFrame.removeView(tab.getWebView());
                        tab.destroy();
                    }
                    RustBridge.nativeCloseTab(closedId);
                }
            }

            // 2. Handle create new tab
            if (data.getBooleanExtra(TabSwitcherActivity.EXTRA_CREATE_NEW_TAB, false)) {
                openNewTab("https://www.google.com");
                return;
            }

            // 3. Handle select tab
            int selectedTabId = data.getIntExtra(TabSwitcherActivity.EXTRA_SELECTED_TAB_ID, -1);
            if (selectedTabId != -1 && tabViews.containsKey(selectedTabId)) {
                showTab(selectedTabId);
            } else if (!tabViews.isEmpty()) {
                // If selected tab was closed or invalid, show the first available tab
                int firstId = tabViews.keySet().iterator().next();
                showTab(firstId);
            } else {
                // All tabs were closed -> open one fresh tab
                openNewTab("https://www.google.com");
            }
            updateTabCountBadge();
            saveSession();
            return;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        WebTabView currentTab = tabViews.get(activeTabId);
        if (currentTab != null) {
            currentTab.onPermissionResult(requestCode, permissions, grantResults);
        }
    }

    // -------------------------------------------------------------------------
    // Navigation helpers
    // -------------------------------------------------------------------------

    /**
     * Navigate the active WebView to the given raw URL string, prepending
     * {@code https://} if no protocol scheme is present.
     *
     * @param raw The text typed into the URL bar.
     */
    private void navigateToUrl(String raw) {
        if (raw == null || raw.isEmpty()) return;

        String url;
        if (raw.startsWith("http://") || raw.startsWith("https://")
                || raw.startsWith("file://") || raw.startsWith("about:")) {
            url = raw;
        } else if (raw.contains(".") && !raw.contains(" ")) {
            // Looks like a hostname — prepend https://.
            url = "https://" + raw;
        } else {
            // Treat as a search query.
            url = "https://www.google.com/search?q=" + android.net.Uri.encode(raw);
        }

        WebTabView tab = tabViews.get(activeTabId);
        if (tab != null) {
            tab.loadUrl(url);
        }

        // Hide keyboard.
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && urlBar.getWindowToken() != null) {
            imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
        }
    }

    // -------------------------------------------------------------------------
    // Hardware back button
    // -------------------------------------------------------------------------

    @Override
    public void onBackPressed() {
        if (customView != null) {
            onHideCustomView();
            return;
        }
        if (isBrowserFullscreen) {
            exitBrowserFullscreen();
            return;
        }
        WebTabView tab = tabViews.get(activeTabId);
        if (tab != null && tab.canGoBack()) {
            tab.goBack();
        } else {
            // Don't exit; move the task to the back of the stack.
            moveTaskToBack(true);
        }
    }

    // -------------------------------------------------------------------------
    // WebTabView.TabEventListener implementation
    // -------------------------------------------------------------------------

    @Override
    public void onPageStarted(int tabId, String url) {
        if (tabId != activeTabId) return;
        runOnUiThread(() -> {
            urlBar.setText(url);
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setProgress(10); // show minimal progress immediately
            WebTabView tab = tabViews.get(tabId);
            if (tab != null) updateNavButtons(tab);
        });
    }

    @Override
    public void onPageFinished(int tabId, String url) {
        if (tabId != activeTabId) return;
        runOnUiThread(() -> {
            urlBar.setText(url);
            progressBar.setVisibility(View.INVISIBLE);
            progressBar.setProgress(0);
            WebTabView tab = tabViews.get(tabId);
            if (tab != null) updateNavButtons(tab);
            saveSession();
        });
    }

    @Override
    public void onTitleChanged(int tabId, String title) {
        // No title shown in toolbar — could update window title or tab strip if desired.
    }

    @Override
    public void onProgressChanged(int tabId, int progress) {
        if (tabId != activeTabId) return;
        runOnUiThread(() -> {
            if (progress >= 100) {
                progressBar.setVisibility(View.INVISIBLE);
                progressBar.setProgress(0);
            } else {
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(progress);
            }
        });
    }

    @Override
    public void onShowCustomView(View view, android.webkit.WebChromeClient.CustomViewCallback callback) {
        runOnUiThread(() -> {
            if (customView != null) {
                callback.onCustomViewHidden();
                return;
            }

            customView = view;
            customViewCallback = callback;

            mainContentLayout.setVisibility(View.GONE);
            customViewContainer.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            customViewContainer.setVisibility(View.VISIBLE);

            WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsetsCompat.Type.systemBars());
            }
        });
    }

    @Override
    public void onHideCustomView() {
        runOnUiThread(() -> {
            if (customView == null) return;

            customViewContainer.removeView(customView);
            customViewContainer.setVisibility(View.GONE);
            mainContentLayout.setVisibility(View.VISIBLE);

            WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            if (controller != null && !isBrowserFullscreen) {
                controller.show(WindowInsetsCompat.Type.systemBars());
                controller.setAppearanceLightStatusBars(false);
            }

            if (customViewCallback != null) {
                customViewCallback.onCustomViewHidden();
            }

            customView = null;
            customViewCallback = null;
        });
    }

    // -------------------------------------------------------------------------
    // Fullscreen helpers
    // -------------------------------------------------------------------------

    private void enterBrowserFullscreen() {
        isBrowserFullscreen = true;
        toolbar.setVisibility(View.GONE);
        btnExitFullscreen.setVisibility(View.VISIBLE);

        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsetsCompat.Type.systemBars());
        }
        Toast.makeText(this, "Chế độ toàn màn hình. Bấm nút nổi hoặc phím Quay lại để thoát", Toast.LENGTH_SHORT).show();
    }

    private void exitBrowserFullscreen() {
        isBrowserFullscreen = false;
        toolbar.setVisibility(View.VISIBLE);
        btnExitFullscreen.setVisibility(View.GONE);

        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.show(WindowInsetsCompat.Type.systemBars());
            controller.setAppearanceLightStatusBars(false);
        }
    }

    // -------------------------------------------------------------------------
    // UI update helpers
    // -------------------------------------------------------------------------

    private void updateTabCountBadge() {
        int count = RustBridge.nativeGetTabCount();
        runOnUiThread(() -> tabCountBadge.setText(String.valueOf(count)));
    }

    private void updateNavButtons(WebTabView tab) {
        btnBack.setAlpha(tab.canGoBack() ? 1f : 0.4f);
        btnForward.setAlpha(tab.canGoForward() ? 1f : 0.4f);
    }

    // -------------------------------------------------------------------------
    // View factory helpers
    // -------------------------------------------------------------------------

    private ImageButton makeIconButton(int drawableResId, String contentDescription) {
        ImageButton btn = new ImageButton(this);
        btn.setImageResource(drawableResId);
        btn.setBackgroundColor(Color.TRANSPARENT);
        btn.setColorFilter(Color.WHITE);
        btn.setContentDescription(contentDescription);
        return btn;
    }

    private LinearLayout.LayoutParams iconButtonParams() {
        int size = dpToPx(44);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.gravity = Gravity.CENTER_VERTICAL;
        return lp;
    }

    private android.graphics.drawable.GradientDrawable createRoundedBackground(int color, int radius) {
        android.graphics.drawable.GradientDrawable drawable =
                new android.graphics.drawable.GradientDrawable();
        drawable.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private android.graphics.drawable.GradientDrawable createCircleBackground(int color) {
        android.graphics.drawable.GradientDrawable drawable =
                new android.graphics.drawable.GradientDrawable();
        drawable.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    // -------------------------------------------------------------------------
    // Session persistence
    // -------------------------------------------------------------------------

    private static final String PREF_NAME = "browser_session";
    private static final String KEY_SAVED_TABS = "saved_tabs";
    private static final String KEY_ACTIVE_TAB_INDEX = "active_tab_index";

    private void saveSession() {
        if (isRestoringSession) return; // Don't save while restoring — prevents duplication
        try {
            SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
            JSONArray array = new JSONArray();
            int activeIndex = 0;
            int index = 0;

            for (Map.Entry<Integer, WebTabView> entry : tabViews.entrySet()) {
                int tabId = entry.getKey();
                WebTabView tab = entry.getValue();
                String currentUrl = tab.getWebView().getUrl();
                if (currentUrl == null || currentUrl.trim().isEmpty() || currentUrl.equals("about:blank")) {
                    currentUrl = tab.getInitialUrl();
                }
                if (currentUrl == null || currentUrl.trim().isEmpty() || currentUrl.equals("about:blank")) {
                    currentUrl = "https://www.google.com";
                }
                JSONObject obj = new JSONObject();
                obj.put("url", currentUrl);
                array.put(obj);

                if (tabId == activeTabId) {
                    activeIndex = index;
                }
                index++;
            }

            if (array.length() > 0) {
                prefs.edit()
                        .putString(KEY_SAVED_TABS, array.toString())
                        .putInt(KEY_ACTIVE_TAB_INDEX, activeIndex)
                        .apply();
            } else {
                // Clear saved session if all tabs are closed
                prefs.edit()
                        .remove(KEY_SAVED_TABS)
                        .remove(KEY_ACTIVE_TAB_INDEX)
                        .apply();
            }
        } catch (Exception e) {
            android.util.Log.e("MainActivity", "Failed to save session", e);
        }
    }

    // -------------------------------------------------------------------------
    // URL matching and Intent handling
    // -------------------------------------------------------------------------

    /**
     * Normalize URL for matching (strips scheme, trailing slashes, and www).
     */
    private String normalizeUrl(String url) {
        if (url == null) return "";
        String s = url.trim().toLowerCase();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.startsWith("https://")) {
            s = s.substring(8);
        } else if (s.startsWith("http://")) {
            s = s.substring(7);
        }
        if (s.startsWith("www.")) {
            s = s.substring(4);
        }
        return s;
    }

    /**
     * Check if any existing tab already contains the given target URL.
     *
     * @param targetUrl The URL to check.
     * @return Existing tab ID or -1 if not found.
     */
    private int findExistingTabByUrl(String targetUrl) {
        if (targetUrl == null || targetUrl.trim().isEmpty()) return -1;
        String normalizedTarget = normalizeUrl(targetUrl);
        if (normalizedTarget.isEmpty()) return -1;

        for (Map.Entry<Integer, WebTabView> entry : tabViews.entrySet()) {
            WebTabView tab = entry.getValue();
            String currentUrl = tab.getWebView().getUrl();
            if (currentUrl != null && !currentUrl.isEmpty()) {
                if (normalizeUrl(currentUrl).equals(normalizedTarget)) {
                    return entry.getKey();
                }
            }
            String initialUrl = tab.getInitialUrl();
            if (initialUrl != null && !initialUrl.isEmpty()) {
                if (normalizeUrl(initialUrl).equals(normalizedTarget)) {
                    return entry.getKey();
                }
            }
        }
        return -1;
    }

    /**
     * Handle incoming Intent (e.g. from home screen pinned shortcut or external link).
     */
    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        android.net.Uri uri = intent.getData();
        if (uri == null) return;
        String targetUrl = uri.toString().trim();
        if (targetUrl.isEmpty()) return;

        int existingTabId = findExistingTabByUrl(targetUrl);
        if (existingTabId != -1) {
            // Already open in an existing tab! Just switch to it.
            showTab(existingTabId);
        } else {
            // Not open in any tab -> create new tab
            openNewTab(targetUrl);
        }
    }

    private void restoreOrInitSession(String launchUrl) {
        // Clear any old tabs in Rust runtime first so Rust and Java are 100% in sync
        RustBridge.nativeClearTabs();

        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String savedTabsJson = prefs.getString(KEY_SAVED_TABS, null);
        int activeIndex = prefs.getInt(KEY_ACTIVE_TAB_INDEX, 0);

        boolean restored = false;
        if (savedTabsJson != null && !savedTabsJson.trim().isEmpty()) {
            try {
                JSONArray array = new JSONArray(savedTabsJson);
                if (array.length() > 0) {
                    // Suppress saveSession() calls during restore
                    isRestoringSession = true;

                    List<Integer> createdTabIds = new ArrayList<>();
                    boolean hasGoogleTab = false;
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject obj = array.getJSONObject(i);
                        String url = obj.optString("url", "https://www.google.com");
                        if (url.trim().isEmpty() || url.equals("about:blank")) {
                            url = "https://www.google.com";
                        }

                        // Prevent restoring duplicate Google tabs if user accumulated multiple identical Google tabs in prefs
                        boolean isGoogle = normalizeUrl(url).equals("google.com");
                        if (isGoogle) {
                            if (hasGoogleTab) {
                                continue; // Skip duplicate default Google tabs
                            }
                            hasGoogleTab = true;
                        }

                        int tabId = RustBridge.nativeCreateTab(url);
                        WebTabView tabView = new WebTabView(this, tabId, url, this);
                        tabView.getWebView().setDownloadListener(DownloadHandler.createFor(this, tabId));
                        CookieManager.getInstance().setAcceptThirdPartyCookies(tabView.getWebView(), true);
                        tabViews.put(tabId, tabView);
                        createdTabIds.add(tabId);
                    }

                    if (!createdTabIds.isEmpty()) {
                        int targetTabId = -1;
                        if (launchUrl != null && !launchUrl.isEmpty()) {
                            int matchingId = findExistingTabByUrl(launchUrl);
                            if (matchingId != -1) {
                                targetTabId = matchingId;
                            }
                        }

                        if (targetTabId == -1) {
                            int targetIndex = Math.min(Math.max(0, activeIndex), createdTabIds.size() - 1);
                            targetTabId = createdTabIds.get(targetIndex);
                        }

                        showTab(targetTabId);
                        updateTabCountBadge();
                        restored = true;
                    }

                    // Re-enable saving and persist the restored session
                    isRestoringSession = false;
                    saveSession();
                }
            } catch (Exception e) {
                android.util.Log.e("MainActivity", "Failed to restore session", e);
                isRestoringSession = false;
            }
        }

        if (!restored) {
            String initial = (launchUrl != null && !launchUrl.isEmpty()) ? launchUrl : "https://www.google.com";
            openNewTab(initial);
        } else if (launchUrl != null && !launchUrl.isEmpty()) {
            if (findExistingTabByUrl(launchUrl) == -1) {
                openNewTab(launchUrl);
            }
        }
    }
}
