/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.languagetool.offline;

/** Bridges Guava's Android suppliers and Java suppliers without multi-release JAR assumptions. */
public final class AndroidSuppliers {
    private AndroidSuppliers() { }

    public interface SharedSupplier<T> extends java.util.function.Supplier<T>, com.google.common.base.Supplier<T> { }

    public static <T> SharedSupplier<T> ofInstance(T value) {
        return () -> value;
    }

    public static <T> SharedSupplier<T> memoize(com.google.common.base.Supplier<T> source) {
        com.google.common.base.Supplier<T> memoized = com.google.common.base.Suppliers.memoize(source);
        return memoized::get;
    }
}
