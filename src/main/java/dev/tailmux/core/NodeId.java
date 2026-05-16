package dev.tailmux.core;

import java.util.Objects;

public record NodeId(String value) {
    public NodeId {
        Objects.requireNonNull(value, "value");
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
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
}

