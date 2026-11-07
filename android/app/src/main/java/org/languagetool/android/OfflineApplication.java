/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.languagetool.android;

import android.app.Application;
import androidx.appcompat.app.AppCompatDelegate;
import android.util.Log;
import android.os.SystemClock;
import org.languagetool.offline.AndroidRuleCache;
import org.languagetool.offline.OfflineChecker;

public final class OfflineApplication extends Application {
    private LanguageSession session;
    volatile long startupMilliseconds;
    volatile long startupCacheHits;
    volatile long startupCacheMisses;
    volatile String startupLanguage;
    volatile String startupDetails;

    @Override public void onCreate() {
        super.onCreate();
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        session = new LanguageSession(getFilesDir());
        String saved = getSharedPreferences("preferences", MODE_PRIVATE).getString("language", "ca-ES");
        final String language = OfflineChecker.SUPPORTED_LANGUAGES.contains(saved) ? saved : "ca-ES";
        startupLanguage = language;
        long started = SystemClock.elapsedRealtime();
        session.worker.execute(() -> {
            long hits = AndroidRuleCache.hits(), misses = AndroidRuleCache.misses();
            try {
                session.warmUp(language);
                startupMilliseconds = SystemClock.elapsedRealtime() - started;
                startupCacheHits = AndroidRuleCache.hits() - hits;
                startupCacheMisses = AndroidRuleCache.misses() - misses;
                startupDetails = org.languagetool.offline.AndroidStartupCache.statistics();
                Log.i("LangTAndroid", "Language startup ms=" + startupMilliseconds
                        + " cache_hits=" + startupCacheHits + " cache_misses=" + startupCacheMisses);
            }
            catch (Exception | LinkageError failure) {
                Log.e("LangTAndroid", "Language startup failed: " + failure.getClass().getName());
            }
        });
    }

    LanguageSession session() { return session; }
}
