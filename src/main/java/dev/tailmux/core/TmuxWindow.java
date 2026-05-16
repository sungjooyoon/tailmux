package dev.tailmux.core;

import java.util.List;

public record TmuxWindow(int index, String id, String name, boolean active, List<TmuxPane> panes) {
    public TmuxWindow {
        panes = panes == null ? List.of() : List.copyOf(panes);
    }

    public TmuxWindow(int index, String id, String name, boolean active) {
        this(index, id, name, active, List.of());
    }
}
