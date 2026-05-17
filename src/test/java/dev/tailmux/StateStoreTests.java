package dev.tailmux;

import dev.tailmux.core.NodeId;
import dev.tailmux.core.NodeSnapshot;
import dev.tailmux.core.NodeStatus;
import dev.tailmux.core.TailmuxException;
import dev.tailmux.core.TmuxSession;
import dev.tailmux.state.PropertiesStateStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

final class StateStoreTests extends TestMain {
    @Override
    void run() throws Exception {
        testStateRoundTrip();
        testSnapshotWindowTotalRoundTrip();
        testMalformedWorkspaceFailsClearly();
        testMalformedSnapshotCountFailsClearly();
        testMissingPaneFieldsRemainReadable();
        testWorkspaceWritesDeterministicKeys();
        testEventLogRedactsUnapprovedFields();
        testStateWritersAvoidStreamCollectors();
        testAtomicPropertyEscapeIsSinglePass();
        testSnapshotLoadListsUseStoredCounts();
        testEventLogSortsApprovedFieldsOnly();
        testEventLogFormatsTimestampOnce();
    }

    private void testStateRoundTrip() throws Exception {
        Path home = tempDir();
        PropertiesStateStore store = new PropertiesStateStore(home.resolve(".tailmux/state"));
        store.saveWorkspace("work", NodeId.parse("office-a"), "work", Instant.parse("2026-05-15T18:12:00Z"), Instant.parse("2026-05-15T19:02:13Z"));

        var loaded = store.loadWorkspace("work").orElseThrow();
        check(loaded.name().value().equals("work"), "workspace name round-trip");
        check(loaded.home().value().equals("office-a"), "workspace home round-trip");
        check(Files.exists(home.resolve(".tailmux/state/workspaces/work.properties")), "workspace file exists");
    }

    private void testMalformedWorkspaceFailsClearly() throws Exception {
        Path state = tempDir().resolve(".tailmux/state");
        Path file = state.resolve("workspaces/work.properties");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                version=1
                name=work
                session=work
                createdAt=not-an-instant
                lastSeenAt=2026-05-15T19:02:13Z
                """);

        TailmuxException error = expectTailmuxException(() -> new PropertiesStateStore(state).loadWorkspace("work"));
        check(error.getMessage().contains("FAIL state: malformed workspace"), "malformed workspace has clear prefix");
        check(error.getMessage().contains("work.properties"), "malformed workspace includes path");
    }

    private void testSnapshotWindowTotalRoundTrip() throws Exception {
        Path home = tempDir();
        PropertiesStateStore store = new PropertiesStateStore(home.resolve(".tailmux/state"));
        store.saveSnapshot(new NodeSnapshot(NodeId.parse("office-a"), NodeStatus.ONLINE, Instant.parse("2026-05-15T19:02:13Z"),
                List.of(new TmuxSession("default", "work", "$1", false, 1, 2, List.of(), 4))));

        var session = store.loadSnapshot(NodeId.parse("office-a")).orElseThrow().sessions().getFirst();

        check(session.windowCount() == 4, "snapshot window total round-trip");
    }

    private void testMalformedSnapshotCountFailsClearly() throws Exception {
        Path state = tempDir().resolve(".tailmux/state");
        Path file = state.resolve("snapshots/office-a.properties");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                version=1
                node=office-a
                status=ONLINE
                lastSeenAt=2026-05-15T19:02:13Z
                sessions.count=two
                """);

        TailmuxException error = expectTailmuxException(() -> new PropertiesStateStore(state).loadSnapshot(NodeId.parse("office-a")));
        check(error.getMessage().contains("FAIL state: malformed snapshot"), "malformed snapshot has clear prefix");
        check(error.getMessage().contains("sessions.count"), "malformed snapshot names bad key");
    }

    private void testMissingPaneFieldsRemainReadable() throws Exception {
        Path state = tempDir().resolve(".tailmux/state");
        Path file = state.resolve("snapshots/office-a.properties");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                version=1
                node=office-a
                status=ONLINE
                lastSeenAt=2026-05-15T19:02:13Z
                sessions.count=1
                sessions.0.name=work
                sessions.0.windows.count=1
                sessions.0.windows.0.index=0
                sessions.0.windows.0.panes.count=1
                """);

        var pane = new PropertiesStateStore(state).loadSnapshot(NodeId.parse("office-a")).orElseThrow()
                .sessions().getFirst().windows().getFirst().panes().getFirst();

        check(pane.index() == 0, "missing pane index defaults");
        check(pane.currentPath().isEmpty(), "missing pane cwd defaults");
        check(pane.currentCommand().isEmpty(), "missing pane command defaults");
    }

    private void testWorkspaceWritesDeterministicKeys() throws Exception {
        Path home = tempDir();
        new PropertiesStateStore(home.resolve(".tailmux/state")).saveWorkspace("work", NodeId.parse("office-a"), "work", "socket-a",
                Instant.parse("2026-05-15T18:12:00Z"), Instant.parse("2026-05-15T19:02:13Z"));

        String file = Files.readString(home.resolve(".tailmux/state/workspaces/work.properties"));
        check(file.equals("""
                createdAt=2026-05-15T18:12:00Z
                home=office-a
                lastSeenAt=2026-05-15T19:02:13Z
                name=work
                session=work
                socket=socket-a
                transport=ssh
                version=1
                """), "workspace keys are deterministic");
    }

    private void testEventLogRedactsUnapprovedFields() throws Exception {
        Path home = tempDir();
        PropertiesStateStore store = new PropertiesStateStore(home.resolve(".tailmux/state"));
        store.appendEvent(Instant.parse("2026-05-15T19:02:13Z"), "diagnostic",
                Map.of("command", "doctor", "node", "office-a\nbad", "stdout", "secret output", "token", "secret"));

        String log = Files.readString(home.resolve(".tailmux/state/events/2026-05-15.log"));

        check(log.contains("event=diagnostic"), "event log writes event type");
        check(log.contains("command=doctor"), "event log writes approved field");
        check(log.contains("node=office-a_bad"), "event log sanitizes newlines");
        check(!log.contains("secret"), "event log redacts unapproved fields");
        check(!log.contains("stdout"), "event log skips command output key");
    }

    private void testStateWritersAvoidStreamCollectors() throws Exception {
        for (String file : List.of(
                "src/main/java/dev/tailmux/state/AtomicFiles.java",
                "src/main/java/dev/tailmux/state/PropertiesStateStore.java")) {
            String source = Files.readString(Path.of(file));
            check(!source.contains(".stream()") && !source.contains("Collectors"), file + " avoids stream collectors");
        }
    }

    private void testAtomicPropertyEscapeIsSinglePass() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/tailmux/state/AtomicFiles.java"));
        check(!source.contains(".replace("), "atomic property escaping avoids replace chains");
    }

    private void testSnapshotLoadListsUseStoredCounts() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/tailmux/state/PropertiesStateStore.java"));
        check(source.contains("new ArrayList<>(sessionCount)"), "snapshot load pre-sizes sessions");
        check(source.contains("new ArrayList<>(windowCount)"), "snapshot load pre-sizes windows");
        check(source.contains("new ArrayList<>(paneCount)"), "snapshot load pre-sizes panes");
    }

    private void testEventLogSortsApprovedFieldsOnly() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/tailmux/state/PropertiesStateStore.java"));
        check(!source.contains("new ArrayList<>(fields.keySet())"), "event log copies approved fields only");
    }

    private void testEventLogFormatsTimestampOnce() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/tailmux/state/PropertiesStateStore.java"));
        check(count(source, "at.toString()") == 1, "event log formats timestamp once");
    }

    private int count(String source, String needle) {
        int count = 0;
        for (int index = source.indexOf(needle); index >= 0; index = source.indexOf(needle, index + needle.length())) count++;
        return count;
    }

    private TailmuxException expectTailmuxException(ThrowingRunnable runnable) {
        try {
            runnable.run();
            throw new AssertionError("expected TailmuxException");
        } catch (TailmuxException e) {
            return e;
        } catch (Throwable t) {
            throw new AssertionError("expected TailmuxException, got " + t, t);
        }
    }
}
