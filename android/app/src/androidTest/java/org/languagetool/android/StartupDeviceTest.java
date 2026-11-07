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
        assertEquals("Device tests must use the distributed release build", 0,
                app.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE);
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
        sampleStartup("cached");
        assertEquals("ca-ES-valencia", app.startupLanguage);
        assertTrue("Language must be warm before an activity or user check", session.checker.isReady("ca-ES-valencia"));
        assertTrue("New process must read permanent rule cache", app.startupCacheHits > 0);
        assertEquals("New process must bypass bundled grammar XML", 0, app.startupCacheMisses);
        assertTrue(app.startupDetails, app.startupDetails.contains("dictionary_builds=0"));
        assertTrue(app.startupDetails, app.startupDetails.contains("pos_scans=0"));
        assertTrue(app.startupDetails, app.startupDetails.contains("chunker_builds=0"));
        session.worker.submit(() -> {
            assertTrue(session.checker.check("Jo penso.", "ca-ES-valencia").stream().anyMatch(issue ->
                    issue.ruleId.startsWith("EXIGEIX_VERBS_VALENCIANS") && issue.replacements.contains("pense")));
            assertTrue(session.checker.check("Una casa bonic. Això és un errror.", "ca-ES-valencia").stream()
                    .anyMatch(issue -> issue.spelling && issue.replacements.contains("error")));
            return null;
        }).get(120, TimeUnit.SECONDS);
        report(app, "restored-process");
    }

    @Test public void profileWithoutDataCache() throws Exception {
        OfflineApplication app = application();
        sampleStartup("baseline");
        assertEquals("ca-ES-valencia", app.startupLanguage);
        assertTrue(app.session().checker.isReady("ca-ES-valencia"));
        assertTrue(app.startupCacheHits > 0);
        assertFalse("Baseline must rebuild the supplemental dictionary", app.startupDetails.contains("dictionary_builds=0"));
        report(app, "baseline-process");
    }

    private static void sampleStartup(String phase) throws Exception {
        OfflineApplication app = application();
        java.util.concurrent.Future<?> done = app.session().worker.submit(() -> { });
        java.util.Map<String, Integer> stacks = new java.util.HashMap<>();
        Thread engine = null;
        long deadline = android.os.SystemClock.elapsedRealtime() + 120000;
        while (!done.isDone() && android.os.SystemClock.elapsedRealtime() < deadline) {
            if (engine == null) for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (thread.getName().equals("LangT-engine")) { engine = thread; break; }
            }
            if (engine != null) {
                StringBuilder stack = new StringBuilder();
                for (StackTraceElement frame : engine.getStackTrace()) {
                    if (stack.length() > 0) stack.append(';');
                    stack.append(frame.getClassName()).append('.').append(frame.getMethodName());
                }
                stacks.merge(stack.toString(), 1, Integer::sum);
            }
            Thread.sleep(10);
        }
        done.get(1, TimeUnit.SECONDS);
        File folder = new File(app.getExternalFilesDir(null), "startup-samples");
        assertTrue(folder.isDirectory() || folder.mkdirs());
        try (java.io.PrintWriter out = new java.io.PrintWriter(new File(folder, phase + ".txt"))) {
            stacks.entrySet().stream().sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(entry -> out.println(entry.getValue() + "\t" + entry.getKey()));
        }
    }

    private static OfflineApplication application() {
        return (OfflineApplication) InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
    }

    private static void report(OfflineApplication app, String phase) {
        Bundle result = new Bundle();
        result.putString("stream", "STARTUP " + phase + " ms=" + app.startupMilliseconds
                + " cache_hits=" + app.startupCacheHits + " cache_misses=" + app.startupCacheMisses
                + " " + app.startupDetails + "\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(0, result);
    }
}
