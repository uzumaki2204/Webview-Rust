package com.example.webviewbrowser;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Chrome-style full-screen tab-switcher overlay.
 *
 * <p>Launched by {@link MainActivity} with
 * {@link androidx.activity.result.ActivityResultLauncher} (or
 * {@link android.app.Activity#startActivityForResult}). Returns one of:</p>
 * <ul>
 *   <li>{@link android.app.Activity#RESULT_OK} + {@link #EXTRA_SELECTED_TAB_ID} — user tapped a tab</li>
 *   <li>{@link android.app.Activity#RESULT_OK} + {@link #EXTRA_CREATE_NEW_TAB} — user tapped "+"</li>
 *   <li>{@link android.app.Activity#RESULT_CANCELED} — user pressed back</li>
 * </ul>
 *
 * <p>Tab thumbnails are passed via the static map {@link #thumbnails} to avoid
 * Bitmap serialisation overhead through Intent extras.</p>
 */
public class TabSwitcherActivity extends AppCompatActivity {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    /** Request code to use when starting this Activity with startActivityForResult. */
    public static final int REQUEST_CODE = 1001;

    /** Intent extra key: JSON array string describing all tabs (same format as nativeGetTabListJson). */
    public static final String EXTRA_TAB_LIST_JSON = "extra_tab_list_json";

    /** Intent extra key: the tab ID that is currently active in MainActivity. */
    public static final String EXTRA_ACTIVE_TAB_ID = "extra_active_tab_id";

    /** Result extra key: the tab ID the user selected. */
    public static final String EXTRA_SELECTED_TAB_ID = "extra_selected_tab_id";

    /** Result extra key: set to {@code true} when the user wants a new tab. */
    public static final String EXTRA_CREATE_NEW_TAB = "extra_create_new_tab";

    /** Result extra key: list of tab IDs closed while inside TabSwitcherActivity. */
    public static final String EXTRA_CLOSED_TAB_IDS = "extra_closed_tab_ids";

    // -------------------------------------------------------------------------
    // Static thumbnail store (avoids Parcel size limits for Bitmaps)
    // -------------------------------------------------------------------------

    /**
     * Populated by {@link MainActivity} before launching this Activity.
     * Keyed by tab ID; value may be {@code null} if no snapshot was available.
     */
    public static final Map<Integer, Bitmap> thumbnails = new HashMap<>();

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private TabAdapter adapter;
    private final List<TabItem> tabItems = new ArrayList<>();
    private final ArrayList<Integer> closedTabIds = new ArrayList<>();
    private int activeTabId = -1;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(0xFF303134);
        }
        androidx.core.view.WindowInsetsControllerCompat controller = androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.setAppearanceLightStatusBars(false);
        }

        // Build layout programmatically (avoids requiring XML layout files).
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(0xFF303134); // Match toolbar color behind status bar / notch

        // Apply system window insets so toolbar is never cut off by notch or status bar
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets sysInsets = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.statusBars()
                    | androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            );
            v.setPadding(sysInsets.left, sysInsets.top, sysInsets.right, 0);
            return insets;
        });

        // --- Toolbar ---
        Toolbar toolbar = new Toolbar(this);
        toolbar.setBackgroundColor(0xFF303134);
        toolbar.setTitle("Tabs");
        toolbar.setTitleTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams toolbarParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(56));
        root.addView(toolbar, toolbarParams);

        // "+" button in toolbar
        ImageButton btnNewTab = new ImageButton(this);
        btnNewTab.setImageResource(android.R.drawable.ic_input_add);
        btnNewTab.setBackgroundColor(0x00000000); // transparent
        btnNewTab.setContentDescription("New Tab");
        btnNewTab.setColorFilter(0xFFFFFFFF);
        Toolbar.LayoutParams addBtnParams = new Toolbar.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        addBtnParams.gravity = android.view.Gravity.END;
        toolbar.addView(btnNewTab, addBtnParams);

        // --- RecyclerView ---
        RecyclerView recyclerView = new RecyclerView(this);
        recyclerView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        recyclerView.setLayoutManager(new GridLayoutManager(this, 2));
        recyclerView.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));
        recyclerView.setClipToPadding(false);
        root.addView(recyclerView);

        setContentView(root);

        // --- Parse Intent extras ---
        Intent intent = getIntent();
        activeTabId = intent.getIntExtra(EXTRA_ACTIVE_TAB_ID, -1);
        String tabListJson = intent.getStringExtra(EXTRA_TAB_LIST_JSON);
        parseTabList(tabListJson);

        // --- Adapter ---
        adapter = new TabAdapter(tabItems);
        recyclerView.setAdapter(adapter);

        // --- Listeners ---
        btnNewTab.setOnClickListener(v -> finishWithResult(-1, true));
    }

    private void finishWithResult(int selectedTabId, boolean createNewTab) {
        Intent result = new Intent();
        result.putIntegerArrayListExtra(EXTRA_CLOSED_TAB_IDS, closedTabIds);
        if (selectedTabId != -1) {
            result.putExtra(EXTRA_SELECTED_TAB_ID, selectedTabId);
        }
        if (createNewTab) {
            result.putExtra(EXTRA_CREATE_NEW_TAB, true);
        }
        setResult(RESULT_OK, result);
        finish();
    }

    @Override
    public void onBackPressed() {
        finishWithResult(-1, false);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Parse the JSON tab list (same schema as {@code nativeGetTabListJson}) into {@link #tabItems}. */
    private void parseTabList(String json) {
        tabItems.clear();
        if (json == null || json.isEmpty()) return;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                int id = obj.optInt("id", -1);
                String title = obj.optString("title", "New Tab");
                String url = obj.optString("url", "");
                Bitmap thumb = thumbnails.get(id);
                tabItems.add(new TabItem(id, title, url, thumb));
            }
        } catch (Exception e) {
            // Malformed JSON — show empty list.
        }
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    // -------------------------------------------------------------------------
    // Data model
    // -------------------------------------------------------------------------

    /** Lightweight data model for a single tab card. */
    private static class TabItem {
        final int id;
        String title;
        final String url;
        final Bitmap thumbnail; // may be null

        TabItem(int id, String title, String url, Bitmap thumbnail) {
            this.id = id;
            this.title = (title != null && !title.isEmpty()) ? title : "New Tab";
            this.url = url;
            this.thumbnail = thumbnail;
        }
    }

    // -------------------------------------------------------------------------
    // RecyclerView Adapter
    // -------------------------------------------------------------------------

    private class TabAdapter extends RecyclerView.Adapter<TabAdapter.TabViewHolder> {

        private final List<TabItem> items;

        TabAdapter(List<TabItem> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public TabViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            // Build each card programmatically.
            FrameLayout card = buildCardView(parent);
            return new TabViewHolder(card);
        }

        @Override
        public void onBindViewHolder(@NonNull TabViewHolder holder, int position) {
            TabItem item = items.get(position);
            boolean isActive = (item.id == activeTabId);

            // Thumbnail
            if (item.thumbnail != null) {
                holder.thumbnail.setImageBitmap(item.thumbnail);
                holder.thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            } else {
                holder.thumbnail.setImageResource(android.R.drawable.ic_menu_gallery);
                holder.thumbnail.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            }

            // Title
            holder.title.setText(item.title);

            // Highlight border for active tab
            if (isActive) {
                ((FrameLayout) holder.itemView).setForeground(
                        getResources().getDrawable(android.R.drawable.list_selector_background, null));
                holder.itemView.setBackgroundColor(0xFF4285F4); // Google-blue highlight
            } else {
                holder.itemView.setBackgroundColor(0xFF3C3C3F);
            }

            // Tap card → select tab
            holder.itemView.setOnClickListener(v -> finishWithResult(item.id, false));

            // Tap close button
            holder.closeBtn.setOnClickListener(v -> {
                int pos = holder.getAdapterPosition();
                if (pos == RecyclerView.NO_ID || pos < 0 || pos >= items.size()) return;
                TabItem removed = items.get(pos);
                closedTabIds.add(removed.id);
                RustBridge.nativeCloseTab(removed.id);
                items.remove(pos);
                notifyItemRemoved(pos);
                if (items.isEmpty()) {
                    finishWithResult(-1, false);
                }
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        // --- ViewHolder ---

        class TabViewHolder extends RecyclerView.ViewHolder {
            final ImageView thumbnail;
            final TextView title;
            final ImageButton closeBtn;

            TabViewHolder(@NonNull View itemView) {
                super(itemView);
                thumbnail = itemView.findViewWithTag("thumbnail");
                title = itemView.findViewWithTag("title");
                closeBtn = itemView.findViewWithTag("close");
            }
        }

        // --- Card layout builder ---

        /**
         * Builds a single tab card programmatically:
         * <pre>
         * ┌──────────────────────┐
         * │ [thumbnail 16:9]  [X]│
         * │ [title]              │
         * └──────────────────────┘
         * </pre>
         */
        private FrameLayout buildCardView(ViewGroup parent) {
            int margin = dpToPx(6);
            int cornerRadius = dpToPx(8);

            // Outer card (FrameLayout with margin)
            FrameLayout card = new FrameLayout(parent.getContext());
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(margin, margin, margin, margin);
            card.setLayoutParams(lp);
            card.setBackgroundColor(0xFF3C3C3F);

            // Inner vertical layout
            LinearLayout inner = new LinearLayout(parent.getContext());
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            // --- Thumbnail row (thumbnail + close button overlaid) ---
            FrameLayout thumbContainer = new FrameLayout(parent.getContext());
            int thumbHeight = dpToPx(120); // approximates 16:9 in a narrow card
            thumbContainer.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, thumbHeight));

            ImageView thumbnail = new ImageView(parent.getContext());
            thumbnail.setTag("thumbnail");
            thumbnail.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbnail.setBackgroundColor(0xFF1C1C1E);
            thumbContainer.addView(thumbnail);

            // Close (X) button overlaid in top-right of thumbnail
            ImageButton closeBtn = new ImageButton(parent.getContext());
            closeBtn.setTag("close");
            closeBtn.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
            closeBtn.setBackgroundColor(0xCC000000);
            closeBtn.setColorFilter(0xFFFFFFFF);
            closeBtn.setContentDescription("Close tab");
            FrameLayout.LayoutParams closeBtnParams = new FrameLayout.LayoutParams(
                    dpToPx(32), dpToPx(32));
            closeBtnParams.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
            closeBtnParams.setMargins(0, dpToPx(4), dpToPx(4), 0);
            closeBtn.setLayoutParams(closeBtnParams);
            thumbContainer.addView(closeBtn);

            inner.addView(thumbContainer);

            // --- Title ---
            TextView title = new TextView(parent.getContext());
            title.setTag("title");
            title.setTextColor(0xFFFFFFFF);
            title.setTextSize(12f);
            title.setMaxLines(1);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setPadding(dpToPx(6), dpToPx(4), dpToPx(6), dpToPx(6));
            title.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            inner.addView(title);

            card.addView(inner);
            return card;
        }
    }
}
