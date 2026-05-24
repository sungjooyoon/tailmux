package dev.tailmux.core;

import dev.tailmux.text.Ascii;

import java.util.Objects;

public record WorkspaceName(String value) {
    public WorkspaceName {
        Objects.requireNonNull(value, "value");
        value = Ascii.trim(value);
        if (!NodeId.valid(value, false)) {
            throw new IllegalArgumentException("invalid workspace name: " + value);
        }
    }

    public static WorkspaceName parse(String value) {
        return new WorkspaceName(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
