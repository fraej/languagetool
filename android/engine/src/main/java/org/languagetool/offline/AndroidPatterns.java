/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.languagetool.offline;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Equivalent ICU syntax for regexes from Java sources and bundled grammar XML. */
public final class AndroidPatterns {
    private AndroidPatterns() { }

    public static Pattern compile(String expression) {
        return compile(expression, 0);
    }

    public static Pattern compile(String expression, int flags) {
        // Android's predefined character classes are already Unicode-aware.
        String compatible = (flags & Pattern.LITERAL) == 0 ? normalize(expression) : expression;
        int compatibleFlags = flags & ~256;
        try {
            return Pattern.compile(compatible, compatibleFlags);
        } catch (PatternSyntaxException failure) {
            String reason = failure.getDescription().toLowerCase(java.util.Locale.ROOT);
            if (reason.contains("back-reference") || reason.contains("invalid_back_ref")) {
                return Pattern.compile(numericReferences(compatible, compatibleFlags), compatibleFlags);
            }
            throw failure;
        }
    }

    public static boolean matches(String expression, CharSequence text) {
        return compile(expression).matcher(text).matches();
    }

    static String normalize(String expression) {
        StringBuilder result = new StringBuilder(expression.length());
        boolean quoted = false;
        int characterClass = 0;
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
            if (!quoted && characterClass == 0 && expression.startsWith("(?", i)) {
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
            // Java ignores the second quantifier in this UNIT_SPACE number
            // pattern. ICU rejects it; removing it keeps the captures intact.
            if (!quoted && characterClass == 0 && expression.startsWith("*{1,30}", i)) {
                result.append('*');
                i += "*{1,30}".length();
                continue;
            }
            if (!quoted && expression.charAt(i) == '[') characterClass++;
            if (!quoted && expression.charAt(i) == ']' && characterClass > 0) characterClass--;
            result.append(expression.charAt(i++));
        }
        return result.toString();
    }

    // Java drops trailing digits until a numeric reference names an existing
    // group; a still-undefined reference never matches. ICU rejects both forms.
    static String numericReferences(String expression, int flags) {
        String neutral = rewriteReferences(expression, -1);
        int groups = Pattern.compile(neutral, flags).matcher("").groupCount();
        return rewriteReferences(expression, groups);
    }

    private static String rewriteReferences(String expression, int groups) {
        StringBuilder result = new StringBuilder(expression.length());
        boolean quoted = false;
        int characterClass = 0;
        for (int i = 0; i < expression.length();) {
            char value = expression.charAt(i);
            if (value == '\\' && i + 1 < expression.length()) {
                char next = expression.charAt(i + 1);
                if (!quoted && characterClass == 0 && next >= '1' && next <= '9') {
                    int end = i + 2;
                    while (end < expression.length() && expression.charAt(end) >= '0'
                            && expression.charAt(end) <= '9') end++;
                    if (groups < 0) {
                        result.append("(?:)");
                    } else {
                        int number = next - '0';
                        int consumed = i + 2;
                        while (consumed < end && number <= (groups - (expression.charAt(consumed) - '0')) / 10) {
                            number = number * 10 + expression.charAt(consumed++) - '0';
                        }
                        if (number > groups) result.append("(?!)");
                        else {
                            result.append('\\').append(number);
                            if (consumed < end) result.append("(?:)");
                        }
                        result.append(expression, consumed, end);
                    }
                    i = end;
                    continue;
                }
                result.append(value).append(next);
                if (next == 'Q') quoted = true;
                if (next == 'E') quoted = false;
                i += 2;
                continue;
            }
            if (!quoted && value == '[') characterClass++;
            if (!quoted && value == ']' && characterClass > 0) characterClass--;
            result.append(value);
            i++;
        }
        return result.toString();
    }
}
