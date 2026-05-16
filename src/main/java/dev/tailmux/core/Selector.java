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
        int firstDot = target.indexOf('.');
        String session = firstDot < 0 ? target : target.substring(0, firstDot);
        if (session.isBlank()) {
            throw new IllegalArgumentException("selector must be node:session[.window[.pane]]");
        }
        if (firstDot < 0) return new Selector(node, session, Optional.empty(), Optional.empty());

        int secondDot = target.indexOf('.', firstDot + 1);
        if (secondDot < 0) {
            return new Selector(node, session, Optional.of(parseIndex(target.substring(firstDot + 1), "window")), Optional.empty());
        }
        if (target.indexOf('.', secondDot + 1) >= 0) {
            throw new IllegalArgumentException("selector must be node:session[.window[.pane]]");
        }
        return new Selector(node, session,
                Optional.of(parseIndex(target.substring(firstDot + 1, secondDot), "window")),
                Optional.of(parseIndex(target.substring(secondDot + 1), "pane")));
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
