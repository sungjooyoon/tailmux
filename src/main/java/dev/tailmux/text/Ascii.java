package dev.tailmux.text;

public final class Ascii {
    private Ascii() {
    }

    public static boolean containsIgnoreCase(String text, String needle) {
        int end = text.length() - needle.length();
        for (int i = 0; i <= end; i++) {
            if (text.regionMatches(true, i, needle, 0, needle.length())) return true;
        }
        return false;
    }

    public static String trim(String value) {
        if (value == null || value.isEmpty()) return "";
        int start = 0;
        int end = value.length();
        while (start < end && whitespace(value.charAt(start))) start++;
        while (end > start && whitespace(value.charAt(end - 1))) end--;
        return start == 0 && end == value.length() ? value : value.substring(start, end);
    }

    private static boolean whitespace(char c) {
        return c == ' ' || c == '\n' || c == '\r' || c == '\t';
    }
}
