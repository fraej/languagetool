/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.languagetool.android;

import org.languagetool.offline.AndroidRuleCache;
import org.languagetool.offline.AndroidStartupCache;
import org.languagetool.offline.OfflineChecker;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One engine and one worker per application process, independent of activities. */
final class LanguageSession {
    final OfflineChecker checker = new OfflineChecker();
    final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), runnable -> new Thread(runnable, "LangT-engine"),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private final File cacheRoot;

    LanguageSession(File filesDirectory) {
        cacheRoot = new File(filesDirectory, "language-rules");
    }

    /** Called only by the shared worker. Cache identity includes every app update. */
    void warmUp(String language) throws IOException {
        if (checker.isReady(language)) return;
        File version = new File(cacheRoot, "v" + BuildConfig.VERSION_CODE + "-1");
        File selected = new File(version, language);
        AndroidRuleCache.configure(selected);
        checker.warmUp(language);
        AndroidStartupCache.flush();
        // Keep only the last successfully initialized profile on permanent storage.
        File[] versions = cacheRoot.listFiles();
        if (versions != null) for (File old : versions) if (!old.equals(version)) delete(old);
        File[] languages = version.listFiles();
        if (languages != null) for (File old : languages) if (!old.equals(selected)) delete(old);
        File[] files = selected.listFiles();
        if (files != null) for (File old : files) if (old.getName().endsWith(".tmp")) old.delete();
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
