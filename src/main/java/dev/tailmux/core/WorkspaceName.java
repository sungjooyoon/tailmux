package dev.tailmux.core;

import java.util.Objects;

public record WorkspaceName(String value) {
    public WorkspaceName {
        Objects.requireNonNull(value, "value");
        if (!NodeId.valid(value, false)) {
            throw new IllegalArgumentException("invalid workspace name: " + value);
        }
    }

    public static WorkspaceName parse(String value) {
        return new WorkspaceName(value.trim());
    }

    @Override
    public String toString() {
        return value;
    }
}
