/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.languagetool.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.content.res.ColorStateList;
import android.graphics.drawable.StateListDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Html;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.UnderlineSpan;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.languagetool.offline.OfflineChecker;
import org.languagetool.offline.OfflineChecker.Issue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Native Java editor; every check runs on one background thread. */
public final class MainActivity extends Activity {
    private static final int PURPLE = 0xFF667EEA;
    private static final int MUTED = 0xFF6B7280;
    private static final int INK = 0xFF1A1A2E;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.DiscardOldestPolicy());
    private final OfflineChecker checker = new OfflineChecker();
    private EditText editor;
    private TextView status;
    private TextView counter;
    private LinearLayout suggestions;
    private ProgressBar progress;
    private Spinner language;
    private RadioGroup varieties;
    private LinearLayout catalanOptions;
    private TextView spellingCount, grammarCount, styleCount;
    private AlertDialog correctionPopup;
    private long revision;
    private boolean destroyed;
    private boolean ready;
    private String checkedText;
    private String checkedDialect;
    private List<Issue> displayedIssues;
    private long lastCheckMilliseconds;
    private final Runnable delayedCheck = this::check;

    @Override
    public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(gradient(0));
        FrameLayout canvas = new FrameLayout(this);
        canvas.setPadding(dp(12), dp(20), dp(12), dp(24));
        scroll.addView(canvas);
        LinearLayout root = column();
        root.setPadding(dp(20), dp(18), dp(20), dp(20));
        root.setBackground(box(Color.WHITE, Color.WHITE, 18));
        root.setElevation(dp(8));
        int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(24), dp(760));
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(width, -2);
        cardParams.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
        canvas.addView(root, cardParams);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        setContentView(scroll);

        TextView heading = text(getString(R.string.heading), 27, PURPLE);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setGravity(android.view.Gravity.CENTER);
        add(root, heading, 12);
        TextView subtitle = text(getString(R.string.subtitle), 14, MUTED);
        subtitle.setGravity(android.view.Gravity.CENTER);
        add(root, subtitle, 6);
        TextView privacy = text("●  Fully offline · Text stays on your device", 12, 0xFF278267);
        privacy.setGravity(android.view.Gravity.CENTER);
        add(root, privacy, 10);

        LinearLayout settings = column();
        settings.setPadding(dp(12), dp(12), dp(12), dp(12));
        settings.setBackground(box(0xFFF8FAFC, 0xFFF8FAFC));
        TextView languageLabel = text("Language", 12, MUTED);
        LinearLayout languageRow = row();
        languageRow.addView(languageLabel);
        language = new Spinner(this);
        language.setId(R.id.checking_language);
        language.setContentDescription("Checking language");
        language.setMinimumHeight(dp(48));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, getResources().getStringArray(R.array.checking_languages));
        language.setAdapter(adapter);
        language.setSelection(savedState == null ? 3 : savedState.getInt("dialect", 3));
        languageRow.addView(language, new LinearLayout.LayoutParams(0, -2, 1));
        settings.addView(languageRow);
        catalanOptions = column();
        varieties = new RadioGroup(this);
        varieties.setId(R.id.catalan_variety);
        varieties.setOrientation(RadioGroup.HORIZONTAL);
        int[] varietyIds = {R.id.catalan_general, R.id.catalan_valencian, R.id.catalan_balearic};
        String[] varietyNames = {"General", "Valencià", "Balear"};
        for (int i = 0; i < varietyIds.length; i++) {
            RadioButton choice = new RadioButton(this);
            choice.setId(varietyIds[i]);
            choice.setText(varietyNames[i]);
            choice.setContentDescription("Català · " + varietyNames[i]);
            choice.setButtonDrawable(null);
            choice.setTextSize(13);
            choice.setTypeface(null, Typeface.BOLD);
            choice.setGravity(android.view.Gravity.CENTER);
            choice.setMinHeight(dp(48));
            choice.setPadding(dp(5), dp(6), dp(5), dp(6));
            choice.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                    new int[]{Color.WHITE, INK}));
            StateListDrawable background = new StateListDrawable();
            background.addState(new int[]{android.R.attr.state_checked}, gradient(24));
            background.addState(new int[]{}, box(Color.WHITE, 0xFFE5E7EB, 24));
            choice.setBackground(background);
            RadioGroup.LayoutParams params = new RadioGroup.LayoutParams(0, -2, 1);
            if (i > 0) params.leftMargin = dp(5);
            varieties.addView(choice, params);
        }
        varieties.check(varietyIds[Math.max(0, Math.min(2, savedState == null ? 0
                : savedState.getInt("catalanVariety", 0)))]);
        add(catalanOptions, varieties, 8);
        add(settings, catalanOptions, 8);
        catalanOptions.setVisibility(language.getSelectedItemPosition() == 3 ? View.VISIBLE : View.GONE);
        add(root, settings, 18);

        editor = new EditText(this);
        editor.setId(R.id.editor);
        editor.setHint(R.string.editor_hint);
        editor.setContentDescription("Text to check");
        editor.setTextSize(17);
        editor.setTextColor(INK);
        editor.setHintTextColor(0xFF808594);
        editor.setLineSpacing(dp(5), 1f);
        editor.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setPadding(dp(16), dp(16), dp(16), dp(16));
        editor.setBackground(box(Color.WHITE, 0xFFE5E7EB));
        editor.setOnFocusChangeListener((view, focused) -> editor.setBackground(
                box(Color.WHITE, focused ? PURPLE : 0xFFE5E7EB)));
        editor.setOnClickListener(view -> showCorrectionAt(editor.getSelectionStart()));
        LinearLayout.LayoutParams editorParams = new LinearLayout.LayoutParams(-1, dp(220));
        editorParams.topMargin = dp(18);
        root.addView(editor, editorParams);
        counter = text("0 / 10,000 characters", 12, MUTED);
        counter.setGravity(android.view.Gravity.END);
        add(root, counter, 8);

        Button check = button("✓  " + getString(R.string.check));
        check.setId(R.id.check_text);
        check.setTextSize(16);
        check.setTextColor(Color.WHITE);
        check.setBackground(gradient(28));
        check.setElevation(dp(3));
        check.setOnClickListener(view -> check());
        add(root, check, 14);

        LinearLayout tools = row();
        Button more = button("More options");
        more.setId(R.id.more_options);
        tools.addView(more, new LinearLayout.LayoutParams(0, -2, 1));
        Button sample = button(getString(R.string.sample));
        sample.setId(R.id.use_example);
        sample.setBackgroundColor(Color.TRANSPARENT);
        sample.setOnClickListener(view -> editor.setText(sampleResource()));
        tools.addView(sample, new LinearLayout.LayoutParams(0, -2, 1));
        add(root, tools, 12);
        LinearLayout options = column();
        options.setId(R.id.options_panel);
        options.setVisibility(View.GONE);
        more.setOnClickListener(view -> {
            boolean show = options.getVisibility() != View.VISIBLE;
            options.setVisibility(show ? View.VISIBLE : View.GONE);
            more.setText(show ? "Fewer options" : "More options");
        });
        LinearLayout actions = row();
        Button copy = button(getString(R.string.copy));
        copy.setId(R.id.copy_text);
        copy.setOnClickListener(view -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("Checked text", editor.getText().toString()));
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        });
        actions.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        Button clear = button("Clear");
        clear.setId(R.id.clear_text);
        clear.setOnClickListener(view -> editor.setText(""));
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(0, -2, 1);
        clearParams.leftMargin = dp(8);
        actions.addView(clear, clearParams);
        options.addView(actions);
        Button about = button(getString(R.string.about));
        about.setBackgroundColor(Color.TRANSPARENT);
        about.setOnClickListener(view -> showAbout());
        add(options, about, 4);
        add(root, options, 8);

        if (Intent.ACTION_PROCESS_TEXT.equals(getIntent().getAction())
                && !getIntent().getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)) {
            Button use = button("Use corrected text");
            use.setOnClickListener(view -> {
                setResult(RESULT_OK, new Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, editor.getText().toString()));
                finish();
            });
            add(root, use, 8);
        }

        LinearLayout statusRow = row();
        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        statusRow.addView(progress, new LinearLayout.LayoutParams(dp(24), dp(24)));
        status = text(getString(R.string.ready), 13, MUTED);
        status.setId(R.id.check_status);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setPadding(dp(6), 0, 0, 0);
        statusRow.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        add(root, statusRow, 16);
        LinearLayout counts = row();
        spellingCount = text("● 0 spelling", 12, 0xFFCF3232);
        grammarCount = text("● 0 grammar", 12, 0xFF9F6500);
        styleCount = text("● 0 style", 12, 0xFF16845C);
        counts.addView(spellingCount, new LinearLayout.LayoutParams(0, -2, 1));
        counts.addView(grammarCount, new LinearLayout.LayoutParams(0, -2, 1));
        counts.addView(styleCount, new LinearLayout.LayoutParams(0, -2, 1));
        add(root, counts, 14);
        TextView results = text("Suggestions", 18, INK);
        results.setTypeface(null, Typeface.BOLD);
        add(root, results, 24);
        add(root, text("Tap a marked word or review a suggestion below.", 12, MUTED), 6);
        suggestions = column();
        suggestions.setId(R.id.suggestions);
        root.addView(suggestions);
        root.setFocusableInTouchMode(true);
        root.requestFocus();

        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { invalidate(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        language.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                catalanOptions.setVisibility(position == 3 ? View.VISIBLE : View.GONE);
                invalidate();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        varieties.setOnCheckedChangeListener((group, checkedId) -> invalidate());
        if (savedState != null) {
            editor.setText(savedState.getString("text", ""));
            editor.setSelection(Math.min(editor.length(), savedState.getInt("selection", 0)));
        } else {
            String action = getIntent().getAction();
            CharSequence incoming = Intent.ACTION_SEND.equals(action)
                    ? getIntent().getCharSequenceExtra(Intent.EXTRA_TEXT)
                    : Intent.ACTION_PROCESS_TEXT.equals(action)
                    ? getIntent().getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT) : null;
            if (incoming != null) {
                editor.setText(incoming.toString());
            }
        }
    }

    private String dialect() {
        int position = language.getSelectedItemPosition();
        if (position == 3) {
            if (varieties.getCheckedRadioButtonId() == R.id.catalan_valencian) return "ca-ES-valencia";
            if (varieties.getCheckedRadioButtonId() == R.id.catalan_balearic) return "ca-ES-balear";
        }
        return OfflineChecker.SUPPORTED_LANGUAGES.get(position);
    }

    private void invalidate() {
        revision++;
        checkedText = null;
        if (correctionPopup != null) { correctionPopup.dismiss(); correctionPopup = null; }
        updateCounts(java.util.Collections.emptyList());
        worker.getQueue().clear();
        main.removeCallbacks(delayedCheck);
        suggestions.removeAllViews();
        clearMarks();
        counter.setText(String.format(java.util.Locale.getDefault(), "%,d / 10,000 characters", editor.length()));
        progress.setVisibility(View.GONE);
        boolean empty = editor.getText().toString().trim().isEmpty();
        status.setText(editor.length() > OfflineChecker.MAX_CHARACTERS ? R.string.too_long
                : empty ? R.string.empty : R.string.ready);
        if (!empty && editor.length() <= OfflineChecker.MAX_CHARACTERS) {
            main.postDelayed(delayedCheck, 700);
        }
    }

    private void check() {
        main.removeCallbacks(delayedCheck);
        String snapshot = editor.getText().toString();
        if (snapshot.trim().isEmpty() || snapshot.length() > OfflineChecker.MAX_CHARACTERS) {
            invalidate();
            return;
        }
        final long ticket = ++revision;
        final String requestedDialect = dialect();
        progress.setVisibility(View.VISIBLE);
        status.setText(ready ? R.string.checking : R.string.initializing);
        worker.getQueue().clear();
        worker.execute(() -> {
            long start = System.nanoTime();
            try {
                List<Issue> issues = checker.check(snapshot, requestedDialect);
                long milliseconds = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                main.post(() -> {
                    if (destroyed || revision != ticket) return;
                    ready = true;
                    checkedText = snapshot;
                    checkedDialect = requestedDialect;
                    showIssues(issues, milliseconds);
                });
            } catch (Exception | LinkageError failure) {
                // Do not log submitted text or exceptions that can include a sentence.
                Log.e("LanguageToolOffline", "Local checker failed: " + failure.getClass().getName());
                main.post(() -> {
                    if (destroyed || revision != ticket) return;
                    progress.setVisibility(View.GONE);
                    status.setText(R.string.failed);
                });
            }
        });
    }

    private void showIssues(List<Issue> issues, long milliseconds) {
        displayedIssues = issues;
        lastCheckMilliseconds = milliseconds;
        clearMarks();
        suggestions.removeAllViews();
        progress.setVisibility(View.GONE);
        status.setText(issues.isEmpty() ? getString(R.string.no_issues)
                : getResources().getQuantityString(R.plurals.issue_count, issues.size(), issues.size(), milliseconds));
        for (Issue issue : issues) {
            Editable text = editor.getText();
            text.setSpan(new UnderlineSpan(), issue.from, issue.to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new BackgroundColorSpan("spelling".equals(issue.type) ? 0xFFFDE2E2
                    : "style".equals(issue.type) ? 0xFFDDF5EB : 0xFFFFF0D0),
                    issue.from, issue.to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        updateCounts(issues);
        for (Issue issue : issues.subList(0, Math.min(100, issues.size()))) {
            add(suggestions, issueCard(issue), 12);
        }
        if (issues.size() > 100) {
            add(suggestions, text("Showing the first 100 suggestions. Apply corrections and check again.", 13, INK), 12);
        }
    }

    private int sampleResource() {
        switch (dialect()) {
            case "es": return R.string.sample_spanish;
            case "ca-ES": return R.string.sample_catalan;
            case "ca-ES-valencia": return R.string.sample_valencian;
            case "ca-ES-balear": return R.string.sample_balearic;
            default: return R.string.sample_text;
        }
    }

    private void updateCounts(List<Issue> issues) {
        int spelling = 0, grammar = 0, style = 0;
        for (Issue issue : issues) {
            if ("spelling".equals(issue.type)) spelling++;
            else if ("style".equals(issue.type)) style++;
            else grammar++;
        }
        spellingCount.setText("● " + spelling + " spelling");
        grammarCount.setText("● " + grammar + " grammar");
        styleCount.setText("● " + style + " style");
    }

    private void showCorrectionAt(int position) {
        if (!isCurrent() || displayedIssues == null) return;
        for (Issue issue : displayedIssues) {
            if (position >= issue.from && position <= issue.to) {
                if (correctionPopup != null) correctionPopup.dismiss();
                correctionPopup = new AlertDialog.Builder(this).setTitle("Review suggestion")
                        .setView(issueCard(issue)).setNegativeButton("Close", null).create();
                correctionPopup.show();
                return;
            }
        }
    }

    private LinearLayout issueCard(Issue issue) {
        LinearLayout card = column();
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(box(0xFFF8FAFC, 0xFFE5E7EB));
        int color = "spelling".equals(issue.type) ? 0xFFCF3232
                : "style".equals(issue.type) ? 0xFF16845C : 0xFF9F6500;
        TextView label = text(issue.type.toUpperCase(java.util.Locale.ROOT), 11, color);
        label.setTypeface(null, Typeface.BOLD);
        card.addView(label);
        TextView message = text(Html.fromHtml(issue.message, Html.FROM_HTML_MODE_LEGACY).toString(), 14, INK);
        add(card, message, 7);
        message.setOnClickListener(view -> {
            if (!isCurrent()) return;
            editor.requestFocus();
            editor.setSelection(issue.from, issue.to);
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(editor, 0);
        });
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout choices = row();
        scroll.addView(choices);
        if (issue.replacements.isEmpty()) {
            add(card, text(getString(R.string.no_replacement), 13, MUTED), 6);
        } else {
            for (String replacement : issue.replacements.subList(0, Math.min(5, issue.replacements.size()))) {
                Button apply = button(replacement.isEmpty() ? "Delete" : replacement);
                apply.setTextColor(0xFF0284C7);
                apply.setBackground(box(0xFFF0F9FF, 0xFFBAE6FD, 8));
                apply.setOnClickListener(view -> {
                    if (!isCurrent()) return;
                    editor.getText().replace(issue.from, issue.to, replacement);
                    editor.setSelection(issue.from + replacement.length());
                });
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
                params.rightMargin = dp(6);
                choices.addView(apply, params);
            }
        }
        Button ignore = button(getString(R.string.ignored));
        ignore.setBackground(box(0xFFF1F5F9, 0xFFCBD5E1, 8));
        ignore.setTextColor(MUTED);
        ignore.setOnClickListener(view -> {
            if (!isCurrent()) return;
            if (correctionPopup != null) { correctionPopup.dismiss(); correctionPopup = null; }
            List<Issue> remaining = new ArrayList<>(displayedIssues);
            remaining.remove(issue);
            showIssues(remaining, lastCheckMilliseconds);
        });
        choices.addView(ignore);
        add(card, scroll, 10);
        return card;
    }

    private boolean isCurrent() {
        return checkedText != null && checkedText.contentEquals(editor.getText()) && dialect().equals(checkedDialect);
    }

    private void clearMarks() {
        Editable value = editor.getText();
        for (UnderlineSpan mark : value.getSpans(0, value.length(), UnderlineSpan.class)) value.removeSpan(mark);
        for (BackgroundColorSpan mark : value.getSpans(0, value.length(), BackgroundColorSpan.class)) value.removeSpan(mark);
    }

    private void showAbout() {
        new AlertDialog.Builder(this).setTitle(R.string.about).setMessage(R.string.about_text)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton("License texts", (dialog, which) -> {
                    StringBuilder licenses = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                            getAssets().open("licenses/NOTICES.txt"), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) licenses.append(line).append('\n');
                    } catch (java.io.IOException error) {
                        licenses.append("See the bundled LanguageTool resource notices and the source repository.");
                    }
                    TextView value = text(licenses.toString(), 13, INK);
                    value.setTextIsSelectable(true);
                    value.setPadding(dp(16), dp(12), dp(16), dp(12));
                    ScrollView scroll = new ScrollView(this);
                    scroll.addView(value);
                    new AlertDialog.Builder(this).setTitle("Open-source licenses").setView(scroll)
                            .setPositiveButton(android.R.string.ok, null).show();
                }).show();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("text", editor.getText().toString());
        state.putInt("dialect", language.getSelectedItemPosition());
        state.putInt("catalanVariety", varieties.getCheckedRadioButtonId() == R.id.catalan_valencian ? 1
                : varieties.getCheckedRadioButtonId() == R.id.catalan_balearic ? 2 : 0);
        state.putInt("selection", Math.max(0, editor.getSelectionStart()));
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (correctionPopup != null) correctionPopup.dismiss();
        revision++;
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }

    private int dp(int size) { return Math.round(size * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }
    private Button button(String title) {
        Button view = new Button(this);
        view.setText(title);
        view.setTextColor(PURPLE);
        view.setAllCaps(false);
        view.setMinHeight(dp(48));
        view.setMinWidth(0);
        view.setMinimumWidth(0);
        view.setTextSize(13);
        view.setTypeface(null, Typeface.BOLD);
        view.setPadding(dp(12), dp(8), dp(12), dp(8));
        view.setBackground(box(0xFFF8FAFC, 0xFFE5E7EB, 24));
        return view;
    }
    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(android.view.Gravity.CENTER_VERTICAL);
        return view;
    }
    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }
    private GradientDrawable gradient(int radius) {
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{PURPLE, 0xFF764BA2});
        shape.setCornerRadius(dp(radius));
        return shape;
    }
    private GradientDrawable box(int fill, int stroke) { return box(fill, stroke, 12); }
    private GradientDrawable box(int fill, int stroke, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        shape.setStroke(dp(1), stroke);
        return shape;
    }
    private void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        parent.addView(child, params);
    }
}
