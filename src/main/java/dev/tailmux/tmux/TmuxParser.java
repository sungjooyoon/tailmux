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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

public final class TmuxParser {
    private static final String SEP = "\u001F";
    private static final char SEP_CHAR = '\u001F';

    private TmuxParser() {
    }

    public static NodeSnapshot parse(NodeId node, String socket, String sessionsOutput, String windowsOutput, Instant seenAt) {
        return parse(node, socket, sessionsOutput, windowsOutput, "", seenAt);
    }

    public static NodeSnapshot parse(NodeId node, String socket, String sessionsOutput, String windowsOutput, String panesOutput, Instant seenAt) {
        Map<String, MutableSession> sessions = new LinkedHashMap<>();
        Map<String, MutableWindow> windows = new LinkedHashMap<>();
        boolean hasFullPaneRows = panesOutput != null && !panesOutput.isBlank();
        for (String line : lines(sessionsOutput)) {
            Row row = new Row(line);
            String name = required(row, line, "session");
            String id = required(row, line, "session");
            String attached = required(row, line, "session");
            String created = required(row, line, "session");
            String activity = required(row, line, "session");
            sessions.put(name, new MutableSession(
                    socket,
                    name,
                    id,
                    "1".equals(attached),
                    parseLong(created),
                    parseLong(activity),
                    row.hasNext() ? parseInt(row.next()) : 0
            ));
        }

        for (String line : lines(windowsOutput)) {
            Row row = new Row(line);
            String sessionName = required(row, line, "window");
            int index = parseInt(required(row, line, "window"));
            String id = required(row, line, "window");
            String name = required(row, line, "window");
            boolean active = "1".equals(required(row, line, "window"));
            MutableSession session = sessions.get(sessionName);
            // tmux can report rows from stale/racing state; keep discovery useful by ignoring rows whose parent is absent.
            if (session != null) {
                MutableWindow window = new MutableWindow(index, id, name, active);
                if (!hasFullPaneRows && row.hasFields(4)) {
                    window.panes.add(new TmuxPane(parseInt(row.next()), row.next(), row.next(), row.next(), true));
                }
                session.windows.add(window);
                windows.put(windowKey(sessionName, window.index), window);
            }
        }

        for (String line : lines(panesOutput)) {
            Row row = new Row(line);
            String sessionName = required(row, line, "pane");
            int windowIndex = parseInt(required(row, line, "pane"));
            int paneIndex = parseInt(required(row, line, "pane"));
            String id = required(row, line, "pane");
            String cwd = required(row, line, "pane");
            String command = required(row, line, "pane");
            boolean active = "1".equals(required(row, line, "pane"));
            MutableWindow window = windows.get(windowKey(sessionName, windowIndex));
            // Same rule as windows: orphan pane rows are ignored, not promoted into phantom sessions.
            if (window != null) {
                window.panes.add(new TmuxPane(paneIndex, id, cwd, command, active));
            }
        }

        ArrayList<TmuxSession> parsed = new ArrayList<>(sessions.size());
        for (MutableSession session : sessions.values()) parsed.add(session.toSession());
        return new NodeSnapshot(node, NodeStatus.ONLINE, seenAt, parsed);
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

    private static Iterable<String> lines(String output) {
        if (output == null || output.isEmpty()) return () -> java.util.Collections.emptyIterator();
        return () -> new Iterator<>() {
            private int start;
            private String next;
            private boolean ready;

            @Override
            public boolean hasNext() {
                prepare();
                return next != null;
            }

            @Override
            public String next() {
                prepare();
                if (next == null) throw new NoSuchElementException();
                String line = next;
                next = null;
                ready = false;
                return line;
            }

            private void prepare() {
                if (ready) return;
                ready = true;
                while (start <= output.length()) {
                    int rawEnd = output.indexOf('\n', start);
                    if (rawEnd < 0) rawEnd = output.length();
                    int end = rawEnd > start && output.charAt(rawEnd - 1) == '\r' ? rawEnd - 1 : rawEnd;
                    String line = output.substring(start, end);
                    start = rawEnd + 1;
                    if (!line.isBlank()) {
                        next = line;
                        return;
                    }
                    if (rawEnd == output.length()) break;
                }
                next = null;
            }
        };
    }

    private static String required(Row row, String line, String type) {
        if (!row.hasNext()) {
            throw new IllegalArgumentException("invalid tmux " + type + " row: " + printable(line));
        }
        return row.next();
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
        return line.replace(SEP_CHAR, '|');
    }

    private static String windowKey(String session, int index) {
        return session + SEP + index;
    }

    private static final class Row {
        private final String line;
        private int start;
        private boolean available = true;

        private Row(String line) {
            this.line = line;
        }

        private boolean hasNext() {
            return available;
        }

        private boolean hasFields(int fields) {
            if (!available) return false;
            int index = start;
            for (int seen = 1; seen < fields; seen++) {
                index = line.indexOf(SEP_CHAR, index);
                if (index < 0) return false;
                index++;
            }
            return true;
        }

        private String next() {
            int sep = line.indexOf(SEP_CHAR, start);
            if (sep < 0) {
                available = false;
                return line.substring(start);
            }
            String value = line.substring(start, sep);
            start = sep + 1;
            return value;
        }
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
        private final int windowCount;
        private final ArrayList<MutableWindow> windows = new ArrayList<>();

        private MutableSession(String socket, String name, String id, boolean attached, long created, long activity, int windowCount) {
            this.socket = socket;
            this.name = name;
            this.id = id;
            this.attached = attached;
            this.created = created;
            this.activity = activity;
            this.windowCount = windowCount;
        }

        private TmuxSession toSession() {
            ArrayList<TmuxWindow> parsedWindows = new ArrayList<>(windows.size());
            for (MutableWindow window : windows) parsedWindows.add(window.toWindow());
            return new TmuxSession(socket, name, id, attached, created, activity, parsedWindows, windowCount);
        }
    }

    public record DiscoveryOutput(String sessions, String windows, String panes) {
        public DiscoveryOutput(String sessions, String windows) {
            this(sessions, windows, "");
        }
    }
}
