package org.languagetool.offline;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class AndroidSuppliersTest {
    @Test public void supportsBothSupplierInterfacesAndMemoizesOnce() {
        AtomicInteger calls = new AtomicInteger();
        AndroidSuppliers.SharedSupplier<Object> shared = AndroidSuppliers.memoize(() -> {
            calls.incrementAndGet();
            return new Object();
        });
        java.util.function.Supplier<Object> javaSupplier = shared;
        com.google.common.base.Supplier<Object> guavaSupplier = shared;
        assertSame(javaSupplier.get(), guavaSupplier.get());
        assertEquals(1, calls.get());
    }

    @Test public void supportsNullAndConstantValues() {
        assertNull(AndroidSuppliers.ofInstance(null).get());
        Object value = new Object();
        assertSame(value, AndroidSuppliers.ofInstance(value).get());
    }
}
