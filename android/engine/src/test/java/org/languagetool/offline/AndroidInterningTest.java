package org.languagetool.offline;

import com.google.common.collect.Interner;
import com.google.common.collect.Interners;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class AndroidInterningTest {
    @Test public void batchesDuplicateLookupsWithoutChangingExistingCanonicalIdentity() {
        Interner<String> shared = Interners.newWeakInterner();
        String original = shared.intern(new String("bundled word"));
        AtomicInteger calls = new AtomicInteger();
        Interner<String> counted = text -> { calls.incrementAndGet(); return shared.intern(text); };
        AndroidInterning.begin();
        try {
            for (int i = 0; i < 100; i++) assertSame(original, AndroidInterning.intern(new String("bundled word"), counted));
            assertEquals(1, calls.get());
        } finally { AndroidInterning.end(); }
        assertSame(original, AndroidInterning.intern(new String("bundled word"), counted));
        assertEquals(2, calls.get());
    }

    @Test public void failedInitializationReleasesTheTemporaryInterner() {
        assertThrows(IllegalArgumentException.class, () -> new OfflineChecker().warmUp("unsupported"));
        AtomicInteger calls = new AtomicInteger();
        Interner<String> counted = text -> { calls.incrementAndGet(); return text; };
        for (int i = 0; i < 3; i++) AndroidInterning.intern("word", counted);
        assertEquals(3, calls.get());
    }
}
