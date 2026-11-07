/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.languagetool.offline;

import java.util.regex.Pattern;

/** Equivalent ICU syntax for regexes from Java sources and bundled grammar XML. */
public final class AndroidPatterns {
    private AndroidPatterns() { }

    public static Pattern compile(String expression) {
        return compile(expression, 0);
    }

    public static Pattern compile(String expression, int flags) {
        // Android's predefined character classes are already Unicode-aware.
        return Pattern.compile((flags & Pattern.LITERAL) == 0 ? normalize(expression) : expression, flags & ~256);
    }

    public static boolean matches(String expression, CharSequence text) {
        return compile(expression).matcher(text).matches();
    }

    static String normalize(String expression) {
        StringBuilder result = new StringBuilder(expression.length());
        boolean quoted = false;
        for (int i = 0; i < expression.length();) {
            if (expression.charAt(i) == '\\' && i + 1 < expression.length()) {
                char next = expression.charAt(i + 1);
                if (!quoted && (next == 'p' || next == 'P')
                        && expression.startsWith("{IsPunctuation}", i + 2)) {
                    result.append('\\').append(next).append("{P}");
                    i += 2 + "{IsPunctuation}".length();
                    continue;
                }
                result.append('\\').append(next);
                if (next == 'Q') quoted = true;
                if (next == 'E') quoted = false;
                i += 2;
                continue;
            }
            if (!quoted && expression.startsWith("(?", i)) {
                int end = i + 2;
                while (end < expression.length() && "idmsuxU-".indexOf(expression.charAt(end)) >= 0) end++;
                if (end < expression.length() && (expression.charAt(end) == ')' || expression.charAt(end) == ':')) {
                    String options = expression.substring(i + 2, end).replace("U", "");
                    if (options.replace("-", "").isEmpty()) options = "";
                    if (!options.isEmpty() || expression.charAt(end) == ':') {
                        result.append("(?").append(options).append(expression.charAt(end));
                    }
                    i = end + 1;
                    continue;
                }
            }
            result.append(expression.charAt(i++));
        }
        return result.toString();
    }
}
