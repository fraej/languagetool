package org.languagetool.offline;

import org.junit.Test;
import java.util.regex.Pattern;
import static org.junit.Assert.*;

public class AndroidPatternsTest {
    @Test public void acceptsJavaEmptyFlagGroupFromGrammarXml() {
        assertTrue(AndroidPatterns.matches("(?)id", "id"));
    }

    @Test public void acceptsJavaUnicodeOptionsWithOtherFlags() {
        assertTrue(AndroidPatterns.compile("(?iU:example)", 256).matcher("EXAMPLE").matches());
        assertTrue(AndroidPatterns.matches("(?U)example", "example"));
    }

    @Test public void usesEquivalentPunctuationCategory() {
        assertTrue(AndroidPatterns.matches("\\p{IsPunctuation}", "!"));
        assertTrue(AndroidPatterns.matches("\\P{IsPunctuation}", "a"));
    }

    @Test public void preservesQuotedAndEscapedText() {
        String literal = "(?U)(?)\\p{IsPunctuation}";
        assertTrue(AndroidPatterns.matches(Pattern.quote(literal), literal));
        assertEquals("\\(\\?U\\)", AndroidPatterns.normalize("\\(\\?U\\)"));
        assertTrue(AndroidPatterns.compile(literal, Pattern.LITERAL).matcher(literal).matches());
        assertTrue(AndroidPatterns.matches("[(?)]", "("));
    }

    @Test public void preservesJavaUndefinedNumericReferenceAlternatives() {
        Pattern pattern = Pattern.compile(AndroidPatterns.numericReferences("\\1|word", 0));
        assertTrue(pattern.matcher("word").matches());
        assertFalse(pattern.matcher("anything").matches());
        assertFalse(pattern.matcher("").matches());
    }

    @Test public void preservesJavaNumericReferenceDigitTruncation() {
        Pattern pattern = Pattern.compile(AndroidPatterns.numericReferences("(a)\\12", 0));
        assertTrue(pattern.matcher("aa2").matches());
        assertFalse(pattern.matcher("aa").matches());
        assertEquals("\\Q\\12\\E", AndroidPatterns.numericReferences("\\Q\\12\\E", 0));
    }
}
