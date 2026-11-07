package org.languagetool.android;

import android.content.pm.PackageInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.core.app.ActivityScenario;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.languagetool.offline.OfflineChecker;
import org.languagetool.offline.AndroidPatterns;
import org.languagetool.offline.AndroidSuppliers;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import static org.junit.Assert.*;

/** Runs against the installed APK, using Android's actual runtime and bundled assets. */
@RunWith(AndroidJUnit4.class)
public class OfflineDeviceTest {
    @Test public void utf8SpellingSuggestions() {
        List<byte[]> words = new java.util.ArrayList<>();
        for (String word : new String[]{"café", "spelling", "spilling", "éclair"}) {
            words.add(word.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        words.sort(morfologik.fsa.builders.FSABuilder.LEXICAL_ORDERING);
        morfologik.stemming.DictionaryMetadata metadata = morfologik.stemming.DictionaryMetadata.builder()
                .separator('+').encoding(java.nio.charset.StandardCharsets.UTF_8)
                .encoder(morfologik.stemming.EncoderType.NONE).build();
        morfologik.speller.Speller speller = new morfologik.speller.Speller(new morfologik.stemming.Dictionary(
                morfologik.fsa.builders.FSABuilder.build(words), metadata));
        assertTrue(speller.findReplacements("speling").contains("spelling"));
        assertTrue(speller.findReplacements("cafe").contains("café"));
        assertTrue(speller.findReplacements("eclair").contains("éclair"));
    }

    @Test public void supplierInterfaceCompatibility() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        AndroidSuppliers.SharedSupplier<Object> shared = AndroidSuppliers.memoize(() -> {
            calls.incrementAndGet();
            return new Object();
        });
        java.util.function.Supplier<Object> javaSupplier = shared;
        com.google.common.base.Supplier<Object> guavaSupplier = shared;
        assertSame(javaSupplier.get(), guavaSupplier.get());
        assertEquals(1, calls.get());
    }

    @Test public void javaRegexCompatibility() {
        assertTrue(AndroidPatterns.matches("(?)id", "id"));
        assertTrue(AndroidPatterns.matches("\\p{IsPunctuation}", "!"));
        assertTrue(AndroidPatterns.compile("(?iU:example)", 256).matcher("EXAMPLE").matches());
        assertTrue(AndroidPatterns.matches("[(?)]", "("));
        assertTrue(AndroidPatterns.matches("\\1|word", "word"));
        assertFalse(AndroidPatterns.matches("\\1|word", "anything"));
        assertTrue(AndroidPatterns.matches("(a)\\12", "aa2"));
        assertTrue(AndroidPatterns.matches("((\\-)?[0-9]+[0-9,.]*{1,30})", "-12.345"));
        assertTrue(AndroidPatterns.matches(java.util.regex.Pattern.quote("(?U)"), "(?U)"));
    }

    @Test public void noNetworkPermission() throws Exception {
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(),
                android.content.pm.PackageManager.GET_PERMISSIONS);
        assertFalse(info.requestedPermissions != null
                && Arrays.asList(info.requestedPermissions).contains("android.permission.INTERNET"));
    }

    @Test public void grammarAndSpellingFromInstalledApk() throws Exception {
        OfflineChecker checker = new OfflineChecker();
        List<OfflineChecker.Issue> issues = checker.check(
                "This are a example. I have have a speling mistake.", "en-US");
        assertTrue(issues.stream().anyMatch(issue -> !issue.spelling));
        assertTrue(issues.stream().anyMatch(issue -> issue.spelling && issue.replacements.contains("spelling")));
        assertTrue(checker.check("This are the options.", "en-US").stream()
                .anyMatch(issue -> "PLURAL_VERB_AFTER_THIS".equals(issue.ruleId)));
        assertFalse(checker.check("The colour is beautiful.", "en-GB").stream().anyMatch(issue -> issue.spelling));
    }

    @Test public void unicodeOffsets() throws Exception {
        OfflineChecker checker = new OfflineChecker();
        String text = "Hello 😀.\n\nThis is a speling mistake.";
        OfflineChecker.Issue issue = checker.check(text, "en-US").stream()
                .filter(match -> match.spelling && match.replacements.contains("spelling"))
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals("Hello 😀.\n\nThis is a spelling mistake.", issue.apply(text, "spelling"));
    }

    @Test public void editorAppliesCorrectionsAndRejectsStaleSuggestions() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                ((EditText) activity.findViewById(R.id.editor)).setText("This is a speling mistake.");
                activity.findViewById(R.id.check_text).performClick();
            });
            AtomicReference<Button> suggestion = new AtomicReference<>();
            long deadline = android.os.SystemClock.uptimeMillis() + 180_000;
            while (suggestion.get() == null && android.os.SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity(activity -> {
                    assertFalse("Android checker reported a runtime error", activity.getString(R.string.failed)
                            .contentEquals(((TextView) activity.findViewById(R.id.check_status)).getText()));
                    suggestion.set(findButton(activity.findViewById(R.id.suggestions), "spelling"));
                });
                if (suggestion.get() == null) Thread.sleep(100);
            }
            assertNotNull("No spelling correction appeared in the editor", suggestion.get());
            scenario.onActivity(activity -> {
                suggestion.get().performClick();
                EditText editor = activity.findViewById(R.id.editor);
                assertEquals("This is a spelling mistake.", editor.getText().toString());
                editor.setText("Completely different.");
                suggestion.get().performClick();
                assertEquals("Completely different.", editor.getText().toString());
            });
        }
    }

    private static Button findButton(View view, String label) {
        if (view instanceof Button && label.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = findButton(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }
}
