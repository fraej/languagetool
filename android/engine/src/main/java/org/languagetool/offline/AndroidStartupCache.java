/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.languagetool.offline;

import morfologik.stemming.Dictionary;
import org.languagetool.AnalyzedToken;
import org.languagetool.JLanguageTool;

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

/** Typed caches of expensive, deterministic computations on bundled language data. */
public final class AndroidStartupCache {
    private static volatile boolean enabled = true;
    private static Map<String, Set<String>> posValues;
    private static final Map<Set<String>, String> universes = new IdentityHashMap<>();
    private static boolean dirty;
    private static long dictionaryHits, dictionaryBuilds, posHits, posScans, chunkerHits, chunkerBuilds;

    private AndroidStartupCache() { }

    /** Used by benchmarks to compare the same APK with these optimizations disabled. */
    public static synchronized void setEnabled(boolean value) { enabled = value; configure(); }

    static synchronized void configure() { posValues = null; universes.clear(); dirty = false; }

    public static synchronized String statistics() {
        return "dictionary_hits=" + dictionaryHits + " dictionary_builds=" + dictionaryBuilds
                + " pos_hits=" + posHits + " pos_scans=" + posScans
                + " chunker_hits=" + chunkerHits + " chunker_builds=" + chunkerBuilds;
    }

    public static Dictionary dictionary(String key, String infoPath) {
        if (!enabled || !AndroidRuleCache.enabled() || new File(infoPath).exists()) return null;
        byte[] data = AndroidRuleCache.readData("dictionary:" + key);
        if (data == null) return null;
        try (InputStream metadata = JLanguageTool.getDataBroker().getFromResourceDirAsStream(infoPath)) {
            Dictionary dictionary = Dictionary.read(new ByteArrayInputStream(data), metadata);
            dictionaryHits++;
            return dictionary;
        } catch (IOException | RuntimeException invalid) { return null; }
    }

    public static void saveDictionary(String key, String infoPath, byte[] data) {
        dictionaryBuilds++;
        if (enabled && !new File(infoPath).exists()) AndroidRuleCache.writeData("dictionary:" + key, data);
    }

    public static synchronized Set<String> postags(String pattern, boolean caseSensitive, Set<String> universe,
                                                  Supplier<Set<String>> compute) {
        if (!enabled || !AndroidRuleCache.enabled()) { posScans++; return compute.get(); }
        if (posValues == null) posValues = readPostags();
        String context = universes.computeIfAbsent(universe, AndroidStartupCache::fingerprint);
        String key = context + ":" + caseSensitive + ":" + pattern;
        if (posValues.containsKey(key)) { posHits++; return posValues.get(key); }
        posScans++;
        Set<String> result = compute.get();
        posValues.put(key, result == null ? null : Collections.unmodifiableSet(new HashSet<>(result)));
        dirty = true;
        return result;
    }

    public static synchronized void flush() {
        if (!enabled || !dirty || posValues == null) return;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(posValues.size());
                for (Map.Entry<String, Set<String>> entry : posValues.entrySet()) {
                    out.writeUTF(entry.getKey());
                    Set<String> values = entry.getValue();
                    out.writeInt(values == null ? -1 : values.size());
                    if (values != null) for (String value : values) out.writeUTF(value);
                }
            }
            AndroidRuleCache.writeData("postags-v1", bytes.toByteArray());
            dirty = false;
        } catch (IOException unavailableStorage) { /* Recompute on the next start. */ }
    }

    private static Map<String, Set<String>> readPostags() {
        Map<String, Set<String>> result = new HashMap<>();
        byte[] data = AndroidRuleCache.readData("postags-v1");
        if (data == null) return result;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int count = count(in, 200000);
            for (int i = 0; i < count; i++) {
                String key = in.readUTF();
                int size = in.readInt();
                if (size < -1 || size > 100000) throw new IOException("POS cache size");
                Set<String> values = size == -1 ? null : new HashSet<>();
                for (int j = 0; j < size; j++) values.add(in.readUTF());
                result.put(key, values == null ? null : Collections.unmodifiableSet(values));
            }
            if (in.read() != -1) throw new IOException("Trailing POS cache data");
            return result;
        } catch (IOException | RuntimeException invalid) { return new HashMap<>(); }
    }

    private static String fingerprint(Set<String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<String> sorted = new ArrayList<>(values); Collections.sort(sorted);
            for (String value : sorted) {
                digest.update(value.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
            }
            return Base64.getEncoder().encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    public static boolean chunker(String key, Map<String, Integer> startSpace, Map<String, Integer> startNoSpace,
                                  Map<String, AnalyzedToken> fullSpace, Map<String, AnalyzedToken> fullNoSpace) {
        if (!enabled) return false;
        byte[] data = AndroidRuleCache.readData("chunker:" + key);
        if (data == null) return false;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            readStarts(in, startSpace); readStarts(in, startNoSpace);
            readTokens(in, fullSpace); readTokens(in, fullNoSpace);
            if (in.read() != -1) throw new IOException("Trailing chunker cache data");
            chunkerHits++;
            return true;
        } catch (IOException | RuntimeException invalid) {
            startSpace.clear(); startNoSpace.clear(); fullSpace.clear(); fullNoSpace.clear();
            return false;
        }
    }

    public static void saveChunker(String key, Map<String, Integer> startSpace, Map<String, Integer> startNoSpace,
                                   Map<String, AnalyzedToken> fullSpace, Map<String, AnalyzedToken> fullNoSpace) {
        chunkerBuilds++;
        if (!enabled || !AndroidRuleCache.enabled()) return;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                writeStarts(out, startSpace); writeStarts(out, startNoSpace);
                writeTokens(out, fullSpace); writeTokens(out, fullNoSpace);
            }
            AndroidRuleCache.writeData("chunker:" + key, bytes.toByteArray());
        } catch (IOException unavailableStorage) { /* Retain the initialized in-memory maps. */ }
    }

    private static int count(DataInputStream in, int maximum) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > maximum) throw new IOException("Language cache size");
        return count;
    }

    private static void writeStarts(DataOutputStream out, Map<String, Integer> values) throws IOException {
        out.writeInt(values.size());
        for (Map.Entry<String, Integer> entry : values.entrySet()) { out.writeUTF(entry.getKey()); out.writeInt(entry.getValue()); }
    }

    private static void readStarts(DataInputStream in, Map<String, Integer> values) throws IOException {
        int size = count(in, 1000000);
        for (int i = 0; i < size; i++) values.put(in.readUTF(), in.readInt());
    }

    private static void writeTokens(DataOutputStream out, Map<String, AnalyzedToken> values) throws IOException {
        out.writeInt(values.size());
        for (Map.Entry<String, AnalyzedToken> entry : values.entrySet()) {
            out.writeUTF(entry.getKey());
            AnalyzedToken token = entry.getValue();
            out.writeUTF(token.getToken()); nullable(out, token.getPOSTag()); nullable(out, token.getLemma());
        }
    }

    private static void readTokens(DataInputStream in, Map<String, AnalyzedToken> values) throws IOException {
        int size = count(in, 1000000);
        for (int i = 0; i < size; i++) values.put(in.readUTF(), new AnalyzedToken(in.readUTF(), nullable(in), nullable(in)));
    }

    private static void nullable(DataOutputStream out, String value) throws IOException {
        out.writeBoolean(value != null); if (value != null) out.writeUTF(value);
    }

    private static String nullable(DataInputStream in) throws IOException { return in.readBoolean() ? in.readUTF() : null; }
}
