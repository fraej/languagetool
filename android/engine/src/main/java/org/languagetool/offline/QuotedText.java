/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.languagetool.offline;

import java.util.Arrays;

/** Paired quotations/code literals are intentional; apostrophes inside words are not quotes. */
final class QuotedText {
    private QuotedText() { }

    static String withoutQuotations(String text) {
        boolean[] quoted = mask(text);
        char[] content = text.toCharArray();
        for (int i = 0; i < content.length; i++) if (quoted[i]) content[i] = ' ';
        return new String(content);
    }

    static boolean[] mask(String text) {
        boolean[] quoted = new boolean[text.length()];
        int start = -1;
        char closing = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean leftWord = i > 0 && Character.isLetterOrDigit(text.charAt(i - 1));
            boolean rightWord = i + 1 < text.length() && Character.isLetterOrDigit(text.charAt(i + 1));
            if ((c == '\'' || c == '’') && leftWord && rightWord) continue;
            if (closing != 0) {
                if (c == closing) {
                    Arrays.fill(quoted, start, i + 1, true);
                    closing = 0;
                }
            } else if (!leftWord) {
                closing = c == '“' || c == '„' ? '”' : c == '«' ? '»' : c == '‘' ? '’'
                        : c == '"' || c == '\'' || c == '`' ? c : 0;
                if (closing != 0) start = i;
            }
        }
        return quoted;
    }
}
