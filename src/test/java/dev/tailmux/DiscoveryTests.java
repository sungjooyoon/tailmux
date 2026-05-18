package dev.tailmux;

import dev.tailmux.cli.CommandRouter;
import dev.tailmux.cli.ExitCodes;
import dev.tailmux.config.TailmuxConfig;
import dev.tailmux.core.NodeId;
import dev.tailmux.exec.ExecResult;
import dev.tailmux.exec.FakeRemoteExecutor;
import dev.tailmux.state.PropertiesStateStore;
import dev.tailmux.tmux.TmuxCommands;
import dev.tailmux.tmux.TmuxParser;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Properties;

final class DiscoveryTests extends TestMain {
    @Override
    void run() throws Exception {
        testOfflineCachedRendering();
        testTimeoutDoesNotRenderAsNoTmux();
        testTimeoutWritesOfflineSnapshot();
        testRecentOfflineSnapshotSkipsProbe();
        testListUsesSingleDiscoveryCommandPerNode();
        testListScansConfiguredSockets();
        testListDiscoversNodesConcurrently();
        testDiscoveryWorkerFailureIsReported();
        testDiscoveryLoadsCachedSnapshotOnce();
        testDiscoveryDoesNotCopyInternalResults();
        testDiscoveryFallbackAvoidsOptionalPipelines();
        testFailureSnapshotReadsCacheOnce();
        testDiscoveryInternalCacheIsNullable();
    }

    private void testOfflineCachedRendering() throws Exception {
        var home = tempDir();
        PropertiesStateStore store = new PropertiesStateStore(home.resolve(".tailmux/state"));
        store.saveSnapshot(TmuxParser.parse(NodeId.parse("office-a"), "default",
                "modal\u001F\u00241\u001F0\u001F1\u001F2\n",
                "modal\u001F0\u001F@1\u001Feditor\u001F1\n",
                Instant.parse("2026-05-15T19:02:13Z")));

        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.failNode("office-a", ExecResult.failure(255, "", "ssh failed"));

        CapturingConsole console = new CapturingConsole();
        CommandRouter router = new CommandRouter(configWithOneNode(), store, remote,
                Clock.fixed(Instant.parse("2026-05-15T22:02:13Z"), ZoneOffset.UTC), console);
        int exit = router.run(List.of("ls"));

        check(exit == ExitCodes.SUCCESS, "ls with cached offline state succeeds");
        check(console.out().contains("office-a"), "renders offline node");
        check(console.out().contains("offline"), "renders offline status");
        check(console.out().contains("modal"), "renders cached session");
        check(console.out().contains("3h"), "renders relative last seen");
    }

    private void testTimeoutDoesNotRenderAsNoTmux() throws Exception {
        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.when("office-a", TmuxCommands.listSessions("default"), ExecResult.failure(124, "", "command timed out after 10s"));

        CapturingConsole console = new CapturingConsole();
        int exit = new CommandRouter(configWithOneNode(), new PropertiesStateStore(tempDir().resolve(".tailmux/state")), remote,
                Clock.fixed(Instant.parse("2026-05-15T19:02:13Z"), ZoneOffset.UTC), console)
                .run(List.of("nodes"));

        check(exit == ExitCodes.SUCCESS, "nodes exits success for timeout");
        check(console.out().contains("offline"), "timeout renders offline");
        check(!console.out().contains("no_tmux"), "timeout does not render no_tmux");
    }

    private void testTimeoutWritesOfflineSnapshot() throws Exception {
        var home = tempDir();
        PropertiesStateStore store = new PropertiesStateStore(home.resolve(".tailmux/state"));
        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.when("office-a", TmuxCommands.listSessions("default"), ExecResult.failure(124, "", "command timed out after 10s"));

        int exit = new CommandRouter(configWithOneNode(), store, remote,
                Clock.fixed(Instant.parse("2026-05-15T19:02:13Z"), ZoneOffset.UTC), new CapturingConsole())
                .run(List.of("nodes"));

        check(exit == ExitCodes.SUCCESS, "timeout nodes exits success");
        check(store.loadSnapshot(NodeId.parse("office-a")).status().name().equals("SSH_FAILED"), "timeout snapshot saved as ssh_failed");
    }

    private void testRecentOfflineSnapshotSkipsProbe() throws Exception {
        var home = tempDir();
        PropertiesStateStore store = new PropertiesStateStore(home.resolve(".tailmux/state"));
        store.saveSnapshot(new dev.tailmux.core.NodeSnapshot(
                NodeId.parse("office-a"),
                dev.tailmux.core.NodeStatus.SSH_FAILED,
                Instant.parse("2026-05-15T19:01:45Z"),
                List.of()));
        FakeRemoteExecutor remote = new FakeRemoteExecutor();

        int exit = new CommandRouter(configWithOneNode(), store, remote,
                Clock.fixed(Instant.parse("2026-05-15T19:02:13Z"), ZoneOffset.UTC), new CapturingConsole())
                .run(List.of("ls", "--windows"));

        check(exit == ExitCodes.SUCCESS, "recent offline cache exits success");
        check(remote.commandsFor("office-a").isEmpty(), "recent offline cache skips remote probe");
    }

    private void testListUsesSingleDiscoveryCommandPerNode() throws Exception {
        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        String output = "work\u001F\u00241\u001F0\u001F1\u001F2\n"
                + TmuxCommands.DISCOVERY_WINDOWS_MARKER + "\n"
                + "work\u001F0\u001F@1\u001Feditor\u001F1\n";
        remote.when("office-a", TmuxCommands.discoverWindows("default"), ExecResult.success(output));

        int exit = new CommandRouter(configWithOneNode(), new PropertiesStateStore(tempDir().resolve(".tailmux/state")), remote,
                Clock.fixed(Instant.parse("2026-05-15T19:02:13Z"), ZoneOffset.UTC), new CapturingConsole())
                .run(List.of("ls", "--windows"));

        check(exit == ExitCodes.SUCCESS, "single discovery exits success");
        check(remote.commandsFor("office-a").equals(List.of(TmuxCommands.discoverWindows("default"))), "ls --windows uses one discovery command");
    }

    private void testListScansConfiguredSockets() throws Exception {
        Properties p = new Properties();
        p.setProperty("tailmux.home.pool", "office-a");
        p.setProperty("tailmux.node.office-a.sockets", "default,work");
        TailmuxConfig config = TailmuxConfig.fromProperties(p);
        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.when("office-a", TmuxCommands.listSessions("default"), ExecResult.failure(1, "", "no server running"));
        remote.when("office-a", TmuxCommands.listSessions("work"), ExecResult.success("modal\u001F\u00242\u001F0\u001F1\u001F2\n"));
        remote.when("office-a", TmuxCommands.listWindows("work"), ExecResult.success("modal\u001F0\u001F@1\u001Fshell\u001F1\n"));
        remote.when("office-a", TmuxCommands.listPanes("work"), ExecResult.success("modal\u001F0\u001F0\u001F%1\u001F/tmp\u001Fzsh\u001F1\n"));

        CapturingConsole console = new CapturingConsole();
        int exit = new CommandRouter(config, new PropertiesStateStore(tempDir().resolve(".tailmux/state")), remote,
                Clock.fixed(Instant.parse("2026-05-15T19:02:13Z"), ZoneOffset.UTC), console)
                .run(List.of("ls", "--windows"));

        check(exit == ExitCodes.SUCCESS, "multi-socket ls exits success");
        check(console.out().contains("modal"), "multi-socket ls discovers non-default socket session");
        check(remote.commandsFor("office-a").contains(TmuxCommands.discoverWindows("default")), "multi-socket ls probes default socket");
        check(remote.commandsFor("office-a").contains(TmuxCommands.discoverWindows("work")), "multi-socket ls probes work socket");
    }

    private void testListDiscoversNodesConcurrently() throws Exception {
        Properties p = new Properties();
        p.setProperty("tailmux.home.pool", "office-a,office-b");
        TailmuxConfig config = TailmuxConfig.fromProperties(p);
        DelayingRemoteExecutor remote = new DelayingRemoteExecutor(Duration.ofMillis(500));

        long started = System.nanoTime();
        int exit = new CommandRouter(config, new PropertiesStateStore(tempDir().resolve(".tailmux/state")), remote,
                Clock.fixed(Instant.parse("2026-05-15T19:02:13Z"), ZoneOffset.UTC), new CapturingConsole())
                .run(List.of("ls", "--windows"));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        check(exit == ExitCodes.SUCCESS, "concurrent discovery exits success");
        check(elapsedMillis < 900, "node discovery runs concurrently");
    }

    private void testDiscoveryWorkerFailureIsReported() throws Exception {
        CapturingConsole console = new CapturingConsole();
        int exit = new CommandRouter(configWithOneNode(), new PropertiesStateStore(tempDir().resolve(".tailmux/state")), new ThrowingRemoteExecutor(),
                Clock.fixed(Instant.parse("2026-05-15T19:02:13Z"), ZoneOffset.UTC), console)
                .run(List.of("ls"));

        check(exit == ExitCodes.GENERAL_FAILURE, "discovery worker failure exits general failure");
        check(console.err().contains("FAIL discovery: worker exploded"), "discovery worker failure preserves cause");
    }

    private void testDiscoveryLoadsCachedSnapshotOnce() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/dev/tailmux/cli/DiscoveryService.java"));
        check(source.indexOf("store.loadSnapshot(") == source.lastIndexOf("store.loadSnapshot("), "discovery loads cached snapshot once per node path");
    }

    private void testDiscoveryDoesNotCopyInternalResults() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/dev/tailmux/cli/DiscoveryService.java"));
        check(!source.contains("List.copyOf(discovered)"), "discovery does not copy internal parallel results before immediate consumption");
    }

    private void testDiscoveryFallbackAvoidsOptionalPipelines() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/dev/tailmux/cli/DiscoveryService.java"));
        check(!source.contains(".map(") && !source.contains(".filter(") && !source.contains(".orElseGet("), "discovery fallback paths are explicit branches");
    }

    private void testFailureSnapshotReadsCacheOnce() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/dev/tailmux/cli/DiscoveryService.java"));
        int method = source.indexOf("private NodeSnapshot failureSnapshot");
        int next = source.indexOf("private NodeSnapshot save", method);
        String body = source.substring(method, next);
        check(count(body, "cached.sessions()") == 1, "failure snapshot reads nullable cache once");
    }

    private void testDiscoveryInternalCacheIsNullable() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/dev/tailmux/cli/DiscoveryService.java"));
        check(!source.contains("Optional<NodeSnapshot>") && !source.contains("cached.get()") && !source.contains("cached.isPresent()"), "discovery unwraps cached optional at boundary");
    }

    private int count(String source, String needle) {
        int count = 0;
        for (int index = source.indexOf(needle); index >= 0; index = source.indexOf(needle, index + needle.length())) count++;
        return count;
    }
}
