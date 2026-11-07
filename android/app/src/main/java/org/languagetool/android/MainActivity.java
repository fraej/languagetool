/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.languagetool.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
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
    private static final int GREEN = 0xFF176B56;
    private static final int INK = 0xFF1D302A;
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(18), dp(22), dp(24));
        scroll.addView(root);
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

        TextView eyebrow = text("LANGUAGETOOL / OFFLINE", 12, GREEN);
        eyebrow.setLetterSpacing(0.13f);
        eyebrow.setTypeface(null, Typeface.BOLD);
        root.addView(eyebrow);
        TextView heading = text("Make every word count.", 30, INK);
        heading.setTypeface(null, Typeface.BOLD);
        add(root, heading, 12);
        add(root, text(getString(R.string.privacy), 13, 0xFF52675C), 8);

        language = new Spinner(this);
        language.setContentDescription("English variant");
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, new String[]{"English (US)", "English (UK)"});
        language.setAdapter(adapter);
        language.setSelection(savedState == null ? 0 : savedState.getInt("dialect", 0));
        add(root, language, 18);

        editor = new EditText(this);
        editor.setId(R.id.editor);
        editor.setHint(R.string.editor_hint);
        editor.setContentDescription("Text to check");
        editor.setTextSize(18);
        editor.setTextColor(INK);
        editor.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setPadding(dp(16), dp(16), dp(16), dp(16));
        editor.setBackground(box(Color.WHITE, 0xFFD6DED7));
        LinearLayout.LayoutParams editorParams = new LinearLayout.LayoutParams(-1, dp(240));
        editorParams.topMargin = dp(12);
        root.addView(editor, editorParams);
        counter = text("0 / 10,000 characters", 12, 0xFF52675C);
        add(root, counter, 8);

        LinearLayout actions = row();
        Button check = button(getString(R.string.check));
        check.setId(R.id.check_text);
        check.setTextColor(Color.WHITE);
        check.setBackgroundTintList(android.content.res.ColorStateList.valueOf(GREEN));
        check.setOnClickListener(view -> check());
        actions.addView(check, new LinearLayout.LayoutParams(0, -2, 1));
        Button copy = button(getString(R.string.copy));
        copy.setOnClickListener(view -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("Checked text", editor.getText().toString()));
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        });
        actions.addView(copy);
        Button clear = button("Clear");
        clear.setOnClickListener(view -> editor.setText(""));
        actions.addView(clear);
        add(root, actions, 12);

        if (Intent.ACTION_PROCESS_TEXT.equals(getIntent().getAction())
                && !getIntent().getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)) {
            Button use = button("Use corrected text");
            use.setOnClickListener(view -> {
                setResult(RESULT_OK, new Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, editor.getText().toString()));
                finish();
            });
            root.addView(use);
        }

        Button sample = button(getString(R.string.sample));
        sample.setOnClickListener(view -> editor.setText(R.string.sample_text));
        root.addView(sample);
        LinearLayout statusRow = row();
        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        statusRow.addView(progress, new LinearLayout.LayoutParams(dp(24), dp(24)));
        status = text(getString(R.string.ready), 14, GREEN);
        status.setId(R.id.check_status);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setPadding(dp(8), 0, 0, 0);
        statusRow.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        add(root, statusRow, 18);
        suggestions = new LinearLayout(this);
        suggestions.setId(R.id.suggestions);
        suggestions.setOrientation(LinearLayout.VERTICAL);
        root.addView(suggestions);

        Button about = button(getString(R.string.about));
        about.setOnClickListener(view -> showAbout());
        add(root, about, 24);

        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { invalidate(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        language.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { invalidate(); }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
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
        return language.getSelectedItemPosition() == 1 ? "en-GB" : "en-US";
    }

    private void invalidate() {
        revision++;
        checkedText = null;
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
            text.setSpan(new BackgroundColorSpan(issue.spelling ? 0xFFFFE0DD : 0xFFFFEDBC),
                    issue.from, issue.to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        for (Issue issue : issues.subList(0, Math.min(100, issues.size()))) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(12), dp(14), dp(12));
            card.setBackground(box(Color.WHITE, 0xFFD6DED7));
            TextView label = text(issue.spelling ? "SPELLING" : "GRAMMAR & STYLE", 11, GREEN);
            label.setTypeface(null, Typeface.BOLD);
            card.addView(label);
            TextView message = text(Html.fromHtml(issue.message, Html.FROM_HTML_MODE_LEGACY).toString(), 15, INK);
            add(card, message, 7);
            message.setOnClickListener(view -> {
                if (!isCurrent()) return;
                editor.requestFocus();
                editor.setSelection(issue.from, issue.to);
                ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(editor, 0);
            });
            if (issue.replacements.isEmpty()) {
                add(card, text(getString(R.string.no_replacement), 13, 0xFF52675C), 6);
            } else {
                for (String replacement : issue.replacements.subList(0, Math.min(5, issue.replacements.size()))) {
                    Button apply = button(replacement.isEmpty() ? "Delete" : replacement);
                    apply.setOnClickListener(view -> {
                        if (!isCurrent()) return;
                        editor.getText().replace(issue.from, issue.to, replacement);
                        editor.setSelection(issue.from + replacement.length());
                    });
                    card.addView(apply);
                }
            }
            Button ignore = button(getString(R.string.ignored));
            ignore.setOnClickListener(view -> {
                if (!isCurrent()) return;
                List<Issue> remaining = new ArrayList<>(displayedIssues);
                remaining.remove(issue);
                showIssues(remaining, lastCheckMilliseconds);
                // Ignoring is local to these results; the next check evaluates all rules.
            });
            card.addView(ignore);
            add(suggestions, card, 12);
        }
        if (issues.size() > 100) {
            add(suggestions, text("Showing the first 100 suggestions. Apply corrections and check again.", 13, INK), 12);
        }
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
        state.putInt("selection", Math.max(0, editor.getSelectionStart()));
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        destroyed = true;
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
        view.setTextColor(GREEN);
        view.setAllCaps(false);
        view.setMinHeight(dp(48));
        return view;
    }
    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(android.view.Gravity.CENTER_VERTICAL);
        return view;
    }
    private GradientDrawable box(int fill, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(12));
        shape.setStroke(dp(1), stroke);
        return shape;
    }
    private void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        parent.addView(child, params);
    }
}
