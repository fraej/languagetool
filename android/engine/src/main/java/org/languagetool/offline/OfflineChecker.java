/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.languagetool.offline;

import org.languagetool.JLanguageTool;
import org.languagetool.language.AmericanEnglish;
import org.languagetool.language.BritishEnglish;
import org.languagetool.rules.RuleMatch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Single-thread-confined adapter around the fork's local engine. */
public final class OfflineChecker {
    public static final int MAX_CHARACTERS = 10_000;
    private JLanguageTool tool;
    private String dialect;

    public void prepare(String requestedDialect) {
        if (!"en-US".equals(requestedDialect) && !"en-GB".equals(requestedDialect)) {
            throw new IllegalArgumentException("Unsupported dialect: " + requestedDialect);
        }
        if (tool != null && requestedDialect.equals(dialect)) {
            return;
        }
        // Desktop integration tests also use the bundled parser. Android rule
        // loaders select it explicitly in the generated compatibility sources.
        System.setProperty("javax.xml.parsers.SAXParserFactory",
                "org.apache.xerces.jaxp.SAXParserFactoryImpl");
        tool = new JLanguageTool("en-GB".equals(requestedDialect)
                ? BritishEnglish.getInstance() : AmericanEnglish.getInstance());
        dialect = requestedDialect;
        // No remote-rule activation, model download, or language-model directory.
    }

    public List<Issue> check(String text, String requestedDialect) throws IOException {
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
                    match.getRule().isDictionaryBasedSpellingRule(), match.getSuggestedReplacements()));
        }
        return Collections.unmodifiableList(issues);
    }

    public static final class Issue {
        public final int from;
        public final int to;
        public final String ruleId;
        public final String message;
        public final boolean spelling;
        public final List<String> replacements;

        public Issue(int from, int to, String ruleId, String message, boolean spelling,
                     List<String> replacements) {
            this.from = from;
            this.to = to;
            this.ruleId = ruleId;
            this.message = message;
            this.spelling = spelling;
            this.replacements = Collections.unmodifiableList(new ArrayList<>(replacements));
        }

        /** Offsets are UTF-16, as used by Android's Editable and Java strings. */
        public String apply(String checkedText, String replacement) {
            return checkedText.substring(0, from) + replacement + checkedText.substring(to);
        }
    }
}
