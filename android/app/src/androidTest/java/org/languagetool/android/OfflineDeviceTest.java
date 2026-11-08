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
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.espresso.Espresso;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
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
        assertTrue(AndroidPatterns.matches("\\{P}", "{P}"));
        assertFalse(AndroidPatterns.matches("\\{P}", "!"));
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
        OfflineChecker checker = sharedChecker();
        List<OfflineChecker.Issue> issues = checker.check(
                "This are a example. I have have a speling mistake.", "en-US");
        assertTrue(issues.stream().anyMatch(issue -> !issue.spelling));
        assertTrue(issues.stream().anyMatch(issue -> issue.spelling && issue.replacements.contains("spelling")));
        assertTrue(checker.check("This are the options.", "en-US").stream()
                .anyMatch(issue -> "PLURAL_VERB_AFTER_THIS".equals(issue.ruleId)));
        assertFalse(checker.check("The colour is beautiful.", "en-GB").stream().anyMatch(issue -> issue.spelling));
    }

    @Test public void unicodeOffsets() throws Exception {
        OfflineChecker checker = sharedChecker();
        String text = "Hello 😀.\n\nThis is a speling mistake.";
        OfflineChecker.Issue issue = checker.check(text, "en-US").stream()
                .filter(match -> match.spelling && match.replacements.contains("spelling"))
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals("Hello 😀.\n\nThis is a spelling mistake.", issue.apply(text, "spelling"));
    }

    @Test public void spanishAndCatalanFromInstalledApk() throws Exception {
        OfflineChecker checker = sharedChecker();
        String[][] examples = {
                {"es", "Una casa bonito. Esto es un errror.", "bonita", "Tiene una canción bonita."},
                {"ca-ES", "Una casa bonic. Això és un errror.", "bonica", "Aquesta és una pel·lícula bonica."}
        };
        for (String[] example : examples) {
            List<OfflineChecker.Issue> issues = checker.check(example[1], example[0]);
            assertTrue("Expected agreement rule in " + example[0], issues.stream().anyMatch(issue ->
                    "AGREEMENT_POSTPONED_ADJ".equals(issue.ruleId) && issue.replacements.contains(example[2])));
            assertTrue("Expected dictionary in " + example[0], issues.stream().anyMatch(issue ->
                    issue.spelling && issue.replacements.contains("error")));
            assertTrue("Accented words should be accepted", checker.check(example[3], example[0]).isEmpty());
        }
        for (String language : new String[]{"es", "ca-ES", "en-US", "es"}) {
            String text = "😀\n\n" + ("ca-ES".equals(language) ? "Això és un errror."
                    : "es".equals(language) ? "Esto es un errror." : "This is an errror.");
            OfflineChecker.Issue issue = checker.check(text, language).stream()
                    .filter(match -> match.spelling && match.replacements.contains("error"))
                    .findFirst().orElseThrow(AssertionError::new);
            assertEquals("errror", text.substring(issue.from, issue.to));
            assertEquals(text.replace("errror", "error"), issue.apply(text, "error"));
        }
    }

    @Test public void languageSelectionAndExamplesSurviveRecreation() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            for (String label : new String[]{"Español", "Català · General"}) {
                chooseLanguage(label);
                menuAction(R.string.sample);
                scenario.recreate();
                onView(withId(R.id.checking_language)).check(matches(withText(label)));
                scenario.onActivity(activity -> {
                    assertEquals(activity.getString(label.equals("Español") ? R.string.sample_spanish : R.string.sample_catalan),
                            ((EditText) activity.findViewById(R.id.editor)).getText().toString());
                    ((EditText) activity.findViewById(R.id.editor)).setText("");
                });
            }
        }
    }

    @Test public void catalanVarietiesUseDifferentVerbRules() throws Exception {
        OfflineChecker checker = sharedChecker();
        assertFalse(checker.check("Jo penso.", "ca-ES").stream()
                .anyMatch(issue -> issue.ruleId.startsWith("EXIGEIX_VERBS_")));
        assertTrue(checker.check("Jo penso.", "ca-ES-valencia").stream().anyMatch(issue ->
                issue.ruleId.startsWith("EXIGEIX_VERBS_VALENCIANS") && issue.replacements.contains("pense")));
        assertTrue(checker.check("Jo ho pense.", "ca-ES-balear").stream().anyMatch(issue ->
                issue.ruleId.startsWith("EXIGEIX_VERBS_BALEARS") && issue.replacements.contains("pens")));
        for (String language : new String[]{"ca-ES", "ca-ES-valencia", "ca-ES-balear", "ca-ES"}) {
            String text = "😀\n\nAixò és un errror.";
            OfflineChecker.Issue issue = checker.check(text, language).stream()
                    .filter(match -> match.spelling && match.replacements.contains("error"))
                    .findFirst().orElseThrow(AssertionError::new);
            assertEquals("errror", text.substring(issue.from, issue.to));
            assertEquals(text.replace("errror", "error"), issue.apply(text, "error"));
        }
    }

    @Test public void nativeLanguageDialogAndToolbarMenuWorkAfterRecreation() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            for (String label : new String[]{"Català · General", "Català · Valencià", "Català · Balear"}) {
                chooseLanguage(label);
                menuAction(R.string.sample);
                scenario.recreate();
                onView(withId(R.id.checking_language)).check(matches(withText(label)));
                scenario.onActivity(activity -> {
                    int example = label.endsWith("Valencià") ? R.string.sample_valencian
                            : label.endsWith("Balear") ? R.string.sample_balearic : R.string.sample_catalan;
                    assertEquals(activity.getString(example), ((EditText) activity.findViewById(R.id.editor))
                            .getText().toString());
                    ((EditText) activity.findViewById(R.id.editor)).setText("");
                });
            }
            scenario.onActivity(activity -> ((EditText) activity.findViewById(R.id.editor)).setText("Clear this text."));
            menuAction(R.string.clear);
            onView(withId(R.id.editor)).check(matches(withText("")));
            onView(withId(R.id.check_text)).check(matches(org.hamcrest.Matchers.not(isEnabled())));
            screenshot("editor.png");
        }
    }

    private static OfflineChecker sharedChecker() throws Exception {
        OfflineApplication app = (OfflineApplication) InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        app.session().worker.submit(() -> { }).get(120, java.util.concurrent.TimeUnit.SECONDS);
        return app.session().checker;
    }

    @Test public void activityRecreationKeepsThePreparedLanguage() throws Exception {
        OfflineApplication app = (OfflineApplication) InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        LanguageSession original = app.session();
        String language = app.getSharedPreferences("preferences", 0).getString("language", "ca-ES");
        original.worker.submit(() -> { original.warmUp(language); return null; }).get(120, java.util.concurrent.TimeUnit.SECONDS);
        long misses = org.languagetool.offline.AndroidRuleCache.misses();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.recreate();
            scenario.onActivity(activity -> assertSame(original, ((OfflineApplication) activity.getApplication()).session()));
        }
        original.worker.submit(() -> { }).get(120, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(original.checker.isReady(language));
        assertEquals(misses, org.languagetool.offline.AndroidRuleCache.misses());
    }

    @Test public void editorAppliesCorrectionsAndRejectsStaleSuggestions() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            chooseLanguage("English (US)");
            scenario.onActivity(activity -> {
                ((EditText) activity.findViewById(R.id.editor)).setText("This is a speling mistake.");
                activity.findViewById(R.id.check_text).performClick();
            });
            java.util.concurrent.atomic.AtomicBoolean available = new java.util.concurrent.atomic.AtomicBoolean();
            long deadline = android.os.SystemClock.uptimeMillis() + 180_000;
            while (!available.get() && android.os.SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity(activity -> {
                    assertFalse("Android checker reported a runtime error", activity.getString(R.string.failed)
                            .contentEquals(((TextView) activity.findViewById(R.id.check_status)).getText()));
                    available.set(activity.findViewById(R.id.show_suggestions).isEnabled());
                });
                if (!available.get()) Thread.sleep(100);
            }
            assertTrue("No corrections available", available.get());
            onView(withId(R.id.show_suggestions)).perform(click());
            onView(withId(R.id.sheet_title)).check(matches(withText(R.string.suggestions)));
            screenshot("corrections.png");
            onView(withId(R.id.close_suggestions)).perform(click());
            // Tapping the highlighted word opens its native correction sheet.
            androidx.test.espresso.action.GeneralClickAction tap = new androidx.test.espresso.action.GeneralClickAction(
                    androidx.test.espresso.action.Tap.SINGLE, view -> {
                        EditText text = (EditText) view;
                        int[] location = new int[2];
                        text.getLocationOnScreen(location);
                        android.text.Layout layout = text.getLayout();
                        int line = layout.getLineForOffset(12);
                        return new float[]{location[0] + text.getTotalPaddingLeft()
                                + layout.getPrimaryHorizontal(12) - text.getScrollX(),
                                location[1] + text.getTotalPaddingTop() - text.getScrollY()
                                + (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f};
                    }, androidx.test.espresso.action.Press.FINGER);
            onView(withId(R.id.editor)).perform(new androidx.test.espresso.ViewAction() {
                @Override public org.hamcrest.Matcher<View> getConstraints() { return tap.getConstraints(); }
                @Override public String getDescription() { return "tap marked word and confirm a single tap"; }
                @Override public void perform(androidx.test.espresso.UiController controller, View view) {
                    tap.perform(controller, view);
                    controller.loopMainThreadForAtLeast(android.view.ViewConfiguration.getDoubleTapTimeout() + 50);
                }
            });
            AtomicReference<View> oldSuggestion = new AtomicReference<>();
            onView(withText("spelling")).inRoot(isDialog()).check((view, error) -> {
                if (error != null) throw error;
                oldSuggestion.set(view);
            }).perform(click());
            scenario.onActivity(activity -> {
                EditText editor = activity.findViewById(R.id.editor);
                assertEquals("This is a spelling mistake.", editor.getText().toString());
                editor.setText("Completely different.");
                oldSuggestion.get().performClick();
                assertEquals("Completely different.", editor.getText().toString());
                editor.setText("");
            });
        }
    }

    private static void chooseLanguage(String label) {
        onView(withId(R.id.checking_language)).perform(click());
        if (label.equals("Español")) screenshot("language-picker.png");
        Espresso.onData(org.hamcrest.Matchers.equalTo(label)).inRoot(isDialog()).perform(click());
    }

    @Test public void darkThemePreservesEditorState() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                        androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> ((EditText) activity.findViewById(R.id.editor)).setText("Saved draft."));
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertEquals(android.content.res.Configuration.UI_MODE_NIGHT_YES,
                        activity.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK);
                assertEquals(android.graphics.Color.BLACK, com.google.android.material.color.MaterialColors.getColor(
                        activity.findViewById(R.id.editor_root), com.google.android.material.R.attr.colorSurface));
                assertEquals("Saved draft.", ((EditText) activity.findViewById(R.id.editor)).getText().toString());
                ((EditText) activity.findViewById(R.id.editor)).setText("");
            });
            screenshot("dark-editor.png");
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES));
        }
    }

    @Test public void copyAllIconCopiesTheEntireDocument() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            String document = "First line 😀.\nSecond line.";
            onView(withText("LangT Android")).check(matches(isDisplayed()));
            scenario.onActivity(activity -> {
                assertEquals("LangT Android", activity.getPackageManager().getApplicationLabel(activity.getApplicationInfo()));
                EditText text = activity.findViewById(R.id.editor);
                text.setText(document);
                text.setSelection(0, 5);
            });
            onView(withId(R.id.copy_text)).check(matches(isDisplayed())).perform(click());
            scenario.onActivity(activity -> {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                        activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                assertNotNull(clipboard.getPrimaryClip());
                assertEquals(document, clipboard.getPrimaryClip().getItemAt(0).getText().toString());
                clipboard.clearPrimaryClip();
                ((EditText) activity.findViewById(R.id.editor)).setText("");
            });
        }
    }

    static void screenshot(String name) {
        // Wait for the dialog/window frame to reach the compositor, including
        // after configuration changes, rather than capturing its previous frame.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        try {
            InstrumentationRegistry.getInstrumentation().getUiAutomation().waitForIdle(250, 5000);
        } catch (java.util.concurrent.TimeoutException error) {
            throw new AssertionError("UI did not settle for screenshot", error);
        }
        java.io.File folder = new java.io.File(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getExternalFilesDir(null), "screenshots");
        assertTrue(folder.isDirectory() || folder.mkdirs());
        android.graphics.Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(folder, name))) {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
        } catch (java.io.IOException error) {
            throw new AssertionError("Could not save UI screenshot", error);
        } finally {
            bitmap.recycle();
        }
    }

    private static void menuAction(int title) {
        Espresso.openActionBarOverflowOrOptionsMenu(InstrumentationRegistry.getInstrumentation().getTargetContext());
        onView(withText(title)).perform(click());
    }
}
