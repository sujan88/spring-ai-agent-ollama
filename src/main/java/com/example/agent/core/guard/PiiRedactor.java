package com.example.agent.core.guard;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks sensitive numbers in text shown to the user: payment card numbers (13-19 digits that pass
 * the Luhn check, with optional spaces or dashes) and US social security numbers.
 * Order ids, prices, dates and phone numbers are left alone.
 */
public final class PiiRedactor {

    static final String CARD_MASK = "[REDACTED CARD]";
    static final String SSN_MASK = "[REDACTED SSN]";

    private static final Pattern CARD = Pattern.compile("(?<![\\d-])(?:\\d[ -]?){12,18}\\d(?![\\d-])");
    private static final Pattern SSN = Pattern.compile("(?<!\\d)\\d{3}-\\d{2}-\\d{4}(?!\\d)");

    private PiiRedactor() {
    }

    public static String redact(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = SSN.matcher(text).replaceAll(SSN_MASK);
        Matcher m = CARD.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String digits = m.group().replaceAll("[ -]", "");
            m.appendReplacement(sb, Matcher.quoteReplacement(luhn(digits) ? CARD_MASK : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * How many trailing characters to hold back in a stream, so that a number split across two
     * chunks is never emitted half-masked: the trailing run of digits, spaces and dashes.
     */
    public static int unsafeTail(CharSequence text) {
        int i = text.length();
        while (i > 0) {
            char c = text.charAt(i - 1);
            if (Character.isDigit(c) || c == ' ' || c == '-') i--;
            else break;
        }
        boolean hasDigit = false;
        for (int j = i; j < text.length(); j++) if (Character.isDigit(text.charAt(j))) { hasDigit = true; break; }
        return hasDigit ? text.length() - i : 0;
    }

    static boolean luhn(String digits) {
        int sum = 0;
        boolean twice = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (twice) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            twice = !twice;
        }
        return digits.length() >= 13 && sum % 10 == 0;
    }
}
