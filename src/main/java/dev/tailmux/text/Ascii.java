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
        return trim(value, 0, value.length());
    }

    public static String trim(String value, int start, int end) {
        if (value == null || start >= end) return "";
        int from = start;
        int to = end;
        while (from < to && whitespace(value.charAt(from))) from++;
        while (to > from && whitespace(value.charAt(to - 1))) to--;
        return from == 0 && to == value.length() ? value : value.substring(from, to);
    }

    public static String trimRight(String value) {
        if (value == null || value.isEmpty()) return "";
        int end = value.length();
        while (end > 0 && whitespace(value.charAt(end - 1))) end--;
        return end == value.length() ? value : value.substring(0, end);
    }

    public static String trimLeft(String value) {
        if (value == null || value.isEmpty()) return "";
        int start = 0;
        while (start < value.length() && whitespace(value.charAt(start))) start++;
        return start == 0 ? value : value.substring(start);
    }

    public static boolean hasText(String value) {
        if (value == null) return false;
        for (int i = 0; i < value.length(); i++) {
            if (!whitespace(value.charAt(i))) return true;
        }
        return false;
    }

    public static boolean whitespace(char c) {
        return c == ' ' || c == '\n' || c == '\r' || c == '\t';
    }
}
