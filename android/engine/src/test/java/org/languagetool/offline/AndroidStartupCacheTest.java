package org.languagetool.offline;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.languagetool.AnalyzedToken;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class AndroidStartupCacheTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @After public void reset() { AndroidStartupCache.setEnabled(true); AndroidRuleCache.configure(null); }

    @Test public void persistsPosMatchesAndNullResultsWithoutRepeatingTheScan() throws Exception {
        File directory = temporary.newFolder();
        AndroidRuleCache.configure(directory);
        Set<String> universe = Set.of("NCMS000", "AQ0MS0");
        assertEquals(Set.of("NCMS000"), AndroidStartupCache.postags("NC.*", true, universe, () -> Set.of("NCMS000")));
        assertNull(AndroidStartupCache.postags(".*", false, universe, () -> null));
        AndroidStartupCache.flush();
        AndroidRuleCache.configure(directory);
        assertEquals(Set.of("NCMS000"), AndroidStartupCache.postags("NC.*", true, Set.copyOf(universe), () -> {
            throw new AssertionError("Repeated POS universe scan");
        }));
        assertNull(AndroidStartupCache.postags(".*", false, universe, () -> { throw new AssertionError("Repeated broad-pattern scan"); }));
    }

    @Test public void posCacheSeparatesDifferentUniversesWithTheSameHashCode() throws Exception {
        AndroidRuleCache.configure(temporary.newFolder());
        Set<String> first = Set.of("Aa"), second = Set.of("BB");
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(first, AndroidStartupCache.postags(".*", true, first, () -> first));
        assertEquals(second, AndroidStartupCache.postags(".*", true, second, () -> second));
    }

    @Test public void chunkerRestoresUnicodeCaseVariantsTagsAndLemmas() throws Exception {
        AndroidRuleCache.configure(temporary.newFolder());
        Map<String, Integer> startSpace = new HashMap<>(Map.of("Sant", 3)), startNoSpace = new HashMap<>(Map.of("l", 8));
        Map<String, AnalyzedToken> fullSpace = new HashMap<>(Map.of("Sant Feliu", new AnalyzedToken("Sant Feliu", "NPCN000", "sant feliu")));
        Map<String, AnalyzedToken> fullNoSpace = new HashMap<>(Map.of("l·l", new AnalyzedToken("l·l", null, null)));
        AndroidStartupCache.saveChunker("ca:true", startSpace, startNoSpace, fullSpace, fullNoSpace);
        Map<String, Integer> readStart = new HashMap<>(), readNoSpace = new HashMap<>();
        Map<String, AnalyzedToken> readFull = new HashMap<>(), readFullNoSpace = new HashMap<>();
        assertTrue(AndroidStartupCache.chunker("ca:true", readStart, readNoSpace, readFull, readFullNoSpace));
        assertEquals(startSpace, readStart); assertEquals(startNoSpace, readNoSpace);
        assertEquals(fullSpace, readFull); assertEquals(fullNoSpace, readFullNoSpace);
        assertFalse(AndroidStartupCache.chunker("ca:false", new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>()));
    }

    @Test public void corruptedComputedDataFallsBackToTheOriginalComputation() throws Exception {
        File directory = temporary.newFolder();
        AndroidRuleCache.configure(directory);
        AndroidStartupCache.postags("NC.*", true, Set.of("NC"), () -> Set.of("NC"));
        AndroidStartupCache.flush();
        File file = directory.listFiles()[0];
        byte[] data = Files.readAllBytes(file.toPath()); data[data.length - 1] ^= 0x42; Files.write(file.toPath(), data);
        AndroidRuleCache.configure(directory);
        assertEquals(Set.of("fixed"), AndroidStartupCache.postags("NC.*", true, Set.of("NC"), () -> Set.of("fixed")));
    }

    @Test public void restoresCompiledSpellingAutomatonAndRejectsInvalidBinaryData() throws Exception {
        AndroidRuleCache.configure(temporary.newFolder());
        java.util.List<byte[]> words = new java.util.ArrayList<>();
        for (String word : new String[]{"café", "spelling", "pel·lícula"}) words.add(word.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        words.sort(morfologik.fsa.builders.FSABuilder.LEXICAL_ORDERING);
        java.io.ByteArrayOutputStream compiled = new morfologik.fsa.builders.CFSA2Serializer()
                .serialize(morfologik.fsa.builders.FSABuilder.build(words), new java.io.ByteArrayOutputStream());
        String metadata = "/ca/ca-ES_spelling.info";
        AndroidStartupCache.saveDictionary("test", metadata, compiled.toByteArray());
        morfologik.stemming.Dictionary restored = AndroidStartupCache.dictionary("test", metadata);
        assertNotNull(restored);
        Set<String> read = new java.util.HashSet<>();
        for (java.nio.ByteBuffer sequence : restored.fsa) {
            byte[] bytes = new byte[sequence.remaining()]; sequence.get(bytes);
            read.add(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        }
        assertEquals(Set.of("café", "spelling", "pel·lícula"), read);
        AndroidRuleCache.writeData("dictionary:test", new byte[]{1, 2, 3});
        assertNull(AndroidStartupCache.dictionary("test", metadata));
    }
}
