package dev.tailmux.tmux;

import dev.tailmux.core.NodeId;
import dev.tailmux.core.NodeSnapshot;
import dev.tailmux.core.NodeStatus;
import dev.tailmux.core.TmuxSession;
import dev.tailmux.core.TmuxPane;
import dev.tailmux.core.TmuxWindow;
import dev.tailmux.exec.ExecResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TmuxParser {
    private static final String SEP = "\u001F";

    private TmuxParser() {
    }

    public static NodeSnapshot parse(NodeId node, String socket, String sessionsOutput, String windowsOutput, Instant seenAt) {
        return parse(node, socket, sessionsOutput, windowsOutput, "", seenAt);
    }

    public static NodeSnapshot parse(NodeId node, String socket, String sessionsOutput, String windowsOutput, String panesOutput, Instant seenAt) {
        Map<String, MutableSession> sessions = new LinkedHashMap<>();
        Map<String, MutableWindow> windows = new LinkedHashMap<>();
        for (String line : lines(sessionsOutput)) {
            String[] parts = line.split(SEP, -1);
            if (parts.length < 5) {
                throw new IllegalArgumentException("invalid tmux session row: " + printable(line));
            }
            sessions.put(parts[0], new MutableSession(
                    socket,
                    parts[0],
                    parts[1],
                    "1".equals(parts[2]),
                    parseLong(parts[3]),
                    parseLong(parts[4])
            ));
        }

        for (String line : lines(windowsOutput)) {
            String[] parts = line.split(SEP, -1);
            if (parts.length < 5) {
                throw new IllegalArgumentException("invalid tmux window row: " + printable(line));
            }
            MutableSession session = sessions.get(parts[0]);
            // tmux can report rows from stale/racing state; keep discovery useful by ignoring rows whose parent is absent.
            if (session != null) {
                MutableWindow window = new MutableWindow(parseInt(parts[1]), parts[2], parts[3], "1".equals(parts[4]));
                session.windows.add(window);
                windows.put(windowKey(parts[0], window.index), window);
            }
        }

        for (String line : lines(panesOutput)) {
            String[] parts = line.split(SEP, -1);
            if (parts.length < 7) {
                throw new IllegalArgumentException("invalid tmux pane row: " + printable(line));
            }
            MutableWindow window = windows.get(windowKey(parts[0], parseInt(parts[1])));
            // Same rule as windows: orphan pane rows are ignored, not promoted into phantom sessions.
            if (window != null) {
                window.panes.add(new TmuxPane(parseInt(parts[2]), parts[3], parts[4], parts[5], "1".equals(parts[6])));
            }
        }

        return new NodeSnapshot(node, NodeStatus.ONLINE, seenAt, sessions.values().stream().map(MutableSession::toSession).toList());
    }

    public static DiscoveryOutput splitDiscoveryOutput(String output) {
        if (output == null || output.isEmpty()) return new DiscoveryOutput("", "", "");
        int marker = output.indexOf(TmuxCommands.DISCOVERY_WINDOWS_MARKER);
        if (marker < 0) return new DiscoveryOutput(output, "", "");
        String sessions = output.substring(0, marker);
        String rest = output.substring(marker + TmuxCommands.DISCOVERY_WINDOWS_MARKER.length());
        int paneMarker = rest.indexOf(TmuxCommands.DISCOVERY_PANES_MARKER);
        if (paneMarker < 0) {
            return new DiscoveryOutput(sessions.stripTrailing(), rest.stripLeading(), "");
        }
        String windows = rest.substring(0, paneMarker);
        String panes = rest.substring(paneMarker + TmuxCommands.DISCOVERY_PANES_MARKER.length());
        return new DiscoveryOutput(sessions.stripTrailing(), windows.strip(), panes.stripLeading());
    }

    public static boolean isNoServer(ExecResult result) {
        return TmuxFailure.noServer(result);
    }

    private static List<String> lines(String output) {
        return output == null ? List.of() : output.lines().filter(line -> !line.isBlank()).toList();
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String printable(String line) {
        return line.replace(SEP, "|");
    }

    private static String windowKey(String session, int index) {
        return session + SEP + index;
    }

    private static final class MutableWindow {
        private final int index;
        private final String id;
        private final String name;
        private final boolean active;
        private final ArrayList<TmuxPane> panes = new ArrayList<>();

        private MutableWindow(int index, String id, String name, boolean active) {
            this.index = index;
            this.id = id;
            this.name = name;
            this.active = active;
        }

        private TmuxWindow toWindow() {
            return new TmuxWindow(index, id, name, active, panes);
        }
    }

    private static final class MutableSession {
        private final String socket;
        private final String name;
        private final String id;
        private final boolean attached;
        private final long created;
        private final long activity;
        private final ArrayList<MutableWindow> windows = new ArrayList<>();

        private MutableSession(String socket, String name, String id, boolean attached, long created, long activity) {
            this.socket = socket;
            this.name = name;
            this.id = id;
            this.attached = attached;
            this.created = created;
            this.activity = activity;
        }

        private TmuxSession toSession() {
            return new TmuxSession(socket, name, id, attached, created, activity, windows.stream().map(MutableWindow::toWindow).toList());
        }
    }

    public record DiscoveryOutput(String sessions, String windows, String panes) {
        public DiscoveryOutput(String sessions, String windows) {
            this(sessions, windows, "");
        }
    }
}
