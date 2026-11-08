package org.languagetool.offline;

import morfologik.fsa.builders.FSABuilder;
import morfologik.speller.Speller;
import morfologik.stemming.Dictionary;
import morfologik.stemming.DictionaryMetadata;
import morfologik.stemming.EncoderType;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class SpellerDecoderTest {
    @Test public void traversesIncompleteUtf8PrefixesAndKeepsAccentedCandidates() {
        List<byte[]> words = new ArrayList<>();
        for (String word : new String[]{"café", "spelling", "spilling", "éclair"}) {
            words.add(word.getBytes(StandardCharsets.UTF_8));
        }
        words.sort(FSABuilder.LEXICAL_ORDERING);
        DictionaryMetadata metadata = DictionaryMetadata.builder().separator('+')
                .encoding(StandardCharsets.UTF_8).encoder(EncoderType.NONE).build();
        Speller speller = new Speller(new Dictionary(FSABuilder.build(words), metadata));
        assertTrue(speller.findReplacements("speling").contains("spelling"));
        assertTrue(speller.findReplacements("cafe").contains("café"));
        assertTrue(speller.findReplacements("eclair").contains("éclair"));
    }
}
