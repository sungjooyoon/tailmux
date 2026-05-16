package dev.tailmux.core;

import java.util.List;

public record TmuxSession(
        String socket,
        String name,
        String id,
        boolean attached,
        long createdAtEpochSeconds,
        long activityAtEpochSeconds,
        List<TmuxWindow> windows
) {
    public TmuxSession {
        windows = List.copyOf(windows);
    }
}

