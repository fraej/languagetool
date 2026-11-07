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

    @Test public void spanishGrammarSpellingAndAccents() throws Exception {
        assertLanguage("es", "Una casa bonito. Esto es un errror.", "bonita",
                "Tiene una canción bonita.");
    }

    @Test public void catalanGrammarSpellingAndAccents() throws Exception {
        assertLanguage("ca-ES", "Una casa bonic. Això és un errror.", "bonica",
                "Aquesta és una pel·lícula bonica.");
    }

    @Test public void allCatalanVarietiesUseTheirOwnVerbForms() throws Exception {
        for (String language : new String[]{"ca-ES", "ca-ES-valencia", "ca-ES-balear", "ca-ES"}) {
            assertLanguage(language, "Una casa bonic. Això és un errror.", "bonica",
                    "Aquesta és una pel·lícula bonica.");
        }
        assertFalse(checker.check("Jo penso.", "ca-ES").stream()
                .anyMatch(issue -> issue.ruleId.startsWith("EXIGEIX_VERBS_")));
        assertTrue(checker.check("Jo penso.", "ca-ES-valencia").stream().anyMatch(issue ->
                issue.ruleId.startsWith("EXIGEIX_VERBS_VALENCIANS") && issue.replacements.contains("pense")));
        assertTrue(checker.check("Jo ho pense.", "ca-ES-balear").stream().anyMatch(issue ->
                issue.ruleId.startsWith("EXIGEIX_VERBS_BALEARS") && issue.replacements.contains("pens")));
    }

    @Test public void switchesLanguagesAndPreservesUnicodeOffsets() throws Exception {
        for (String language : new String[]{"es", "ca-ES", "en-US", "es"}) {
            String text = "😀\n\n" + ("ca-ES".equals(language) ? "Això és un errror."
                    : "es".equals(language) ? "Esto es un errror." : "This is an errror.");
            OfflineChecker.Issue issue = checker.check(text, language).stream()
                    .filter(match -> match.spelling && match.replacements.contains("error"))
                    .findFirst().orElseThrow(AssertionError::new);
            assertEquals("errror", text.substring(issue.from, issue.to));
            assertEquals(text.replace("errror", "error"), issue.apply(text, "error"));
        }
    }

    private static void assertLanguage(String language, String text, String adjective,
                                       String correctText) throws Exception {
        List<OfflineChecker.Issue> issues = checker.check(text, language);
        assertTrue("Expected agreement rule in " + language, issues.stream().anyMatch(issue ->
                "AGREEMENT_POSTPONED_ADJ".equals(issue.ruleId) && issue.replacements.contains(adjective)));
        assertTrue("Expected dictionary in " + language, issues.stream().anyMatch(issue ->
                issue.spelling && issue.replacements.contains("error")));
        assertTrue("Accented words should be accepted", checker.check(correctText, language).isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesOversizeText() throws Exception {
        checker.check("a".repeat(OfflineChecker.MAX_CHARACTERS + 1), "en-US");
    }
}
