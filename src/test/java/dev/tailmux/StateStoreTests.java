package dev.tailmux;

import dev.tailmux.core.NodeId;
import dev.tailmux.state.PropertiesStateStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

final class StateStoreTests extends TestMain {
    @Override
    void run() throws Exception {
        testStateRoundTrip();
        testEventLogRedactsUnapprovedFields();
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
}
