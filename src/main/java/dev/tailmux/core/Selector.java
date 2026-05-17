package dev.tailmux.core;

import dev.tailmux.text.Ascii;

import java.util.Optional;

public record Selector(NodeId node, String session, Optional<Integer> window, Optional<Integer> pane) {
    public Selector {
        if (!Ascii.hasText(session)) {
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
        if (!Ascii.hasText(session)) {
            throw new IllegalArgumentException("selector must be node:session[.window[.pane]]");
        }
        if (firstDot < 0) return new Selector(node, session, Optional.empty(), Optional.empty());

        int secondDot = target.indexOf('.', firstDot + 1);
        if (secondDot < 0) {
            return new Selector(node, session, Optional.of(parseIndex(target, firstDot + 1, target.length(), "window")), Optional.empty());
        }
        if (target.indexOf('.', secondDot + 1) >= 0) {
            throw new IllegalArgumentException("selector must be node:session[.window[.pane]]");
        }
        return new Selector(node, session,
                Optional.of(parseIndex(target, firstDot + 1, secondDot, "window")),
                Optional.of(parseIndex(target, secondDot + 1, target.length(), "pane")));
    }

    private static int parseIndex(String value, int start, int end, String label) {
        if (start >= end) throw new IllegalArgumentException(label + " index must be numeric: ");
        int parsed = 0;
        for (int i = start; i < end; i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException(label + " index must be numeric: " + value.substring(start, end));
            }
            int digit = c - '0';
            if (parsed > (Integer.MAX_VALUE - digit) / 10) {
                throw new IllegalArgumentException(label + " index is too large");
            }
            parsed = parsed * 10 + digit;
        }
        return parsed;
    }
}
