package org.languagetool.offline;

import org.junit.Test;
import java.util.List;
import java.util.stream.Collectors;
import static org.junit.Assert.*;

public class ForeignLanguageRuleTest {
    private static final OfflineChecker checker = new OfflineChecker();
    private static final String SCREENSHOT = "Ara no ho sé dir. nom the less";

    @Test public void flagsTheScreenshotFragmentInAllCatalanVarieties() throws Exception {
        for (String language : new String[]{"ca-ES", "ca-ES-valencia", "ca-ES-balear"}) {
            List<OfflineChecker.Issue> issues = foreign(SCREENSHOT, language);
            assertEquals(language, 1, issues.size());
            OfflineChecker.Issue issue = issues.get(0);
            assertEquals("the less", SCREENSHOT.substring(issue.from, issue.to));
            assertEquals("language", issue.type);
            assertFalse(issue.spelling);
            assertTrue("Do not invent a correction or translation", issue.replacements.isEmpty());
        }
    }

    @Test public void flagsOtherSkippedEnglishPhrasesWithoutMarkingTheCatalanPrefix() throws Exception {
        String prefix = "Ara no ho sé dir. ";
        for (String fragment : new String[]{"none the less", "the weather is nice", "This is wrong"}) {
            String text = prefix + fragment;
            List<OfflineChecker.Issue> issues = foreign(text, "ca-ES-balear");
            assertEquals(fragment, 1, issues.size());
            assertTrue(issues.get(0).from >= prefix.length());
            assertEquals(fragment, text.substring(issues.get(0).from, issues.get(0).to));
        }
        String shared = prefix + "the internet";
        assertFalse("A shared Catalan noun must not hide an English article", foreign(shared, "ca-ES-balear").isEmpty());
    }

    @Test public void preservesUnicodeParagraphAndSoftHyphenOffsets() throws Exception {
        for (String text : new String[]{"😀\n\n" + SCREENSHOT, "Ara no ho sé d\u00ADir. nom the less"}) {
            List<OfflineChecker.Issue> issues = foreign(text, "ca-ES-balear");
            assertEquals(1, issues.size());
            assertEquals("the less", text.substring(issues.get(0).from, issues.get(0).to));
        }
    }

    @Test public void preservesValidCatalanSharedWordsAndProperNames() throws Exception {
        for (String text : new String[]{"Ara no ho sé dir.", "El nom del llibre és bonic.",
                "John Smith viu a Londres.", "M'agrada escoltar The Beatles.",
                "Ara no ho sé dir. No obstant això, ho intentaré."}) {
            assertTrue(text, foreign(text, "ca-ES-balear").isEmpty());
        }
    }

    @Test public void respectsPairedQuotesAcrossSentencesAndApostrophesInsideWords() throws Exception {
        for (String[] quotes : new String[][]{{"\"", "\""}, {"'", "'"}, {"“", "”"}, {"«", "»"}, {"‘", "’"}, {"`", "`"}}) {
            String text = "L'anglès no és la meva llengua. " + quotes[0] + "The weather is nice. This is English." + quotes[1];
            assertTrue(text, foreign(text, "ca-ES-balear").isEmpty());
        }
        assertFalse(foreign("L'anglès no és la meva llengua. the weather is nice", "ca-ES-balear").isEmpty());
        assertFalse(foreign("Ara no ho sé dir. \"the weather is nice", "ca-ES-balear").isEmpty());
    }

    @Test public void mixedCatalanTextDoesNotRequestSwitchingTheWholeDocumentToEnglish() throws Exception {
        assertNull(new WrittenLanguageDetector().mismatch(SCREENSHOT, "ca-ES-balear"));
        assertFalse(foreign(SCREENSHOT, "ca-ES-balear").isEmpty());
    }

    private static List<OfflineChecker.Issue> foreign(String text, String language) throws Exception {
        return checker.check(text, language).stream().filter(issue -> issue.ruleId.equals(ForeignLanguageRule.ID)).collect(Collectors.toList());
    }
}
