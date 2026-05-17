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
}
