/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.languagetool.android;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
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
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ArrayAdapter;
import android.widget.CheckedTextView;
import android.view.ViewGroup;
import android.graphics.drawable.Drawable;
import androidx.core.content.ContextCompat;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import org.languagetool.offline.OfflineChecker;
import org.languagetool.offline.OfflineChecker.Issue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Material editor. LanguageTool checks run on one background worker. */
public final class MainActivity extends AppCompatActivity {
    private static final int[] LANGUAGE_FLAGS = {R.drawable.flag_us, R.drawable.flag_gb, R.drawable.flag_es,
            R.drawable.flag_ca, R.drawable.flag_valencia, R.drawable.flag_balear};
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.DiscardOldestPolicy());
    private final OfflineChecker checker = new OfflineChecker();
    private final Runnable delayedCheck = this::check;
    private EditText editor;
    private TextView status, counter;
    private Chip language;
    private MaterialButton results;
    private View progress, checkButton;
    private BottomSheetDialog correctionSheet;
    private AlertDialog auxiliaryDialog;
    private String selectedLanguage, checkedText, checkedLanguage;
    private List<Issue> displayedIssues = Collections.emptyList();
    private long revision;
    private boolean destroyed, ready;

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_main);
        View root = findViewById(R.id.editor_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        editor = findViewById(R.id.editor);
        status = findViewById(R.id.check_status);
        counter = findViewById(R.id.char_count);
        progress = findViewById(R.id.check_progress);
        checkButton = findViewById(R.id.check_text);
        language = findViewById(R.id.checking_language);
        results = findViewById(R.id.show_suggestions);
        selectedLanguage = savedState == null
                ? getSharedPreferences("preferences", MODE_PRIVATE).getString("language", "ca-ES")
                : savedState.getString("language", "ca-ES");
        if (!OfflineChecker.SUPPORTED_LANGUAGES.contains(selectedLanguage)) selectedLanguage = "ca-ES";
        updateLanguageLabel();
        language.setOnClickListener(view -> chooseLanguage());
        results.setOnClickListener(view -> openCorrections(null));
        checkButton.setOnClickListener(view -> check());
        ((CheckingEditText) editor).setOnWordTapListener(this::showCorrectionAt);
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                // IMEs can resend the same text when focus/composition changes.
                if (checkedText == null || !checkedText.contentEquals(s)) invalidate();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        View use = findViewById(R.id.use_corrected_text);
        if (Intent.ACTION_PROCESS_TEXT.equals(getIntent().getAction())
                && !getIntent().getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)) {
            use.setVisibility(View.VISIBLE);
            use.setOnClickListener(view -> {
                setResult(RESULT_OK, new Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, editor.getText().toString()));
                finish();
            });
        }
        if (savedState != null) {
            editor.setText(savedState.getString("text", ""));
            editor.setSelection(Math.min(editor.length(), savedState.getInt("selection", 0)));
        } else {
            String action = getIntent().getAction();
            CharSequence incoming = Intent.ACTION_SEND.equals(action)
                    ? getIntent().getCharSequenceExtra(Intent.EXTRA_TEXT)
                    : Intent.ACTION_PROCESS_TEXT.equals(action)
                    ? getIntent().getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT) : null;
            if (incoming != null) editor.setText(incoming.toString());
        }
        if (editor.length() == 0) invalidate();
        root.requestFocus();
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.editor_actions, menu);
        return true;
    }

    @Override public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem copy = menu.findItem(R.id.copy_text);
        copy.setEnabled(editor.length() > 0);
        if (copy.getIcon() != null) copy.getIcon().mutate().setAlpha(editor.length() > 0 ? 255 : 100);
        menu.findItem(R.id.clear_text).setEnabled(editor.length() > 0);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.copy_text) {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), editor.getText().toString()));
        } else if (id == R.id.paste_text) {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData clip = clipboard.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence text = clip.getItemAt(0).coerceToText(this);
                if (text != null) {
                    int start = Math.max(0, editor.getSelectionStart());
                    int end = Math.max(start, editor.getSelectionEnd());
                    editor.getText().replace(start, end, text.toString());
                    editor.setSelection(start + text.length());
                }
            }
        } else if (id == R.id.clear_text) editor.setText("");
        else if (id == R.id.use_example) editor.setText(sampleResource());
        else if (id == R.id.about_app) showAbout();
        else return super.onOptionsItemSelected(item);
        return true;
    }

    private void chooseLanguage() {
        hideKeyboard();
        if (auxiliaryDialog != null) auxiliaryDialog.dismiss();
        String[] labels = getResources().getStringArray(R.array.checking_languages);
        ArrayAdapter<String> choices = new ArrayAdapter<String>(this, R.layout.item_language, android.R.id.text1, labels) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                CheckedTextView row = (CheckedTextView) super.getView(position, recycled, parent);
                Drawable flag = ContextCompat.getDrawable(MainActivity.this, LANGUAGE_FLAGS[position]);
                int size = (int) (24 * getResources().getDisplayMetrics().density);
                flag.setBounds(0, 0, size, size);
                row.setCompoundDrawablesRelative(flag, null, null, null);
                return row;
            }
        };
        auxiliaryDialog = new MaterialAlertDialogBuilder(this).setTitle(R.string.choose_language)
                .setSingleChoiceItems(choices, OfflineChecker.SUPPORTED_LANGUAGES.indexOf(selectedLanguage),
                        (dialog, which) -> {
                            String requested = OfflineChecker.SUPPORTED_LANGUAGES.get(which);
                            if (!requested.equals(selectedLanguage)) {
                                selectedLanguage = requested;
                                getSharedPreferences("preferences", MODE_PRIVATE).edit()
                                        .putString("language", selectedLanguage).apply();
                                updateLanguageLabel();
                                invalidate();
                            }
                            dialog.dismiss();
                        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void updateLanguageLabel() {
        int index = OfflineChecker.SUPPORTED_LANGUAGES.indexOf(selectedLanguage);
        String label = getResources().getStringArray(R.array.checking_languages)[index];
        language.setText(label);
        language.setChipIconResource(LANGUAGE_FLAGS[index]);
        language.setChipIconTint(null);
        language.setContentDescription(getString(R.string.language_description, label));
    }

    private void invalidate() {
        revision++;
        checkedText = null;
        displayedIssues = Collections.emptyList();
        dismissCorrections();
        results.setEnabled(false);
        results.setText(R.string.suggestions);
        worker.getQueue().clear();
        main.removeCallbacks(delayedCheck);
        clearMarks();
        counter.setText(getString(R.string.character_count, editor.length()));
        progress.setVisibility(View.INVISIBLE);
        boolean empty = editor.getText().toString().trim().isEmpty();
        boolean valid = !empty && editor.length() <= OfflineChecker.MAX_CHARACTERS;
        checkButton.setEnabled(valid);
        status.setText(editor.length() > OfflineChecker.MAX_CHARACTERS ? R.string.too_long
                : empty ? R.string.empty : R.string.ready);
        invalidateOptionsMenu();
        if (valid) main.postDelayed(delayedCheck, 700);
    }

    private void check() {
        main.removeCallbacks(delayedCheck);
        String snapshot = editor.getText().toString();
        if (snapshot.trim().isEmpty() || snapshot.length() > OfflineChecker.MAX_CHARACTERS) {
            invalidate();
            return;
        }
        final long ticket = ++revision;
        final String requested = selectedLanguage;
        progress.setVisibility(View.VISIBLE);
        status.setText(ready ? R.string.checking : R.string.initializing);
        worker.getQueue().clear();
        worker.execute(() -> {
            try {
                List<Issue> issues = checker.check(snapshot, requested);
                main.post(() -> {
                    if (destroyed || revision != ticket) return;
                    ready = true;
                    checkedText = snapshot;
                    checkedLanguage = requested;
                    showIssues(issues);
                });
            } catch (Exception | LinkageError failure) {
                // Exceptions may contain submitted text; log only their type.
                Log.e("LangTAndroid", "Local checker failed: " + failure.getClass().getName());
                main.post(() -> {
                    if (destroyed || revision != ticket) return;
                    progress.setVisibility(View.INVISIBLE);
                    status.setText(R.string.failed);
                });
            }
        });
    }

    private void showIssues(List<Issue> issues) {
        displayedIssues = issues;
        clearMarks();
        progress.setVisibility(View.INVISIBLE);
        status.setText(issues.isEmpty() ? R.string.no_issues : R.string.checked);
        results.setEnabled(!issues.isEmpty());
        results.setText(issues.isEmpty() ? getString(R.string.suggestions)
                : getResources().getQuantityString(R.plurals.issue_count, issues.size(), issues.size()));
        for (Issue issue : issues) {
            int color = "spelling".equals(issue.type) ? com.google.android.material.R.attr.colorErrorContainer
                    : "style".equals(issue.type) ? com.google.android.material.R.attr.colorPrimaryContainer
                    : com.google.android.material.R.attr.colorTertiaryContainer;
            Editable text = editor.getText();
            text.setSpan(new UnderlineSpan(), issue.from, issue.to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new BackgroundColorSpan(MaterialColors.getColor(editor, color)),
                    issue.from, issue.to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private void showCorrectionAt(int position) {
        if (!isCurrent()) return;
        for (Issue issue : displayedIssues) {
            if (position >= issue.from && position <= issue.to) { openCorrections(issue); return; }
        }
    }

    private void openCorrections(Issue focus) {
        if (!isCurrent() || displayedIssues.isEmpty()) return;
        hideKeyboard();
        dismissCorrections();
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        sheet.setContentView(R.layout.sheet_suggestions);
        sheet.getBehavior().setMaxHeight((int) (getResources().getDisplayMetrics().heightPixels * 0.85f));
        sheet.findViewById(R.id.close_suggestions).setOnClickListener(view -> sheet.dismiss());
        LinearLayout cards = sheet.findViewById(R.id.suggestions);
        int spelling = 0, grammar = 0, style = 0;
        for (Issue issue : displayedIssues) {
            if ("spelling".equals(issue.type)) spelling++;
            else if ("style".equals(issue.type)) style++;
            else grammar++;
        }
        ((TextView) sheet.findViewById(R.id.issue_summary)).setText(
                getString(R.string.issue_summary, spelling, grammar, style));
        List<Issue> visible = focus == null ? displayedIssues : Collections.singletonList(focus);
        for (Issue issue : visible.subList(0, Math.min(100, visible.size()))) cards.addView(issueCard(cards, issue));
        if (visible.size() > 100) {
            TextView limit = new TextView(this);
            limit.setText(R.string.suggestion_limit);
            cards.addView(limit);
        }
        correctionSheet = sheet;
        sheet.setOnDismissListener(dialog -> { if (correctionSheet == sheet) correctionSheet = null; });
        sheet.show();
        sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
    }

    private View issueCard(LinearLayout parent, Issue issue) {
        View card = getLayoutInflater().inflate(R.layout.item_suggestion, parent, false);
        TextView type = card.findViewById(R.id.issue_type);
        type.setText("spelling".equals(issue.type) ? R.string.spelling
                : "style".equals(issue.type) ? R.string.style : R.string.grammar);
        type.setTextColor(MaterialColors.getColor(card, "spelling".equals(issue.type)
                ? com.google.android.material.R.attr.colorError : com.google.android.material.R.attr.colorPrimary));
        TextView word = card.findViewById(R.id.issue_word);
        word.setText(issue.from == issue.to ? getString(R.string.insert_here) : checkedText.substring(issue.from, issue.to));
        word.setOnClickListener(view -> {
            if (!isCurrent()) return;
            dismissCorrections();
            editor.requestFocus();
            editor.setSelection(issue.from, issue.to);
            androidx.core.view.WindowInsetsControllerCompat controller = ViewCompat.getWindowInsetsController(editor);
            if (controller != null) controller.show(WindowInsetsCompat.Type.ime());
        });
        ((TextView) card.findViewById(R.id.issue_message)).setText(
                Html.fromHtml(issue.message, Html.FROM_HTML_MODE_LEGACY).toString());
        ChipGroup replacements = card.findViewById(R.id.replacements);
        for (String replacement : issue.replacements.subList(0, Math.min(5, issue.replacements.size()))) {
            Chip apply = (Chip) getLayoutInflater().inflate(R.layout.item_replacement, replacements, false);
            apply.setText(replacement.isEmpty() ? getString(R.string.delete_text) : replacement);
            apply.setCheckable(false);
            apply.setEnsureMinTouchTargetSize(true);
            apply.setOnClickListener(view -> {
                if (!isCurrent()) return;
                editor.getText().replace(issue.from, issue.to, replacement);
                editor.setSelection(issue.from + replacement.length());
            });
            replacements.addView(apply);
        }
        card.findViewById(R.id.ignore_suggestion).setOnClickListener(view -> {
            if (!isCurrent()) return;
            List<Issue> remaining = new ArrayList<>(displayedIssues);
            remaining.remove(issue);
            dismissCorrections();
            showIssues(remaining);
            if (!remaining.isEmpty()) openCorrections(null);
        });
        return card;
    }

    private boolean isCurrent() {
        return !destroyed && checkedText != null && checkedText.contentEquals(editor.getText())
                && selectedLanguage.equals(checkedLanguage);
    }

    private void clearMarks() {
        Editable value = editor.getText();
        for (UnderlineSpan span : value.getSpans(0, value.length(), UnderlineSpan.class)) value.removeSpan(span);
        for (BackgroundColorSpan span : value.getSpans(0, value.length(), BackgroundColorSpan.class)) value.removeSpan(span);
    }

    private void dismissCorrections() {
        if (correctionSheet != null) { correctionSheet.dismiss(); correctionSheet = null; }
    }

    private void hideKeyboard() {
        androidx.core.view.WindowInsetsControllerCompat controller = ViewCompat.getWindowInsetsController(editor);
        if (controller != null) controller.hide(WindowInsetsCompat.Type.ime());
    }

    private int sampleResource() {
        switch (selectedLanguage) {
            case "es": return R.string.sample_spanish;
            case "ca-ES": return R.string.sample_catalan;
            case "ca-ES-valencia": return R.string.sample_valencian;
            case "ca-ES-balear": return R.string.sample_balearic;
            default: return R.string.sample_text;
        }
    }

    private void showAbout() {
        auxiliaryDialog = new MaterialAlertDialogBuilder(this).setTitle(R.string.about).setMessage(R.string.about_text)
                .setPositiveButton(android.R.string.ok, null).setNeutralButton(R.string.license_texts,
                        (dialog, which) -> showLicenses()).show();
    }

    private void showLicenses() {
        StringBuilder licenses = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getAssets().open("licenses/NOTICES.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) licenses.append(line).append('\n');
        } catch (java.io.IOException error) {
            licenses.append(getString(R.string.license_fallback));
        }
        TextView text = new TextView(this);
        text.setText(licenses);
        text.setTextIsSelectable(true);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        text.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        auxiliaryDialog = new MaterialAlertDialogBuilder(this).setTitle(R.string.license_texts).setView(scroll)
                .setPositiveButton(android.R.string.ok, null).show();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("text", editor.getText().toString());
        state.putString("language", selectedLanguage);
        state.putInt("selection", Math.max(0, editor.getSelectionStart()));
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        dismissCorrections();
        if (auxiliaryDialog != null) auxiliaryDialog.dismiss();
        revision++;
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }
}
