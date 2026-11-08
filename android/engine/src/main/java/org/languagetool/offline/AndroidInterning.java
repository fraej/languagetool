/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.languagetool.offline;

import com.google.common.collect.Interner;
import java.util.HashMap;
import java.util.Map;

/** Batches repeated lookups while loading bundled data; canonical identity stays global. */
public final class AndroidInterning {
    private static final ThreadLocal<Map<String, String>> batch = new ThreadLocal<>();
    private AndroidInterning() { }

    public static void begin() { batch.set(new HashMap<>()); }
    public static void end() { batch.remove(); }

    public static String intern(String text, Interner<String> shared) {
        Map<String, String> local = batch.get();
        if (local == null) return shared.intern(text);
        String canonical = local.get(text);
        if (canonical == null) {
            canonical = shared.intern(text);
            local.put(canonical, canonical);
        }
        return canonical;
    }
}
