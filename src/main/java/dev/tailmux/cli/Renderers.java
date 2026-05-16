package dev.tailmux.cli;

import dev.tailmux.config.NodeConfig;
import dev.tailmux.config.TailmuxConfig;
import dev.tailmux.core.NodeSnapshot;
import dev.tailmux.core.NodeStatus;
import dev.tailmux.core.TmuxPane;
import dev.tailmux.core.TmuxSession;
import dev.tailmux.core.TmuxWindow;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public final class Renderers {
    private Renderers() {
    }

    public static void renderLs(Console console, List<NodeSnapshot> snapshots, Clock clock, boolean showWindows, boolean showPanes) {
        console.out("NODE      STATUS     SESSION   WINDOWS   ATTACHED   LAST SEEN");
        for (NodeSnapshot snapshot : snapshots) {
            if (snapshot.sessions().isEmpty()) {
                console.out(row(snapshot.node().value(), snapshot.status().display(), "-", "-", "-", relative(snapshot.lastSeenAt(), clock)));
                continue;
            }
            for (TmuxSession session : snapshot.sessions()) {
                console.out(row(
                        snapshot.node().value(),
                        snapshot.status().display(),
                        session.name(),
                        Integer.toString(session.windows().size()),
                        session.attached() ? "yes" : "no",
                        relative(snapshot.lastSeenAt(), clock)
                ));
                if (showWindows) {
                    for (TmuxWindow window : session.windows()) {
                        TmuxPane active = activePane(window);
                        console.out("  " + snapshot.node().value() + ":" + session.name() + "." + window.index()
                                + "  " + window.name()
                                + (window.active() ? "  active" : "")
                                + "  " + valueOrDash(active.currentPath())
                                + "  " + valueOrDash(active.currentCommand()));
                        if (showPanes) {
                            for (TmuxPane pane : window.panes()) {
                                console.out("    " + snapshot.node().value() + ":" + session.name() + "." + window.index() + "." + pane.index()
                                        + "  " + valueOrDash(pane.currentPath())
                                        + "  " + valueOrDash(pane.currentCommand())
                                        + (pane.active() ? "  active" : ""));
                            }
                        }
                    }
                }
            }
        }
    }

    public static void renderNodes(Console console, TailmuxConfig config, List<NodeSnapshot> snapshots, Clock clock) {
        console.out("NODE      STATUS     ROLE       LAST SEEN");
        List<NodeConfig> nodes = config.nodeConfigs();
        for (int i = 0; i < nodes.size(); i++) {
            NodeConfig node = nodes.get(i);
            NodeSnapshot snapshot = i < snapshots.size() ? snapshots.get(i) : null;
            NodeStatus status = snapshot == null ? NodeStatus.UNKNOWN : snapshot.status();
            String role = node.id().equals(config.defaultHome()) ? "default" : "home";
            String seen = snapshot == null ? "-" : relative(snapshot.lastSeenAt(), clock);
            console.out(row(node.id().value(), status.display(), role, seen));
        }
    }

    private static String row(String a, String b, String c, String d) {
        return String.format("%-9s %-10s %-10s %s", a, b, c, d);
    }

    private static String row(String a, String b, String c, String d, String e, String f) {
        return String.format("%-9s %-10s %-9s %-9s %-10s %s", a, b, c, d, e, f);
    }

    private static TmuxPane activePane(TmuxWindow window) {
        return window.panes().stream().filter(TmuxPane::active).findFirst()
                .orElseGet(() -> window.panes().isEmpty() ? new TmuxPane(0, "", "", "", false) : window.panes().getFirst());
    }

    private static String valueOrDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    public static String relative(Instant instant, Clock clock) {
        Duration duration = Duration.between(instant, clock.instant());
        if (duration.isNegative() || duration.toSeconds() < 60) {
            return "now";
        }
        if (duration.toMinutes() < 60) {
            return duration.toMinutes() + "m";
        }
        if (duration.toHours() < 24) {
            return duration.toHours() + "h";
        }
        return duration.toDays() + "d";
    }
}
