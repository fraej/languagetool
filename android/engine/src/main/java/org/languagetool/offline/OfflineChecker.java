/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.languagetool.offline;

import org.languagetool.JLanguageTool;
import org.languagetool.Languages;
import org.languagetool.Language;
import org.languagetool.rules.RuleMatch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Single-thread-confined adapter around the fork's local engine. */
public final class OfflineChecker {
    public static final int MAX_CHARACTERS = 10_000;
    public static final List<String> SUPPORTED_LANGUAGES = Collections.unmodifiableList(
            Arrays.asList("en-US", "en-GB", "es", "ca-ES", "ca-ES-valencia", "ca-ES-balear"));
    private JLanguageTool tool;
    private String dialect;
    private volatile String warmedDialect;

    public synchronized void prepare(String requestedDialect) {
        if (!SUPPORTED_LANGUAGES.contains(requestedDialect)) {
            throw new IllegalArgumentException("Unsupported dialect: " + requestedDialect);
        }
        if (tool != null && requestedDialect.equals(dialect)) {
            return;
        }
        // Desktop integration tests also use the bundled parser. Android rule
        // loaders select it explicitly in the generated compatibility sources.
        System.setProperty("javax.xml.parsers.SAXParserFactory",
                "org.apache.xerces.jaxp.SAXParserFactoryImpl");
        // Drop the previous checker's Java rules before loading the next one.
        // LanguageTool retains shared dictionaries separately.
        tool = null;
        dialect = null;
        warmedDialect = null;
        Language requested = Languages.getLanguageForShortCode(requestedDialect);
        for (Language language : Languages.get()) {
            if (language != requested) language.clearAndroidPatternRules();
        }
        tool = new JLanguageTool(requested);
        if (requested.getShortCode().equals("ca")) tool.addRule(new ForeignLanguageRule());
        dialect = requestedDialect;
        // No remote-rule activation, model download, or language-model directory.
    }

    /** Force lazy dictionaries, taggers and disambiguation data to load off the UI thread. */
    public synchronized void warmUp(String requestedDialect) throws IOException {
        if (isReady(requestedDialect)) return;
        String sample = requestedDialect.startsWith("ca") ? "Aquesta és una casa."
                : requestedDialect.equals("es") ? "Esta es una casa." : "This is a house.";
        AndroidInterning.begin();
        try {
            check(sample, requestedDialect);
            warmedDialect = requestedDialect;
        } finally { AndroidInterning.end(); }
    }

    public boolean isReady(String requestedDialect) {
        return requestedDialect.equals(warmedDialect);
    }

    public synchronized List<Issue> check(String text, String requestedDialect) throws IOException {
        if (text.length() > MAX_CHARACTERS) {
            throw new IllegalArgumentException("Text exceeds " + MAX_CHARACTERS + " characters");
        }
        if (text.trim().isEmpty()) {
            return Collections.emptyList();
        }
        prepare(requestedDialect);
        List<Issue> issues = new ArrayList<>();
        for (RuleMatch match : tool.check(text)) {
            int from = match.getFromPos();
            int to = match.getToPos();
            if (from < 0 || to < from || to > text.length()) {
                throw new IOException("Engine returned an invalid text range");
            }
            issues.add(new Issue(from, to, match.getRule().getId(), match.getMessage(),
                    match.getRule().isDictionaryBasedSpellingRule(), match.getSuggestedReplacements(),
                    issueType(match)));
        }
        return Collections.unmodifiableList(issues);
    }

    private static String issueType(RuleMatch match) {
        if (match.getRule() instanceof ForeignLanguageRule) return "language";
        String type = match.getRule().getLocQualityIssueType().toString();
        String category = match.getRule().getCategory().getId().toString();
        if (match.getRule().isDictionaryBasedSpellingRule() || "misspelling".equalsIgnoreCase(type)) return "spelling";
        if ("style".equalsIgnoreCase(type) || "typographical".equalsIgnoreCase(type)
                || "formatting".equalsIgnoreCase(type) || "STYLE".equals(category)
                || "REDUNDANCY".equals(category) || "TYPOGRAPHY".equals(category)) return "style";
        return "grammar";
    }

    public static final class Issue {
        public final int from;
        public final int to;
        public final String ruleId;
        public final String message;
        public final boolean spelling;
        public final List<String> replacements;
        public final String type;

        public Issue(int from, int to, String ruleId, String message, boolean spelling,
                     List<String> replacements) {
            this(from, to, ruleId, message, spelling, replacements, spelling ? "spelling" : "grammar");
        }

        public Issue(int from, int to, String ruleId, String message, boolean spelling,
                     List<String> replacements, String type) {
            this.from = from;
            this.to = to;
            this.ruleId = ruleId;
            this.message = message;
            this.spelling = spelling;
            this.replacements = Collections.unmodifiableList(new ArrayList<>(replacements));
            this.type = type;
        }

        /** Offsets are UTF-16, as used by Android's Editable and Java strings. */
        public String apply(String checkedText, String replacement) {
            return checkedText.substring(0, from) + replacement + checkedText.substring(to);
        }
    }
}
