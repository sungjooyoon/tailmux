package dev.tailmux.text;

public final class Ascii {
    private Ascii() {
    }

    public static boolean containsIgnoreCase(String text, String needle) {
        if (needle.isEmpty()) return true;
        int end = text.length() - needle.length();
        char first = lower(needle.charAt(0));
        for (int i = 0; i <= end; i++) {
            if (lower(text.charAt(i)) == first && matchesIgnoreCase(text, i, needle)) return true;
        }
        return false;
    }

    public static boolean containsAnyIgnoreCase(String text, String[] needles) {
        for (String needle : needles) {
            if (needle.isEmpty()) return true;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = lower(text.charAt(i));
            for (String needle : needles) {
                if (i + needle.length() <= text.length()
                        && c == lower(needle.charAt(0))
                        && matchesIgnoreCase(text, i, needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matchesIgnoreCase(String text, int start, String needle) {
        for (int i = 1; i < needle.length(); i++) {
            if (lower(text.charAt(start + i)) != lower(needle.charAt(i))) return false;
        }
        return true;
    }

    private static char lower(char c) {
        return c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
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
        return trimRight(value, 0, value.length());
    }

    public static String trimRight(String value, int start, int end) {
        if (value == null || start >= end) return "";
        int to = end;
        while (to > start && whitespace(value.charAt(to - 1))) to--;
        return start == 0 && to == value.length() ? value : value.substring(start, to);
    }

    public static String trimLeft(String value) {
        if (value == null || value.isEmpty()) return "";
        return trimLeft(value, 0, value.length());
    }

    public static String trimLeft(String value, int start, int end) {
        if (value == null || start >= end) return "";
        int from = start;
        while (from < end && whitespace(value.charAt(from))) from++;
        return from == 0 && end == value.length() ? value : value.substring(from, end);
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
