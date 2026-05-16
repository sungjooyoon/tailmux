package dev.tailmux.exec;

import java.util.List;

public final class PosixShell {
    private PosixShell() {
    }

    public static String quote(String value) {
        if (value.isEmpty()) {
            return "''";
        }
        if (isSafeUnquoted(value)) {
            return value;
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    public static String join(List<String> args) {
        StringBuilder builder = new StringBuilder();
        for (String arg : args) append(builder, arg);
        return builder.toString();
    }

    public static String join(String... args) {
        StringBuilder builder = new StringBuilder();
        for (String arg : args) append(builder, arg);
        return builder.toString();
    }

    private static void append(StringBuilder builder, String arg) {
        if (!builder.isEmpty()) builder.append(' ');
        builder.append(quote(arg));
    }

    private static boolean isSafeUnquoted(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '@' || c == '%' || c == '+' || c == '=' || c == ':' || c == ',' || c == '.' || c == '/' || c == '-') {
                continue;
            }
            return false;
        }
        return true;
    }
}
