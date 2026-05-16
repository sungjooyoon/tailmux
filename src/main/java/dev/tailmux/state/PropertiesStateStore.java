package dev.tailmux.state;

import dev.tailmux.cli.ExitCodes;
import dev.tailmux.core.NodeId;
import dev.tailmux.core.NodeSnapshot;
import dev.tailmux.core.NodeStatus;
import dev.tailmux.core.TailmuxException;
import dev.tailmux.core.TmuxPane;
import dev.tailmux.core.TmuxSession;
import dev.tailmux.core.TmuxWindow;
import dev.tailmux.core.Workspace;
import dev.tailmux.core.WorkspaceName;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

public final class PropertiesStateStore {
    private static final Set<String> EVENT_FIELDS = Set.of("command", "node", "session", "socket", "status", "transport", "workspace");
    private final Path stateDir;

    public PropertiesStateStore(Path stateDir) {
        this.stateDir = stateDir;
    }

    public void ensureWritable() {
        try {
            Files.createDirectories(stateDir.resolve("workspaces"));
            Files.createDirectories(stateDir.resolve("snapshots"));
            Files.createDirectories(stateDir.resolve("events"));
        } catch (IOException e) {
            throw new TailmuxException(ExitCodes.CONFIG_ERROR, "FAIL state: could not create " + stateDir + ": " + e.getMessage(), e);
        }
    }

    public Optional<Workspace> loadWorkspace(String name) {
        Path path = stateDir.resolve("workspaces").resolve(name + ".properties");
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            Properties p = load(path);
            return Optional.of(new Workspace(
                    WorkspaceName.parse(p.getProperty("name", name)),
                    NodeId.parse(required(p, "home")),
                    p.getProperty("session", name),
                    p.getProperty("socket", "default"),
                    Instant.parse(required(p, "createdAt")),
                    Instant.parse(required(p, "lastSeenAt")),
                    p.getProperty("transport", "ssh")
            ));
        } catch (TailmuxException e) {
            throw e;
        } catch (RuntimeException e) {
            throw malformed("workspace", path, e);
        }
    }

    public void saveWorkspace(String name, NodeId home, String session, Instant createdAt, Instant lastSeenAt) {
        saveWorkspace(name, home, session, "default", createdAt, lastSeenAt);
    }

    public void saveWorkspace(String name, NodeId home, String session, String socket, Instant createdAt, Instant lastSeenAt) {
        Properties p = new Properties();
        p.setProperty("version", "1");
        p.setProperty("name", name);
        p.setProperty("home", home.value());
        p.setProperty("session", session);
        p.setProperty("socket", socket);
        p.setProperty("createdAt", createdAt.toString());
        p.setProperty("lastSeenAt", lastSeenAt.toString());
        p.setProperty("transport", "ssh");
        write(stateDir.resolve("workspaces").resolve(name + ".properties"), p);
    }

    public Optional<NodeSnapshot> loadSnapshot(NodeId node) {
        Path path = stateDir.resolve("snapshots").resolve(node.value() + ".properties");
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            Properties p = load(path);
            int sessionCount = countProperty(p, "sessions.count", 0);
            ArrayList<TmuxSession> sessions = new ArrayList<>();
            for (int i = 0; i < sessionCount; i++) {
                String prefix = "sessions." + i + ".";
                int windowCount = countProperty(p, prefix + "windows.count", 0);
                ArrayList<TmuxWindow> windows = new ArrayList<>();
                for (int w = 0; w < windowCount; w++) {
                    String wp = prefix + "windows." + w + ".";
                    int paneCount = countProperty(p, wp + "panes.count", 0);
                    ArrayList<TmuxPane> panes = new ArrayList<>();
                    for (int pane = 0; pane < paneCount; pane++) {
                        String pp = wp + "panes." + pane + ".";
                        panes.add(new TmuxPane(
                                intProperty(p, pp + "index", 0),
                                p.getProperty(pp + "id", ""),
                                p.getProperty(pp + "cwd", ""),
                                p.getProperty(pp + "command", ""),
                                Boolean.parseBoolean(p.getProperty(pp + "active", "false"))
                        ));
                    }
                    windows.add(new TmuxWindow(
                            intProperty(p, wp + "index", 0),
                            p.getProperty(wp + "id", ""),
                            p.getProperty(wp + "name", ""),
                            Boolean.parseBoolean(p.getProperty(wp + "active", "false")),
                            panes
                    ));
                }
                sessions.add(new TmuxSession(
                        p.getProperty(prefix + "socket", "default"),
                        p.getProperty(prefix + "name", ""),
                        p.getProperty(prefix + "id", ""),
                        Boolean.parseBoolean(p.getProperty(prefix + "attached", "false")),
                        longProperty(p, prefix + "created", 0L),
                        longProperty(p, prefix + "activity", 0L),
                        windows
                ));
            }
            NodeStatus status = NodeStatus.valueOf(p.getProperty("status", "ONLINE"));
            return Optional.of(new NodeSnapshot(node, status, Instant.parse(required(p, "lastSeenAt")), sessions));
        } catch (TailmuxException e) {
            throw e;
        } catch (RuntimeException e) {
            throw malformed("snapshot", path, e);
        }
    }

    public void saveSnapshot(NodeSnapshot snapshot) {
        Properties p = new Properties();
        p.setProperty("version", "1");
        p.setProperty("node", snapshot.node().value());
        p.setProperty("status", snapshot.status().name());
        p.setProperty("lastSeenAt", snapshot.lastSeenAt().toString());
        p.setProperty("sessions.count", Integer.toString(snapshot.sessions().size()));
        for (int i = 0; i < snapshot.sessions().size(); i++) {
            TmuxSession session = snapshot.sessions().get(i);
            String prefix = "sessions." + i + ".";
            p.setProperty(prefix + "socket", session.socket());
            p.setProperty(prefix + "name", session.name());
            p.setProperty(prefix + "id", session.id());
            p.setProperty(prefix + "attached", Boolean.toString(session.attached()));
            p.setProperty(prefix + "created", Long.toString(session.createdAtEpochSeconds()));
            p.setProperty(prefix + "activity", Long.toString(session.activityAtEpochSeconds()));
            p.setProperty(prefix + "windows.count", Integer.toString(session.windows().size()));
            for (int w = 0; w < session.windows().size(); w++) {
                TmuxWindow window = session.windows().get(w);
                String wp = prefix + "windows." + w + ".";
                p.setProperty(wp + "index", Integer.toString(window.index()));
                p.setProperty(wp + "id", window.id());
                p.setProperty(wp + "name", window.name());
                p.setProperty(wp + "active", Boolean.toString(window.active()));
                p.setProperty(wp + "panes.count", Integer.toString(window.panes().size()));
                for (int pane = 0; pane < window.panes().size(); pane++) {
                    TmuxPane tmuxPane = window.panes().get(pane);
                    String pp = wp + "panes." + pane + ".";
                    p.setProperty(pp + "index", Integer.toString(tmuxPane.index()));
                    p.setProperty(pp + "id", tmuxPane.id());
                    p.setProperty(pp + "cwd", tmuxPane.currentPath());
                    p.setProperty(pp + "command", tmuxPane.currentCommand());
                    p.setProperty(pp + "active", Boolean.toString(tmuxPane.active()));
                }
            }
        }
        write(stateDir.resolve("snapshots").resolve(snapshot.node().value() + ".properties"), p);
    }

    public void appendEvent(Instant at, String event, Map<String, String> fields) {
        try {
            Files.createDirectories(stateDir.resolve("events"));
            String line = "timestamp=" + clean(at.toString())
                    + " event=" + clean(event)
                    + fields.entrySet().stream()
                    .filter(entry -> EVENT_FIELDS.contains(entry.getKey()))
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> " " + entry.getKey() + "=" + clean(entry.getValue()))
                    .collect(Collectors.joining())
                    + "\n";
            Files.writeString(stateDir.resolve("events").resolve(at.toString().substring(0, 10) + ".log"),
                    line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new TailmuxException(ExitCodes.CONFIG_ERROR, "FAIL state: could not write event log: " + e.getMessage(), e);
        }
    }

    private Properties load(Path path) {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            p.load(in);
            return p;
        } catch (IOException e) {
            throw new TailmuxException(ExitCodes.CONFIG_ERROR, "FAIL state: could not read " + path + ": " + e.getMessage(), e);
        }
    }

    private void write(Path path, Properties properties) {
        try {
            AtomicFiles.writeProperties(path, properties);
        } catch (IOException e) {
            throw new TailmuxException(ExitCodes.CONFIG_ERROR, "FAIL state: could not write " + path + ": " + e.getMessage(), e);
        }
    }

    private static int intProperty(Properties p, String name, int fallback) {
        try {
            return Integer.parseInt(p.getProperty(name, Integer.toString(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int countProperty(Properties p, String name, int fallback) {
        String value = p.getProperty(name);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer", e);
        }
    }

    private static long longProperty(Properties p, String name, long fallback) {
        try {
            return Long.parseLong(p.getProperty(name, Long.toString(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n\\t]", "_");
    }

    private static String required(Properties p, String name) {
        String value = p.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static TailmuxException malformed(String type, Path path, RuntimeException cause) {
        return new TailmuxException(ExitCodes.CONFIG_ERROR,
                "FAIL state: malformed " + type + " " + path + ": " + cause.getMessage(), cause);
    }
}
