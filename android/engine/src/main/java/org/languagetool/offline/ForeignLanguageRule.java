/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.languagetool.offline;

import org.languagetool.AnalyzedSentence;
import org.languagetool.AnalyzedToken;
import org.languagetool.AnalyzedTokenReadings;
import org.languagetool.rules.Category;
import org.languagetool.rules.CategoryId;
import org.languagetool.rules.ITSIssueType;
import org.languagetool.rules.RuleMatch;
import org.languagetool.rules.TextLevelRule;
import org.languagetool.markup.AnnotatedText;

import java.util.ArrayList;
import java.util.List;

/** Surface English spans that the Catalan disambiguator deliberately exempts from spelling. */
public final class ForeignLanguageRule extends TextLevelRule {
    public static final String ID = "LANGT_FOREIGN_LANGUAGE";
    private static final String ENGLISH = "_english_ignore_";

    public ForeignLanguageRule() {
        setCategory(new Category(new CategoryId("LANGT_LANGUAGE"), "Language"));
        setLocQualityIssueType(ITSIssueType.LocaleViolation);
    }

    @Override public String getId() { return ID; }
    @Override public String getDescription() { return "English fragments inside Catalan text"; }

    @Override public int minToCheckParagraph() { return -1; }

    @Override public RuleMatch[] match(List<AnalyzedSentence> sentences) {
        StringBuilder text = new StringBuilder();
        for (AnalyzedSentence sentence : sentences) text.append(sentence.getText());
        return findMatches(sentences, QuotedText.mask(text.toString()));
    }

    @Override public RuleMatch[] match(List<AnalyzedSentence> sentences, AnnotatedText text) {
        return findMatches(sentences, QuotedText.mask(text.getPlainText()));
    }

    private RuleMatch[] findMatches(List<AnalyzedSentence> sentences, boolean[] quoted) {
        List<RuleMatch> matches = new ArrayList<>();
        List<AnalyzedTokenReadings> group = new ArrayList<>();
        int offset = 0;
        for (AnalyzedSentence sentence : sentences) {
            for (AnalyzedTokenReadings token : sentence.getTokens()) {
                if (token.isWhitespace()) continue;
                int start = offset + token.getStartPos();
                if (token.hasPosTag(ENGLISH) && !token.isImmunized()
                        && start >= 0 && start < quoted.length && !quoted[start]) {
                    group.add(token);
                } else {
                    addMatch(sentence, group, offset, matches);
                    group.clear();
                }
            }
            addMatch(sentence, group, offset, matches);
            group.clear();
            offset += sentence.getCorrectedTextLength();
        }
        return matches.toArray(new RuleMatch[0]);
    }

    private void addMatch(AnalyzedSentence sentence, List<AnalyzedTokenReadings> group, int offset, List<RuleMatch> matches) {
        int foreignWords = 0;
        boolean properName = false, titleCaseForeign = true;
        for (AnalyzedTokenReadings token : group) {
            properName |= token.hasPosTagStartingWith("NP");
            if (!hasCatalanReading(token)) {
                foreignWords++;
                titleCaseForeign &= !token.getToken().isEmpty() && Character.isUpperCase(token.getToken().codePointAt(0));
            }
        }
        // Shared words alone are not evidence, and a name such as "The Beatles" is legitimate.
        // A foreign article plus a shared noun can still be a phrase: "the internet".
        if (group.size() < 2 || foreignWords == 0 || foreignWords == 1 && properName && titleCaseForeign) return;
        int first = 0, last = group.size() - 1;
        while (first < last && hasCatalanReading(group.get(first))) first++;
        while (last > first && hasCatalanReading(group.get(last))) last--;
        matches.add(new RuleMatch(this, sentence, offset + group.get(first).getStartPos(), offset + group.get(last).getEndPos(),
                "This fragment looks like English inside Catalan text. Review it, or put it in quotation marks if intentional."));
    }

    private static boolean hasCatalanReading(AnalyzedTokenReadings token) {
        for (AnalyzedToken reading : token.getReadings()) {
            String pos = reading.getPOSTag();
            if (pos != null && !pos.isEmpty() && Character.isUpperCase(pos.charAt(0))
                    && !pos.equals("UNKNOWN") && !pos.equals("SENT_START")
                    && !pos.equals("SENT_END") && !pos.equals("PARA_END")) return true;
        }
        return false;
    }

}
