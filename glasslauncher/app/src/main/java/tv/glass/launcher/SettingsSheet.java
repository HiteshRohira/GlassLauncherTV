package tv.glass.launcher;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Full-height settings sheet on the right edge of the screen.
 *
 * <p>Every page is a list of {@link Row}s that the host rebuilds from current
 * state whenever the page is shown or refreshed, so adding a setting means
 * adding a row rather than new views and key handling. The sheet owns the page
 * stack (Back returns to the row that opened a page), remote navigation,
 * scrolling and the slide-in animation, and draws with the shared
 * {@link EdgeGlassStyle} panel so it matches the app menus.
 */
final class SettingsSheet {

    interface Host {
        FrameLayout root();
        Interpolator menuIn();
        Interpolator menuOut();
        boolean isLetThroughKey(int keyCode);
        /** Called as soon as the sheet starts closing. */
        void onSheetClosing();
        /** Called once the close animation has finished. */
        void onSheetHidden();
    }

    static final int VALUE_PLAIN = 0;
    static final int VALUE_ACCENT = 1;
    static final int VALUE_OFF = 2;
    static final int VALUE_LINK = 3;

    static final int WIDTH_DP = 340;
    private static final int ICON_SLOT_DP = 22;

    private static final int DESC_IDLE = 0x8CFFFFFF;
    private static final int DESC_SELECTED = 0xFF5C5C63;
    private static final int OFF_IDLE = 0x66FFFFFF;
    private static final int OFF_SELECTED = 0x66111114;
    private static final int LINK_IDLE = 0x99FFFFFF;
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);

    /** One line of a page. */
    static final class Row {
        final String key;
        final CharSequence label;
        CharSequence description;
        CharSequence value;
        int valueStyle = VALUE_PLAIN;
        int valueColor;
        boolean dimmed;
        boolean info;
        Bitmap icon;
        String iconKey;
        View iconView;
        int iconSizeDp = ICON_SLOT_DP;
        Runnable activate;
        IntConsumer step;

        Row(String key, CharSequence label) {
            this.key = key;
            this.label = label;
        }

        /** A line of muted text that the cursor skips. */
        static Row info(String key, CharSequence text) {
            Row row = new Row(key, text);
            row.info = true;
            return row;
        }

        Row description(CharSequence text) { description = text; return this; }
        Row value(CharSequence text, int style) { value = text; valueStyle = style; return this; }
        /** Idle colour for an accent value, used by the focus colour swatch. */
        Row valueColor(int color) { valueColor = color; return this; }
        /** Show the value faded, for a setting that has no effect right now. */
        Row dimmed(boolean faded) { dimmed = faded; return this; }
        /** App icon, or {@code null} with {@code packageKey} when it is still loading. */
        Row icon(Bitmap bitmap, String packageKey) { icon = bitmap; iconKey = packageKey; return this; }
        Row iconView(View view, int sizeDp) { iconView = view; iconSizeDp = sizeDp; return this; }
        Row onActivate(Runnable action) { activate = action; return this; }
        /** Left/Right handler; OK also steps forward when there is no activate action. */
        Row onStep(IntConsumer handler) { step = handler; return this; }
    }

    /** A page: title, remote hint, rows and an optional footer line. */
    static final class Page {
        final String id;
        final CharSequence title;
        final CharSequence hint;
        final Supplier<List<Row>> rows;
        Supplier<CharSequence> footer;
        String initialKey;
        Runnable onLeave;
        /** Return true when the page handled Back itself. */
        BooleanSupplier onBack;

        Page(String id, CharSequence title, CharSequence hint, Supplier<List<Row>> rows) {
            this.id = id;
            this.title = title;
            this.hint = hint;
            this.rows = rows;
        }
    }

    private static final class Entry {
        final Page page;
        String selectedKey;

        Entry(Page page) { this.page = page; }
    }

    private static final class RowViews {
        View root;
        TextView label;
        TextView description;
        TextView value;
        ImageView image;
        View iconView;
    }

    private final Activity activity;
    private final Host host;
    private final float density;
    private final List<Entry> stack = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final List<RowViews> views = new ArrayList<>();
    private int selected = -1;
    private boolean showing;

    private FrameLayout overlay;
    private LinearLayout sheet;
    private TextView header;
    private ScrollView scroll;
    private LinearLayout column;
    private TextView footer;
    private TextView hint;

    SettingsSheet(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
        this.density = activity.getResources().getDisplayMetrics().density;
    }

    // ── Navigation ───────────────────────────────────────────────────────

    void show(Page root) {
        if (overlay == null) build();
        if (overlay == null) return;
        stack.clear();
        stack.add(new Entry(root));
        showing = true;
        render(root.initialKey, 0);
        overlay.setVisibility(View.VISIBLE);
        overlay.bringToFront();
        overlay.requestFocus();
        sheet.animate().cancel();
        sheet.setTranslationX(dp(WIDTH_DP));
        sheet.setAlpha(0f);
        sheet.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(220)
                .setInterpolator(host.menuIn())
                .withLayer()
                .start();
    }

    void hide() {
        if (!showing) return;
        showing = false;
        List<Entry> open = new ArrayList<>(stack);
        stack.clear();
        for (int i = open.size() - 1; i >= 0; i--) runLeave(open.get(i).page);
        host.onSheetClosing();
        final FrameLayout ov = overlay;
        sheet.animate().cancel();
        sheet.animate()
                .translationX(dp(WIDTH_DP))
                .alpha(0f)
                .setDuration(160)
                .setInterpolator(host.menuOut())
                .withLayer()
                .withEndAction(() -> {
                    if (showing) return;
                    ov.setVisibility(View.GONE);
                    host.onSheetHidden();
                })
                .start();
    }

    void push(Page page) {
        if (!showing) return;
        Entry top = top();
        if (top != null) top.selectedKey = selectedKey();
        stack.add(new Entry(page));
        render(page.initialKey, 1);
    }

    void back() {
        Entry top = top();
        if (top == null) { hide(); return; }
        if (top.page.onBack != null && top.page.onBack.getAsBoolean()) return;
        if (stack.size() <= 1) { hide(); return; }
        stack.remove(stack.size() - 1);
        runLeave(top.page);
        Entry parent = top();
        if (parent != null) render(parent.selectedKey, -1);
    }

    /** Rebuild the current page's rows from state, keeping the selection. */
    void refresh() {
        Entry top = top();
        if (!showing || top == null) return;
        List<Row> next = top.page.rows.get();
        String key = selectedKey();
        if (sameKeys(next)) {
            rows.clear();
            rows.addAll(next);
            for (int i = 0; i < rows.size(); i++) bind(views.get(i), rows.get(i));
            bindChrome(top.page);
            paint();
            return;
        }
        int oldIndex = selected;
        render(key, 0);
        if (indexOfKey(key) < 0 && !rows.isEmpty()) {
            selected = nearestSelectable(Math.min(Math.max(oldIndex, 0), rows.size() - 1));
            paint();
            scrollToSelected(false, 0);
        }
    }

    /** Show an app icon that finished loading after its row was drawn. */
    void deliverIcon(String packageKey, Bitmap bitmap) {
        if (!showing || packageKey == null || bitmap == null) return;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (!packageKey.equals(row.iconKey)) continue;
            row.icon = bitmap;
            ImageView image = views.get(i).image;
            if (image != null) {
                image.setImageBitmap(bitmap);
                image.setVisibility(View.VISIBLE);
            }
        }
    }

    boolean handleKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                move(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                move(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                step(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                step(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                activate(selected);
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                back();
                return true;
            default:
                return !host.isLetThroughKey(keyCode);
        }
    }

    // ── State for the activity and tests ─────────────────────────────────

    boolean isShowing() { return showing; }

    String pageId() {
        Entry top = top();
        return top != null ? top.page.id : null;
    }

    int rowCount() { return rows.size(); }

    String rowKey(int index) { return rows.get(index).key; }

    CharSequence rowLabel(int index) { return rows.get(index).label; }

    CharSequence rowValue(int index) { return rows.get(index).value; }

    CharSequence rowDescription(int index) { return rows.get(index).description; }

    int selectedIndex() { return selected; }

    String selectedKey() {
        return selected >= 0 && selected < rows.size() ? rows.get(selected).key : null;
    }

    int indexOfKey(String key) {
        if (key == null) return -1;
        for (int i = 0; i < rows.size(); i++) if (key.equals(rows.get(i).key)) return i;
        return -1;
    }

    /** Select the row with {@code key} and run it as if OK was pressed. */
    boolean activateKey(String key) {
        int index = indexOfKey(key);
        if (index < 0 || rows.get(index).info) return false;
        selected = index;
        paint();
        activate(index);
        return true;
    }

    CharSequence titleText() { return header != null ? header.getText() : null; }

    CharSequence hintText() { return hint != null ? hint.getText() : null; }

    CharSequence footerText() {
        return footer != null && footer.getVisibility() == View.VISIBLE ? footer.getText() : null;
    }

    View sheetView() { return sheet; }

    View rowView(int index) { return views.get(index).root; }

    // ── Rendering ────────────────────────────────────────────────────────

    private void build() {
        FrameLayout root = host.root();
        if (root == null) return;
        FrameLayout ov = new FrameLayout(activity) {
            @Override public boolean onTouchEvent(MotionEvent ev) {
                View s = sheet;
                if (ev.getAction() == MotionEvent.ACTION_DOWN && s != null && ev.getX() < s.getX()) {
                    hide();
                }
                return true;
            }
        };
        ov.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ov.setVisibility(View.GONE);
        ov.setClickable(true);
        ov.setFocusable(true);

        // The right edge bleeds off screen so only the left corners are rounded;
        // the top and bottom rims sit just past the screen edge.
        int bleed = dp(EdgeGlassStyle.PANEL_RADIUS_DP + 2);
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        EdgeGlassStyle.applyPanel(panel, density);
        panel.setPadding(dp(10), dp(24), dp(10) + bleed, dp(14));
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                dp(WIDTH_DP) + bleed, ViewGroup.LayoutParams.MATCH_PARENT);
        panelLp.gravity = Gravity.END;
        panelLp.rightMargin = -bleed;
        panelLp.topMargin = -dp(2);
        panelLp.bottomMargin = -dp(2);
        ov.addView(panel, panelLp);

        TextView title = text(11, MEDIUM);
        title.setLetterSpacing(0.08f);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(12), 0, dp(12), dp(12));
        panel.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView sv = new ScrollView(activity);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setVerticalFadingEdgeEnabled(true);
        sv.setFadingEdgeLength(dp(20));
        sv.setFocusable(false);
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        sv.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView note = text(10, REGULAR);
        note.setTextColor(0x73FFFFFF);
        note.setPadding(dp(12), dp(10), dp(12), 0);
        note.setVisibility(View.GONE);
        panel.addView(note, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View divider = new View(activity);
        divider.setBackgroundColor(0x14FFFFFF);
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2));
        dividerLp.topMargin = dp(10);
        dividerLp.leftMargin = dp(12);
        dividerLp.rightMargin = dp(12);
        panel.addView(divider, dividerLp);

        TextView keys = text(9, REGULAR);
        keys.setTextColor(0x80FFFFFF);
        keys.setSingleLine(true);
        keys.setPadding(dp(12), dp(8), dp(12), 0);
        panel.addView(keys, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(ov);
        overlay = ov;
        sheet = panel;
        header = title;
        scroll = sv;
        column = col;
        footer = note;
        hint = keys;
    }

    private void render(String selectKey, int direction) {
        Entry top = top();
        if (top == null || column == null) return;
        rows.clear();
        rows.addAll(top.page.rows.get());
        column.removeAllViews();
        views.clear();
        for (Row row : rows) {
            RowViews v = buildRow(row);
            views.add(v);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(2);
            column.addView(v.root, lp);
        }
        bindChrome(top.page);
        int index = indexOfKey(selectKey);
        selected = index >= 0 && !rows.get(index).info ? index : nearestSelectable(0);
        paint();
        scroll.scrollTo(0, 0);
        scrollToSelected(false, 0);
        if (direction != 0) {
            column.animate().cancel();
            column.setAlpha(0f);
            column.setTranslationX(dp(16) * direction);
            column.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setDuration(160)
                    .setInterpolator(host.menuIn())
                    .start();
        }
    }

    private void bindChrome(Page page) {
        header.setText(breadcrumb());
        hint.setText(page.hint);
        CharSequence note = page.footer != null ? page.footer.get() : null;
        footer.setText(note);
        footer.setVisibility(note == null || note.length() == 0 ? View.GONE : View.VISIBLE);
    }

    /** Parent and current page names; the parent is where Back goes. */
    private CharSequence breadcrumb() {
        SpannableStringBuilder text = new SpannableStringBuilder();
        for (int i = Math.max(0, stack.size() - 2); i < stack.size(); i++) {
            if (text.length() > 0) text.append("  ›  ");
            int start = text.length();
            text.append(stack.get(i).page.title.toString().toUpperCase(Locale.getDefault()));
            int color = i == stack.size() - 1 ? 0xDDFFFFFF : 0x80FFFFFF;
            text.setSpan(new ForegroundColorSpan(color), start, text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return text;
    }

    private RowViews buildRow(Row row) {
        RowViews v = new RowViews();
        if (row.info) {
            TextView t = text(11, REGULAR);
            t.setTextColor(0x80FFFFFF);
            t.setPadding(dp(12), dp(8), dp(12), dp(8));
            v.root = t;
            v.label = t;
            bind(v, row);
            return v;
        }
        LinearLayout line = new LinearLayout(activity);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(12), dp(9), dp(12), dp(9));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(EdgeGlassStyle.rowRadiusPx(density));
        bg.setColor(EdgeGlassStyle.ROW_IDLE_BG);
        line.setBackground(bg);

        if (row.iconView != null) {
            FrameLayout slot = new FrameLayout(activity);
            slot.addView(row.iconView, new FrameLayout.LayoutParams(
                    dp(row.iconSizeDp), dp(row.iconSizeDp), Gravity.CENTER));
            LinearLayout.LayoutParams slotLp = new LinearLayout.LayoutParams(dp(ICON_SLOT_DP), dp(ICON_SLOT_DP));
            slotLp.setMarginEnd(dp(12));
            line.addView(slot, slotLp);
            v.iconView = row.iconView;
        } else if (row.icon != null || row.iconKey != null) {
            ImageView image = new ImageView(activity);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            image.setClipToOutline(true);
            LinearLayout.LayoutParams imageLp = new LinearLayout.LayoutParams(dp(ICON_SLOT_DP), dp(ICON_SLOT_DP));
            imageLp.setMarginEnd(dp(12));
            line.addView(image, imageLp);
            v.image = image;
        }

        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView label = text(13, MEDIUM);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(label);
        TextView desc = text(10, REGULAR);
        desc.setMaxLines(2);
        desc.setEllipsize(TextUtils.TruncateAt.END);
        desc.setPadding(0, dp(1), 0, 0);
        texts.addView(desc);
        line.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView value = text(13, REGULAR);
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.END);
        value.setMaxWidth(dp(150));
        value.setGravity(Gravity.END);
        LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        valueLp.setMarginStart(dp(12));
        line.addView(value, valueLp);

        final String key = row.key;
        line.setClickable(true);
        line.setOnClickListener(view -> {
            int index = indexOfKey(key);
            if (index < 0) return;
            view.playSoundEffect(SoundEffectConstants.CLICK);
            selected = index;
            paint();
            activate(index);
        });

        v.root = line;
        v.label = label;
        v.description = desc;
        v.value = value;
        bind(v, row);
        return v;
    }

    private void bind(RowViews v, Row row) {
        v.label.setText(row.label);
        if (row.info) return;
        boolean hasDesc = row.description != null && row.description.length() > 0;
        v.description.setText(row.description);
        v.description.setVisibility(hasDesc ? View.VISIBLE : View.GONE);
        boolean hasValue = row.value != null && row.value.length() > 0;
        v.value.setText(row.value);
        v.value.setVisibility(hasValue ? View.VISIBLE : View.GONE);
        v.value.setAlpha(row.dimmed ? 0.45f : 1f);
        if (v.image != null) {
            v.image.setImageBitmap(row.icon);
            v.image.setVisibility(row.icon != null ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private void paint() {
        for (int i = 0; i < views.size(); i++) {
            Row row = rows.get(i);
            if (row.info) continue;
            RowViews v = views.get(i);
            boolean sel = i == selected;
            if (v.root.getBackground() instanceof GradientDrawable) {
                ((GradientDrawable) v.root.getBackground())
                        .setColor(sel ? EdgeGlassStyle.SELECTED_PILL : EdgeGlassStyle.ROW_IDLE_BG);
            }
            v.label.setTextColor(sel ? EdgeGlassStyle.SELECTED_TEXT : EdgeGlassStyle.IDLE_TEXT);
            v.description.setTextColor(sel ? DESC_SELECTED : DESC_IDLE);
            v.value.setTextColor(valueColor(row, sel));
            if (v.iconView != null) v.iconView.setSelected(sel);
        }
    }

    private static int valueColor(Row row, boolean selected) {
        switch (row.valueStyle) {
            case VALUE_ACCENT:
                if (selected) return EdgeGlassStyle.SELECTED_TEXT;
                return row.valueColor != 0 ? row.valueColor : EdgeGlassStyle.VALUE_ACCENT;
            case VALUE_OFF:
                return selected ? OFF_SELECTED : OFF_IDLE;
            case VALUE_LINK:
                return selected ? EdgeGlassStyle.SELECTED_TEXT : LINK_IDLE;
            default:
                return selected ? EdgeGlassStyle.SELECTED_TEXT : EdgeGlassStyle.IDLE_TEXT;
        }
    }

    private void move(int direction) {
        int n = rows.size();
        if (n == 0 || selected < 0) return;
        for (int i = 1; i <= n; i++) {
            int index = Math.floorMod(selected + direction * i, n);
            if (!rows.get(index).info) {
                selected = index;
                break;
            }
        }
        paint();
        scrollToSelected(true, 0);
    }

    private void step(int direction) {
        if (selected < 0 || selected >= rows.size()) return;
        IntConsumer handler = rows.get(selected).step;
        if (handler != null) handler.accept(direction);
    }

    private void activate(int index) {
        if (index < 0 || index >= rows.size()) return;
        Row row = rows.get(index);
        if (row.activate != null) row.activate.run();
        else if (row.step != null) row.step.accept(1);
    }

    private void scrollToSelected(boolean smooth, int attempt) {
        if (selected < 0 || selected >= views.size() || scroll == null) return;
        View row = views.get(selected).root;
        if (row.getHeight() == 0 || scroll.getHeight() == 0) {
            if (attempt < 3) scroll.post(() -> scrollToSelected(false, attempt + 1));
            return;
        }
        int margin = dp(28);
        int top = row.getTop() - margin;
        int bottom = row.getBottom() + margin;
        int viewTop = scroll.getScrollY();
        int viewBottom = viewTop + scroll.getHeight();
        int target;
        if (top < viewTop) target = Math.max(0, top);
        else if (bottom > viewBottom) target = bottom - scroll.getHeight();
        else return;
        if (smooth) scroll.smoothScrollTo(0, target);
        else scroll.scrollTo(0, target);
    }

    private boolean sameKeys(List<Row> next) {
        if (next.size() != rows.size()) return false;
        for (int i = 0; i < next.size(); i++) {
            Row a = next.get(i);
            Row b = rows.get(i);
            if (!a.key.equals(b.key) || a.info != b.info) return false;
            if ((a.iconView != null) != (b.iconView != null)) return false;
            if ((a.icon != null || a.iconKey != null) != (b.icon != null || b.iconKey != null)) return false;
        }
        return true;
    }

    private int nearestSelectable(int from) {
        for (int i = from; i < rows.size(); i++) if (!rows.get(i).info) return i;
        for (int i = from - 1; i >= 0; i--) if (!rows.get(i).info) return i;
        return -1;
    }

    private Entry top() {
        return stack.isEmpty() ? null : stack.get(stack.size() - 1);
    }

    private static void runLeave(Page page) {
        if (page.onLeave != null) page.onLeave.run();
    }

    private TextView text(int sp, Typeface typeface) {
        TextView t = new TextView(activity);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(typeface);
        t.setIncludeFontPadding(true);
        return t;
    }

    private int dp(int value) {
        return Math.round(value * density);
    }
}
