package org.languagetool.android;

import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** CI runs these methods in separate processes with an intervening force-stop. */
@RunWith(AndroidJUnit4.class)
public class StartupDeviceTest {
    @Test public void saveLastLanguage() throws Exception {
        OfflineApplication app = application();
        LanguageSession session = app.session();
        assertTrue(app.getSharedPreferences("preferences", 0).edit().putString("language", "ca-ES-valencia").commit());
        session.worker.submit(() -> { session.warmUp("ca-ES-valencia"); return null; }).get(120, TimeUnit.SECONDS);
        assertTrue(session.checker.isReady("ca-ES-valencia"));
        File version = new File(app.getFilesDir(), "language-rules/v" + BuildConfig.VERSION_CODE + "-1");
        assertArrayEquals(new String[]{"ca-ES-valencia"}, version.list());
        File[] rules = new File(version, "ca-ES-valencia").listFiles((dir, name) -> name.endsWith(".rules"));
        assertNotNull(rules);
        assertTrue(rules.length > 0);
        report(app, "first-process");
    }

    @Test public void restoreLastLanguageAfterProcessDeath() throws Exception {
        OfflineApplication app = application();
        LanguageSession session = app.session();
        session.worker.submit(() -> { }).get(120, TimeUnit.SECONDS);
        assertEquals("ca-ES-valencia", app.startupLanguage);
        assertTrue("Language must be warm before an activity or user check", session.checker.isReady("ca-ES-valencia"));
        assertTrue("New process must read permanent rule cache", app.startupCacheHits > 0);
        assertEquals("New process must bypass bundled grammar XML", 0, app.startupCacheMisses);
        session.worker.submit(() -> {
            assertTrue(session.checker.check("Jo penso.", "ca-ES-valencia").stream().anyMatch(issue ->
                    issue.ruleId.startsWith("EXIGEIX_VERBS_VALENCIANS") && issue.replacements.contains("pense")));
            assertTrue(session.checker.check("Una casa bonic. Això és un errror.", "ca-ES-valencia").stream()
                    .anyMatch(issue -> issue.spelling && issue.replacements.contains("error")));
            return null;
        }).get(120, TimeUnit.SECONDS);
        report(app, "restored-process");
    }

    private static OfflineApplication application() {
        return (OfflineApplication) InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
    }

    private static void report(OfflineApplication app, String phase) {
        Bundle result = new Bundle();
        result.putString("stream", "STARTUP " + phase + " ms=" + app.startupMilliseconds
                + " cache_hits=" + app.startupCacheHits + " cache_misses=" + app.startupCacheMisses + "\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(0, result);
    }
}
