package org.languagetool.android;

import android.os.Bundle;
import androidx.test.runner.AndroidJUnitRunner;
import org.languagetool.offline.AndroidStartupCache;

/** Runs the same APK with computed-data caches enabled or disabled for comparison. */
public final class LangTTestRunner extends AndroidJUnitRunner {
    @Override public void onCreate(Bundle arguments) {
        AndroidStartupCache.setEnabled(!"true".equals(arguments.getString("disableStartupDataCache")));
        super.onCreate(arguments);
    }
}
