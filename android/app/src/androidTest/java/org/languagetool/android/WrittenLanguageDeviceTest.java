package org.languagetool.android;

import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.languagetool.offline.WrittenLanguageDetector;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class WrittenLanguageDeviceTest {
    private static final String ENGLISH = "This is an English sentence. I have have a speling mistake.";
    private static final String CATALAN = "Aquesta és una pel·lícula bonica.";

    @Test public void englishWithCatalanSelectedWarnsAndSwitchesWithoutChangingText() throws Exception {
        try (ActivityScenario<MainActivity> scenario = catalanEditor()) {
            enterAndCheck(scenario, ENGLISH);
            awaitMismatch(scenario);
            scenario.onActivity(activity -> {
                assertEquals("Català · General", ((TextView) activity.findViewById(R.id.checking_language)).getText().toString());
                assertEquals(ENGLISH, ((EditText) activity.findViewById(R.id.editor)).getText().toString());
                assertTrue(((TextView) activity.findViewById(R.id.check_status)).getText().toString().contains("English"));
            });
            OfflineDeviceTest.screenshot("language-mismatch.png");
            onView(withText("Use English")).perform(click());
            onView(withId(R.id.checking_language)).check(matches(withText("English (UK)")));
            scenario.onActivity(activity -> {
                EditText editor = activity.findViewById(R.id.editor);
                assertEquals(ENGLISH, editor.getText().toString());
                assertEquals(5, editor.getSelectionStart());
            });
            awaitStatus(scenario, R.string.checked);
            onView(withId(R.id.show_suggestions)).perform(click());
            onView(withText("spelling")).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check(matches(isDisplayed()));
            onView(withId(R.id.close_suggestions)).perform(click());
            assertTrue(InstrumentationRegistry.getInstrumentation().getTargetContext().getSharedPreferences("preferences", 0)
                    .edit().putString("preferred_ca", "ca-ES-valencia").commit());
            enterAndCheck(scenario, CATALAN);
            awaitMismatch(scenario);
            onView(withText("Use Català")).perform(click());
            onView(withId(R.id.checking_language)).check(matches(withText("Català · Valencià")));
            awaitStatus(scenario, R.string.no_issues);
            scenario.onActivity(activity -> assertEquals(CATALAN, ((EditText) activity.findViewById(R.id.editor)).getText().toString()));
            clear(scenario);
        }
    }

    @Test public void checkAnywayPreservesTheSelectionAcrossRecreationAndResetsForANewDocument() throws Exception {
        try (ActivityScenario<MainActivity> scenario = catalanEditor()) {
            enterAndCheck(scenario, ENGLISH);
            awaitMismatch(scenario);
            onView(withText(R.string.check_anyway)).perform(click());
            awaitStatus(scenario, R.string.checked);
            onView(withId(R.id.checking_language)).check(matches(withText("Català · General")));
            scenario.recreate();
            awaitStatus(scenario, R.string.checked);
            scenario.onActivity(activity -> {
                assertEquals(ENGLISH, ((EditText) activity.findViewById(R.id.editor)).getText().toString());
                assertEquals(activity.getString(R.string.check), ((TextView) activity.findViewById(R.id.check_text)).getText().toString());
            });
            clear(scenario);
            enterAndCheck(scenario, ENGLISH);
            awaitMismatch(scenario);
            clear(scenario);
        }
    }

    @Test public void editingTheTextInvalidatesTheOldLanguageSwitch() throws Exception {
        try (ActivityScenario<MainActivity> scenario = catalanEditor()) {
            enterAndCheck(scenario, ENGLISH);
            awaitMismatch(scenario);
            AtomicReference<View> oldAction = new AtomicReference<>();
            scenario.onActivity(activity -> {
                oldAction.set(activity.findViewById(R.id.show_suggestions));
                ((EditText) activity.findViewById(R.id.editor)).setText(CATALAN);
                oldAction.get().performClick();
                assertEquals("Català · General", ((TextView) activity.findViewById(R.id.checking_language)).getText().toString());
            });
            awaitStatus(scenario, R.string.no_issues);
            scenario.onActivity(activity -> assertEquals(CATALAN, ((EditText) activity.findViewById(R.id.editor)).getText().toString()));
            clear(scenario);
        }
    }

    @Test public void bundledDetectorWorksOnAndroidAndKeepsDialectChoices() throws Exception {
        WrittenLanguageDetector detector = new WrittenLanguageDetector();
        assertEquals("en", detector.mismatch(ENGLISH, "ca-ES-valencia"));
        assertEquals("es", detector.mismatch("Hola, esto es una prueba escrita en español.", "en-US"));
        assertEquals("ca", detector.mismatch(CATALAN, "es"));
        assertNull(detector.mismatch(CATALAN, "ca-ES-balear"));
        assertNull(detector.mismatch("The colour of this house is beautiful.", "en-GB"));
        assertNull(detector.mismatch("Bon dia", "en-US"));
    }

    private static ActivityScenario<MainActivity> catalanEditor() {
        assertTrue(InstrumentationRegistry.getInstrumentation().getTargetContext().getSharedPreferences("preferences", 0)
                .edit().putString("language", "ca-ES").putString("preferred_en", "en-GB").commit());
        return ActivityScenario.launch(MainActivity.class);
    }

    private static void enterAndCheck(ActivityScenario<MainActivity> scenario, String text) {
        scenario.onActivity(activity -> {
            EditText editor = activity.findViewById(R.id.editor);
            editor.setText(text); editor.setSelection(5);
            activity.findViewById(R.id.check_text).performClick();
        });
    }

    private static void clear(ActivityScenario<MainActivity> scenario) {
        scenario.onActivity(activity -> ((EditText) activity.findViewById(R.id.editor)).setText(""));
    }

    private static void awaitMismatch(ActivityScenario<MainActivity> scenario) throws Exception {
        await(scenario, R.id.check_text, R.string.check_anyway);
    }

    private static void awaitStatus(ActivityScenario<MainActivity> scenario, int text) throws Exception {
        await(scenario, R.id.check_status, text);
    }

    private static void await(ActivityScenario<MainActivity> scenario, int view, int text) throws Exception {
        AtomicBoolean done = new AtomicBoolean();
        long deadline = android.os.SystemClock.elapsedRealtime() + 180000;
        while (!done.get() && android.os.SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity(activity -> {
                assertFalse("Checking failed", activity.getString(R.string.failed)
                        .contentEquals(((TextView) activity.findViewById(R.id.check_status)).getText()));
                done.set(activity.getString(text).contentEquals(((TextView) activity.findViewById(view)).getText()));
            });
            if (!done.get()) Thread.sleep(50);
        }
        assertTrue("Expected language-checking state was not shown", done.get());
    }
}
