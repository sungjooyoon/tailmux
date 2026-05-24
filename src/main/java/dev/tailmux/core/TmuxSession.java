package dev.tailmux.core;

import java.util.List;

public record TmuxSession(
        String socket,
        String name,
        String id,
        boolean attached,
        long createdAtEpochSeconds,
        long activityAtEpochSeconds,
        List<TmuxWindow> windows,
        int windowCount
) {
    public TmuxSession(String socket, String name, String id, boolean attached, long createdAtEpochSeconds, long activityAtEpochSeconds, List<TmuxWindow> windows) {
        this(socket, name, id, attached, createdAtEpochSeconds, activityAtEpochSeconds, windows, windows == null ? 0 : windows.size());
    }

    public TmuxSession {
        windows = windows == null ? List.of() : List.copyOf(windows);
        windowCount = Math.max(windowCount, windows.size());
    }
}
