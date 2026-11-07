package org.languagetool.offline;

import org.junit.Test;
import static org.junit.Assert.*;

public class WrittenLanguageDetectorTest {
    private static final WrittenLanguageDetector detector = new WrittenLanguageDetector();

    @Test public void detectsEnglishWhenCatalanOrSpanishIsSelected() throws Exception {
        for (String selected : new String[]{"ca-ES", "ca-ES-valencia", "ca-ES-balear", "es"}) {
            assertEquals("en", detector.mismatch("This is an English sentence. I would like to check my spelling.", selected));
            assertEquals("en", detector.mismatch("This are a example. I have have a speling mistake.", selected));
        }
    }

    @Test public void detectsSpanishWhenEnglishOrCatalanIsSelected() throws Exception {
        for (String selected : new String[]{"en-US", "en-GB", "ca-ES"}) {
            assertEquals("es", detector.mismatch("Hola, esto es una prueba escrita en español.", selected));
            assertEquals("es", detector.mismatch("Una casa bonito. Esto es un errror.", selected));
        }
    }

    @Test public void detectsCatalanWhenEnglishOrSpanishIsSelected() throws Exception {
        for (String selected : new String[]{"en-US", "es"}) {
            assertEquals("ca", detector.mismatch("Aquesta és una pel·lícula bonica. M’agrada molt aquesta història.", selected));
        }
    }

    @Test public void preservesSelectedEnglishAndCatalanVarieties() throws Exception {
        for (String selected : new String[]{"en-US", "en-GB"}) {
            assertNull(detector.mismatch("The colour of this house is beautiful.", selected));
        }
        for (String selected : new String[]{"ca-ES", "ca-ES-valencia", "ca-ES-balear"}) {
            assertNull(detector.mismatch("Jo penso que aquesta casa és molt bonica.", selected));
        }
        assertNull(detector.mismatch("Hola, esto es una prueba escrita en español.", "es"));
    }

    @Test public void leavesShortAmbiguousTextToTheSelectedLanguage() throws Exception {
        for (String text : new String[]{"", "Hello", "Hello world", "Bon dia", "Jo penso.", "John Smith", "😀 123"}) {
            assertNull(detector.mismatch(text, "ca-ES"));
        }
    }

    @Test public void ignoresLanguageInsideUrlsAndEmailAddresses() throws Exception {
        assertNull(detector.mismatch("https://example.com/this-is-a-long-english-sentence hello@example.com", "ca-ES"));
    }

    @Test public void nearbyUnsupportedLanguagesAreNotForcedIntoASupportedLanguage() throws Exception {
        assertNull(detector.mismatch("Bonjour, ceci est un texte rédigé en français pour vérifier la langue.", "ca-ES"));
        assertNull(detector.mismatch("Este é um texto escrito em português para verificar a língua.", "ca-ES"));
    }

    @Test public void refusesUnsupportedSelections() {
        assertThrows(IllegalArgumentException.class, () -> detector.mismatch("This is a test.", "unsupported"));
    }
}
