package dev.tailmux.core;

import java.util.Objects;

public record NodeId(String value) {
    public NodeId {
        Objects.requireNonNull(value, "value");
        if (!valid(value, true)) {
            throw new IllegalArgumentException("invalid node id: " + value);
        }
    }

    public static NodeId parse(String value) {
        return new NodeId(value.trim());
    }

    @Override
    public String toString() {
        return value;
    }

    static boolean valid(String value, boolean dotAllowed) {
        if (value.isEmpty() || !alnum(value.charAt(0))) return false;
        for (int i = 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!alnum(c) && c != '_' && c != '-' && (!dotAllowed || c != '.')) return false;
        }
        return true;
    }

    private static boolean alnum(char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9';
    }
}
