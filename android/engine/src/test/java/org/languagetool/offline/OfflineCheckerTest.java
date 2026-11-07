package org.languagetool.offline;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class OfflineCheckerTest {
    private static OfflineChecker checker;

    @BeforeClass public static void initialize() {
        checker = new OfflineChecker();
    }

    @Test public void loadsBundledGrammarAndSpellingWithStandaloneXerces() throws Exception {
        String text = "This are a example. I have have a speling mistake.";
        List<OfflineChecker.Issue> issues = checker.check(text, "en-US");
        assertTrue("Expected grammar issues", issues.stream().anyMatch(issue -> !issue.spelling));
        assertTrue("Expected bundled dictionary to suggest spelling",
                issues.stream().anyMatch(issue -> issue.spelling && issue.replacements.contains("spelling")));
        assertTrue("Expected repeat-word rule", issues.stream().anyMatch(issue ->
                text.substring(issue.from, issue.to).contains("have")));
    }

    @Test public void supportsBritishAndAmericanSpelling() throws Exception {
        String text = "The colour is beautiful.";
        assertTrue(checker.check(text, "en-US").stream().anyMatch(issue -> issue.spelling));
        assertFalse(checker.check(text, "en-GB").stream().anyMatch(issue -> issue.spelling));
    }

    @Test public void loadsXmlPatternRulesAndPosModels() throws Exception {
        assertTrue("Expected an XML grammar rule, not just Java spelling/punctuation rules",
                checker.check("This are the options.", "en-US").stream()
                        .anyMatch(issue -> "PLURAL_VERB_AFTER_THIS".equals(issue.ruleId)));
    }

    @Test public void offsetsApplyToOriginalTextIncludingEmojiAndNewlines() throws Exception {
        String text = "Hello 😀.\n\nThis is a speling mistake.";
        OfflineChecker.Issue issue = checker.check(text, "en-US").stream()
                .filter(match -> match.spelling && match.replacements.contains("spelling"))
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals("speling", text.substring(issue.from, issue.to));
        assertEquals("Hello 😀.\n\nThis is a spelling mistake.", issue.apply(text, "spelling"));
    }

    @Test public void acceptsEmptyText() throws Exception {
        assertTrue(checker.check("  \n", "en-US").isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesOversizeText() throws Exception {
        checker.check("a".repeat(OfflineChecker.MAX_CHARACTERS + 1), "en-US");
    }
}
