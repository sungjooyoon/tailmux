package dev.tailmux.tmux;

import dev.tailmux.core.NodeId;
import dev.tailmux.core.NodeSnapshot;
import dev.tailmux.core.NodeStatus;
import dev.tailmux.core.TmuxSession;
import dev.tailmux.core.TmuxPane;
import dev.tailmux.core.TmuxWindow;
import dev.tailmux.exec.ExecResult;
import dev.tailmux.text.Ascii;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.NoSuchElementException;

public final class TmuxParser {
    private static final char SEP_CHAR = '\u001F';

    private TmuxParser() {
    }

    public static NodeSnapshot parse(NodeId node, String socket, String sessionsOutput, String windowsOutput, Instant seenAt) {
        return parse(node, socket, sessionsOutput, windowsOutput, "", seenAt);
    }

    public static NodeSnapshot parse(NodeId node, String socket, String sessionsOutput, String windowsOutput, String panesOutput, Instant seenAt) {
        ArrayList<MutableSession> sessions = new ArrayList<>();
        boolean hasFullPaneRows = Ascii.hasText(panesOutput);
        for (Row row : rows(sessionsOutput)) {
            String name = required(row, "session");
            String id = required(row, "session");
            String attached = required(row, "session");
            String created = required(row, "session");
            String activity = required(row, "session");
            sessions.add(new MutableSession(
                    socket,
                    name,
                    id,
                    "1".equals(attached),
                    parseLong(created),
                    parseLong(activity),
                    row.hasNext() ? parseInt(row.next()) : 0
            ));
        }

        for (Row row : rows(windowsOutput)) {
            String sessionName = required(row, "window");
            int index = parseInt(required(row, "window"));
            String id = required(row, "window");
            String name = required(row, "window");
            boolean active = "1".equals(required(row, "window"));
            MutableSession session = session(sessions, sessionName);
            // tmux can report rows from stale/racing state; keep discovery useful by ignoring rows whose parent is absent.
            if (session != null) {
                MutableWindow window = new MutableWindow(index, id, name, active);
                if (!hasFullPaneRows && row.hasFields(4)) {
                    window.panes.add(new TmuxPane(parseInt(row.next()), row.next(), row.next(), row.next(), true));
                }
                session.windows.add(window);
            }
        }

        for (Row row : rows(panesOutput)) {
            String sessionName = required(row, "pane");
            int windowIndex = parseInt(required(row, "pane"));
            int paneIndex = parseInt(required(row, "pane"));
            String id = required(row, "pane");
            String cwd = required(row, "pane");
            String command = required(row, "pane");
            boolean active = "1".equals(required(row, "pane"));
            MutableSession session = session(sessions, sessionName);
            MutableWindow window = session == null ? null : session.window(windowIndex);
            // Same rule as windows: orphan pane rows are ignored, not promoted into phantom sessions.
            if (window != null) {
                window.panes.add(new TmuxPane(paneIndex, id, cwd, command, active));
            }
        }

        ArrayList<TmuxSession> parsed = new ArrayList<>(sessions.size());
        for (MutableSession session : sessions) parsed.add(session.toSession());
        return new NodeSnapshot(node, NodeStatus.ONLINE, seenAt, parsed);
    }

    private static MutableSession session(ArrayList<MutableSession> sessions, String name) {
        for (MutableSession session : sessions) {
            if (session.name.equals(name)) return session;
        }
        return null;
    }

    public static DiscoveryOutput splitDiscoveryOutput(String output) {
        if (output == null || output.isEmpty()) return new DiscoveryOutput("", "", "");
        int marker = output.indexOf(TmuxCommands.DISCOVERY_WINDOWS_MARKER);
        if (marker < 0) return new DiscoveryOutput(output, "", "");
        int windowsStart = marker + TmuxCommands.DISCOVERY_WINDOWS_MARKER.length();
        int paneMarker = output.indexOf(TmuxCommands.DISCOVERY_PANES_MARKER, windowsStart);
        if (paneMarker < 0) {
            return new DiscoveryOutput(Ascii.trimRight(output, 0, marker), Ascii.trimLeft(output, windowsStart, output.length()), "");
        }
        return new DiscoveryOutput(
                Ascii.trimRight(output, 0, marker),
                Ascii.trim(output, windowsStart, paneMarker),
                Ascii.trimLeft(output, paneMarker + TmuxCommands.DISCOVERY_PANES_MARKER.length(), output.length()));
    }

    public static boolean isNoServer(ExecResult result) {
        return TmuxFailure.noServer(result);
    }

    private static Iterable<Row> rows(String output) {
        if (output == null || output.isEmpty()) return () -> java.util.Collections.emptyIterator();
        return () -> new Iterator<>() {
            private int start;
            private Row next;
            private boolean ready;

            @Override
            public boolean hasNext() {
                prepare();
                return next != null;
            }

            @Override
            public Row next() {
                prepare();
                if (next == null) throw new NoSuchElementException();
                Row row = next;
                next = null;
                ready = false;
                return row;
            }

            private void prepare() {
                if (ready) return;
                ready = true;
                while (start <= output.length()) {
                    int rawEnd = output.indexOf('\n', start);
                    if (rawEnd < 0) rawEnd = output.length();
                    int end = rawEnd > start && output.charAt(rawEnd - 1) == '\r' ? rawEnd - 1 : rawEnd;
                    int lineStart = start;
                    start = rawEnd + 1;
                    if (!blank(output, lineStart, end)) {
                        next = new Row(output, lineStart, end);
                        return;
                    }
                    if (rawEnd == output.length()) break;
                }
                next = null;
            }
        };
    }

    private static boolean blank(String value, int start, int end) {
        for (int i = start; i < end; i++) {
            if (!Ascii.whitespace(value.charAt(i))) return false;
        }
        return true;
    }

    private static String required(Row row, String type) {
        if (!row.hasNext()) {
            throw new IllegalArgumentException("invalid tmux " + type + " row: " + printable(row));
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

    private static String printable(Row row) {
        StringBuilder printable = new StringBuilder(row.end - row.lineStart);
        for (int i = row.lineStart; i < row.end; i++) {
            char c = row.source.charAt(i);
            printable.append(c == SEP_CHAR ? '|' : c);
        }
        return printable.toString();
    }

    private static final class Row {
        private final String source;
        private final int lineStart;
        private final int end;
        private int start;
        private boolean available = true;

        private Row(String source, int start, int end) {
            this.source = source;
            this.lineStart = start;
            this.start = start;
            this.end = end;
        }

        private boolean hasNext() {
            return available;
        }

        private boolean hasFields(int fields) {
            if (!available) return false;
            int index = start;
            for (int seen = 1; seen < fields; seen++) {
                index = source.indexOf(SEP_CHAR, index);
                if (index < 0 || index >= end) return false;
                index++;
            }
            return true;
        }

        private String next() {
            int sep = source.indexOf(SEP_CHAR, start);
            if (sep < 0 || sep >= end) {
                available = false;
                return source.substring(start, end);
            }
            String value = source.substring(start, sep);
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

        private MutableWindow window(int index) {
            for (MutableWindow window : windows) {
                if (window.index == index) return window;
            }
            return null;
        }
    }

    public record DiscoveryOutput(String sessions, String windows, String panes) {
        public DiscoveryOutput(String sessions, String windows) {
            this(sessions, windows, "");
        }
    }
}
