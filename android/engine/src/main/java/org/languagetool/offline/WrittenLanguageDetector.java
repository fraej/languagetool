/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.languagetool.offline;

import com.optimaize.langdetect.DetectedLanguage;
import com.optimaize.langdetect.LanguageDetector;
import com.optimaize.langdetect.LanguageDetectorBuilder;
import com.optimaize.langdetect.i18n.LdLocale;
import com.optimaize.langdetect.ngram.NgramExtractors;
import com.optimaize.langdetect.profiles.LanguageProfileReader;
import com.optimaize.langdetect.text.CharNormalizerTextFilterImpl;
import com.optimaize.langdetect.text.TextObjectFactory;
import com.optimaize.langdetect.text.TextObjectFactoryBuilder;
import com.optimaize.langdetect.text.UrlTextFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Advisory language detection; checking language and dialect stay under user control. */
public final class WrittenLanguageDetector {
    private static final Pattern EMAIL = Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern WORD = Pattern.compile("\\p{L}[\\p{L}\\p{M}'’]*");
    private static final List<String> SUPPORTED = Arrays.asList("en", "es", "ca");
    private static final int MAX_SAMPLE = 4000;
    private LanguageDetector detector;
    private TextObjectFactory textFactory;

    /** Returns a supported base language only for a confident mismatch, otherwise null. */
    public synchronized String mismatch(String text, String selectedDialect) throws IOException {
        if (!OfflineChecker.SUPPORTED_LANGUAGES.contains(selectedDialect)) throw new IllegalArgumentException("Unsupported dialect");
        if (textFactory == null) textFactory = new TextObjectFactoryBuilder()
                .maxTextLength(MAX_SAMPLE).withTextFilter(UrlTextFilter.getInstance())
                .withTextFilter(new CharNormalizerTextFilterImpl()).build();
        String unquoted = QuotedText.withoutQuotations(text);
        String sample = EMAIL.matcher(unquoted.substring(0, Math.min(unquoted.length(), MAX_SAMPLE))).replaceAll(" ");
        sample = textFactory.forText(sample).toString();
        Matcher words = WORD.matcher(sample);
        int count = 0, letters = 0;
        while (words.find()) { count++; letters += words.group().codePointCount(0, words.group().length()); }
        // Names, shared words and short fragments are too ambiguous to override a manual choice.
        if (count < 3 || letters < 10) return null;
        if (detector == null) {
            List<LdLocale> profiles = new ArrayList<>();
            // Nearby-language profiles prevent forcing French/Portuguese/Italian/German into a supported language.
            for (String language : Arrays.asList("en", "es", "ca", "fr", "pt", "it", "de")) profiles.add(LdLocale.fromString(language));
            detector = LanguageDetectorBuilder.create(NgramExtractors.standard()).seed(0)
                    .withProfiles(new LanguageProfileReader().readBuiltIn(profiles)).build();
        }
        List<DetectedLanguage> probabilities = detector.getProbabilities(sample);
        if (probabilities.isEmpty()) return null;
        DetectedLanguage best = probabilities.get(0);
        double next = probabilities.size() > 1 ? probabilities.get(1).getProbability() : 0;
        String language = best.getLocale().getLanguage();
        if (best.getProbability() < 0.95 || best.getProbability() - next < 0.40
                || !SUPPORTED.contains(language) || language.equals(baseLanguage(selectedDialect))) return null;
        return language;
    }

    public static String baseLanguage(String dialect) { return dialect.split("-", 2)[0]; }
}
