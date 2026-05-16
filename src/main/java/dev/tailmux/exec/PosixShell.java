package dev.tailmux.exec;

import java.util.List;

public final class PosixShell {
    private PosixShell() {
    }

    public static String quote(String value) {
        if (value.isEmpty()) {
            return "''";
        }
        if (value.matches("[A-Za-z0-9_@%+=:,./-]+")) {
            return value;
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    public static String join(List<String> args) {
        StringBuilder builder = new StringBuilder();
        for (String arg : args) {
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(quote(arg));
        }
        return builder.toString();
    }
}

