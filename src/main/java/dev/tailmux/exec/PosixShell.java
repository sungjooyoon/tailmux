package dev.tailmux.exec;

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
        int quote = value.indexOf('\'');
        if (quote < 0) return "'" + value + "'";

        StringBuilder escaped = new StringBuilder(value.length() + 8).append('\'');
        int start = 0;
        while (quote >= 0) {
            escaped.append(value, start, quote).append("'\"'\"'");
            start = quote + 1;
            quote = value.indexOf('\'', start);
        }
        return escaped.append(value, start, value.length()).append('\'').toString();
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
