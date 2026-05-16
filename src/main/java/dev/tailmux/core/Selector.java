package dev.tailmux.core;

import java.util.Optional;

public record Selector(NodeId node, String session, Optional<Integer> window, Optional<Integer> pane) {
    public Selector {
        if (session == null || session.isBlank()) {
            throw new IllegalArgumentException("selector session is required");
        }
        window = window == null ? Optional.empty() : window;
        pane = pane == null ? Optional.empty() : pane;
    }

    public static Selector parse(String value) {
        int colon = value.indexOf(':');
        if (colon <= 0 || colon == value.length() - 1) {
            throw new IllegalArgumentException("selector must be node:session");
        }
        NodeId node = NodeId.parse(value.substring(0, colon));
        String target = value.substring(colon + 1);
        String[] parts = target.split("\\.", -1);
        if (parts.length > 3 || parts[0].isBlank()) {
            throw new IllegalArgumentException("selector must be node:session[.window[.pane]]");
        }
        Optional<Integer> window = parts.length >= 2 ? Optional.of(parseIndex(parts[1], "window")) : Optional.empty();
        Optional<Integer> pane = parts.length == 3 ? Optional.of(parseIndex(parts[2], "pane")) : Optional.empty();
        return new Selector(node, parts[0], window, pane);
    }

    private static int parseIndex(String value, String label) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                throw new IllegalArgumentException(label + " index must be non-negative");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " index must be numeric: " + value, e);
        }
    }
}

