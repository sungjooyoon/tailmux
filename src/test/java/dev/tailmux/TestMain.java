package dev.tailmux;

import dev.tailmux.cli.CommandRouter;
import dev.tailmux.cli.ExitCodes;
import dev.tailmux.config.TailmuxConfig;
import dev.tailmux.config.NodeConfig;
import dev.tailmux.core.NodeId;
import dev.tailmux.core.Selector;
import dev.tailmux.core.WorkspaceName;
import dev.tailmux.exec.ExecResult;
import dev.tailmux.exec.FakeRemoteExecutor;
import dev.tailmux.exec.LocalProcess;
import dev.tailmux.exec.PosixShell;
import dev.tailmux.exec.RemoteExecutor;
import dev.tailmux.state.PropertiesStateStore;
import dev.tailmux.tmux.TmuxCommands;
import dev.tailmux.tmux.TmuxParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public final class TestMain {
    private int passed;
    private int failed;

    public static void main(String[] args) throws Exception {
        TestMain tests = new TestMain();
        tests.runAll();
        if (tests.failed > 0) {
            throw new AssertionError(tests.failed + " test(s) failed");
        }
        System.out.println("PASS " + tests.passed + " tests");
    }

    private void runAll() throws Exception {
        testWorkspaceNameValidation();
        testSelectorParsing();
        testShellQuoting();
        testConfigDefaults();
        testConfigRequiresHomePool();
        testPerNodeUserOverridesGlobalUser();
        testLocalProcessTimeout();
        testLocalProcessDefaultTimeoutFailsFast();
        testLocalProcessLargeOutputDoesNotDeadlock();
        TmuxParserTests.run(this);
        testStateRoundTrip();
        testEventLogRedactsUnapprovedFields();
        testCommandRouting();
        testWorkspaceCreatesOnDefaultHome();
        testRegistryOwnerUnreachableDoesNotCreateDuplicate();
        testDuplicateDiscoveredWorkspaceFails();
        testDiscoveredWorkspaceRemembersSocket();
        testRegisteredWorkspaceUsesStoredSocket();
        testWorkspaceAttachWritesEventMetadata();
        testOfflineCachedRendering();
        testTimeoutDoesNotRenderAsNoTmux();
        testTimeoutWritesOfflineSnapshot();
        testRecentOfflineSnapshotSkipsProbe();
        testListUsesSingleDiscoveryCommandPerNode();
        testListScansConfiguredSockets();
        testListDiscoversNodesConcurrently();
        testListRendersLiveWindowCounts();
        testListWindowsRendersActivePaneMetadata();
        testListPanesRendersPaneRows();
        testAttachPaneSelectsWindowAndPane();
        testDoctorClassification();
        testDoctorClassifiesMagicDnsFailure();
        testDoctorNetworkUsesSafeReadOnlyProbes();
    }

    private void testWorkspaceNameValidation() {
        check("work".equals(WorkspaceName.parse("work").value()), "accepts simple workspace");
        check("modal_2".equals(WorkspaceName.parse("modal_2").value()), "accepts underscore and digit");
        expectThrows(IllegalArgumentException.class, () -> WorkspaceName.parse("-bad"), "rejects leading punctuation");
        expectThrows(IllegalArgumentException.class, () -> WorkspaceName.parse("bad.name"), "rejects dots for managed workspaces");
    }

    private void testSelectorParsing() {
        Selector selector = Selector.parse("office-a:work");
        check(selector.node().value().equals("office-a"), "selector node");
        check(selector.session().equals("work"), "selector session");
        check(selector.window().isEmpty(), "selector without window");

        Selector window = Selector.parse("office-a:work.2");
        check(window.window().orElseThrow() == 2, "selector window");

        Selector pane = Selector.parse("office-a:work.2.1");
        check(pane.window().orElseThrow() == 2, "selector pane window");
        check(pane.pane().orElseThrow() == 1, "selector pane");

        expectThrows(IllegalArgumentException.class, () -> Selector.parse("work"), "selector requires node");
        expectThrows(IllegalArgumentException.class, () -> Selector.parse("office-a:work.two"), "selector rejects nonnumeric window");
    }

    private void testShellQuoting() {
        check(PosixShell.quote("plain").equals("plain"), "plain word unquoted");
        check(PosixShell.quote("two words").equals("'two words'"), "spaces quoted");
        check(PosixShell.quote("a'b").equals("'a'\"'\"'b'"), "single quote escaped");
        check(PosixShell.join(List.of("tmux", "new-session", "-d", "-s", "work space"))
                .equals("tmux new-session -d -s 'work space'"), "join quotes only needed args");
    }

    private void testConfigDefaults() throws Exception {
        Path home = tempDir();
        Path configFile = home.resolve(".tailmux/config.properties");
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, """
                tailmux.home.pool=office-a, office-b
                tailmux.node.office-a.host=office-a.tailnet.ts.net
                """);

        TailmuxConfig config = TailmuxConfig.load(home);
        check(config.homePool().size() == 2, "loads home pool");
        check(config.defaultHome().value().equals("office-a"), "defaults home to first pool node");
        check(config.node(NodeId.parse("office-a")).host().equals("office-a.tailnet.ts.net"), "configured host");
        check(config.node(NodeId.parse("office-b")).host().equals("office-b"), "host defaults to node id");
        check(config.node(NodeId.parse("office-b")).sockets().equals(List.of("default")), "sockets default");
    }

    private void testConfigRequiresHomePool() {
        expectThrows(dev.tailmux.core.TailmuxException.class, () -> TailmuxConfig.fromProperties(new Properties()), "home pool required");
    }

    private void testPerNodeUserOverridesGlobalUser() {
        Properties p = new Properties();
        p.setProperty("tailmux.user", "sungjooyoon");
        p.setProperty("tailmux.home.pool", "sungjoos-mac-pro,sungjoos-mac-studio");
        p.setProperty("tailmux.node.sungjoos-mac-studio.user", "sjy2");

        TailmuxConfig config = TailmuxConfig.fromProperties(p);

        check(config.sshTarget(config.node(NodeId.parse("sungjoos-mac-pro"))).equals("sungjooyoon@sungjoos-mac-pro"), "global user applies by default");
        check(config.sshTarget(config.node(NodeId.parse("sungjoos-mac-studio"))).equals("sjy2@sungjoos-mac-studio"), "node user overrides global user");
    }

    private void testLocalProcessTimeout() throws Exception {
        ExecResult result = new LocalProcess().capture(List.of("sh", "-lc", "sleep 2"), Duration.ofMillis(50));
        check(result.exitCode() == 124, "timeout exit code");
        check(result.stderr().contains("timed out"), "timeout error text");
    }

    private void testLocalProcessDefaultTimeoutFailsFast() throws Exception {
        ExecResult result = new LocalProcess().capture(List.of("sh", "-lc", "sleep 4"));
        check(result.exitCode() == 124, "default timeout exit code");
    }

    private void testLocalProcessLargeOutputDoesNotDeadlock() throws Exception {
        ExecResult result = new LocalProcess().capture(List.of("sh", "-lc", "yes x | head -n 200000"), Duration.ofSeconds(5));
        check(result.exitCode() == 0, "large output command exits successfully");
        check(result.stdout().length() > 200000, "large output is captured");
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

    private void testCommandRouting() throws Exception {
        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        TailmuxConfig config = configWithPool();
        CommandRouter router = router(config, remote, tempDir());

        check(router.classify(List.of("ls")).command().equals("ls"), "routes builtin");
        check(router.classify(List.of("work")).command().equals("workspace"), "routes shorthand");
        check(router.classify(List.of("start", "work", "--home", "office-b")).command().equals("start"), "routes start");
    }

    private void testWorkspaceCreatesOnDefaultHome() throws Exception {
        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.when("office-a", "command -v tmux", ExecResult.success("/opt/homebrew/bin/tmux\n"));
        remote.when("office-a", "tmux -L default list-sessions -F '#{session_name}\\037#{session_id}\\037#{session_attached}\\037#{session_created}\\037#{session_activity}'",
                ExecResult.failure(1, "", "no server running on /tmp/tmux"));
        remote.when("office-a", "tmux -L default list-windows -a -F '#{session_name}\\037#{window_index}\\037#{window_id}\\037#{window_name}\\037#{window_active}'",
                ExecResult.success(""));
        remote.when("office-a", "tmux -L default has-session -t work", ExecResult.failure(1, "", "can't find session"));
        remote.when("office-a", "tmux -L default new-session -d -s work", ExecResult.success(""));

        Path home = tempDir();
        CommandRouter router = router(configWithPool(), remote, home);
        int exit = router.run(List.of("start", "work"));

        check(exit == ExitCodes.SUCCESS, "start exits success");
        check(remote.interactiveCommands().equals(List.of("office-a:tmux -L default attach-session -t work")), "interactive attach command");
        check(Files.exists(home.resolve(".tailmux/state/workspaces/work.properties")), "workspace registered");
    }

    private void testRegistryOwnerUnreachableDoesNotCreateDuplicate() throws Exception {
        Path home = tempDir();
        PropertiesStateStore store = new PropertiesStateStore(home.resolve(".tailmux/state"));
        store.saveWorkspace("work", NodeId.parse("office-a"), "work", Instant.now(), Instant.now());

        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.failNode("office-a", ExecResult.failure(255, "", "ssh failed"));
        remote.when("office-b", "command -v tmux", ExecResult.success("/opt/homebrew/bin/tmux\n"));

        int exit = router(configWithPool(), remote, home).run(List.of("work"));
        check(exit == ExitCodes.REMOTE_EXECUTION_ERROR, "unreachable owner exits remote error");
        check(remote.interactiveCommands().isEmpty(), "does not attach elsewhere");
        check(remote.commandsFor("office-b").isEmpty(), "does not probe alternate node after registered owner failure");
    }

    private void testDuplicateDiscoveredWorkspaceFails() throws Exception {
        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        for (String node : List.of("office-a", "office-b")) {
            remote.when(node, "command -v tmux", ExecResult.success("/opt/homebrew/bin/tmux\n"));
            remote.when(node, "tmux -L default list-sessions -F '#{session_name}\\037#{session_id}\\037#{session_attached}\\037#{session_created}\\037#{session_activity}'",
                    ExecResult.success("work\u001F\u00241\u001F0\u001F1\u001F2\n"));
            remote.when(node, "tmux -L default list-windows -a -F '#{session_name}\\037#{window_index}\\037#{window_id}\\037#{window_name}\\037#{window_active}'",
                    ExecResult.success(""));
        }

        int exit = router(configWithPool(), remote, tempDir()).run(List.of("work"));
        check(exit == ExitCodes.CONFIG_ERROR, "duplicate discovered workspace fails");
        check(remote.interactiveCommands().isEmpty(), "does not attach ambiguous workspace");
    }

    private void testDiscoveredWorkspaceRemembersSocket() throws Exception {
        Properties p = new Properties();
        p.setProperty("tailmux.home.pool", "office-a");
        p.setProperty("tailmux.node.office-a.sockets", "default,work");
        TailmuxConfig config = TailmuxConfig.fromProperties(p);
        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.when("office-a", TmuxCommands.listSessions("default"), ExecResult.failure(1, "", "no server running"));
        remote.when("office-a", TmuxCommands.listSessions("work"), ExecResult.success("modal\u001F\u00242\u001F0\u001F1\u001F2\n"));
        remote.when("office-a", TmuxCommands.listWindows("work"), ExecResult.success("modal\u001F0\u001F@1\u001Fshell\u001F1\n"));
        remote.when("office-a", TmuxCommands.listPanes("work"), ExecResult.success(""));

        Path home = tempDir();
        int exit = router(config, remote, home).run(List.of("modal"));

        check(exit == ExitCodes.SUCCESS, "discovered socket workspace exits success");
        check(remote.interactiveCommands().equals(List.of("office-a:tmux -L work attach-session -t modal")), "discovered workspace attaches to matching socket");
        check(new PropertiesStateStore(home.resolve(".tailmux/state")).loadWorkspace("modal").orElseThrow().socket().equals("work"), "discovered workspace stores socket");
    }

    private void testRegisteredWorkspaceUsesStoredSocket() throws Exception {
        Path home = tempDir();
        PropertiesStateStore store = new PropertiesStateStore(home.resolve(".tailmux/state"));
        store.saveWorkspace("modal", NodeId.parse("office-a"), "modal", "work", Instant.now(), Instant.now());

        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.when("office-a", "command -v tmux", ExecResult.success("/opt/homebrew/bin/tmux\n"));
        remote.when("office-a", TmuxCommands.hasSession("work", "modal"), ExecResult.success(""));

        int exit = router(configWithOneNode(), remote, home).run(List.of("modal"));

        check(exit == ExitCodes.SUCCESS, "registered socket workspace exits success");
        check(remote.interactiveCommands().equals(List.of("office-a:tmux -L work attach-session -t modal")), "registered workspace uses stored socket");
    }

    private void testWorkspaceAttachWritesEventMetadata() throws Exception {
        Path home = tempDir();
        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.when("office-a", "command -v tmux", ExecResult.success("/opt/homebrew/bin/tmux\n"));
        remote.when("office-a", TmuxCommands.listSessions("default"), ExecResult.failure(1, "", "no server running"));
        remote.when("office-a", TmuxCommands.listWindows("default"), ExecResult.success(""));
        remote.when("office-a", TmuxCommands.hasSession("default", "work"), ExecResult.failure(1, "", "missing"));
        remote.when("office-a", TmuxCommands.newSession("default", "work"), ExecResult.success(""));

        int exit = router(configWithOneNode(), remote, home).run(List.of("work"));
        String log = Files.readString(home.resolve(".tailmux/state/events/2026-05-15.log"));

        check(exit == ExitCodes.SUCCESS, "workspace event attach exits success");
        check(log.contains("event=workspace"), "workspace event logged");
        check(log.contains("workspace=work"), "workspace event logs workspace");
        check(log.contains("node=office-a"), "workspace event logs node");
        check(log.contains("socket=default"), "workspace event logs socket");
        check(log.contains("session=work"), "workspace event logs session");
    }

    private void testOfflineCachedRendering() throws Exception {
        Path home = tempDir();
        PropertiesStateStore store = new PropertiesStateStore(home.resolve(".tailmux/state"));
        store.saveSnapshot(TmuxParser.parse(NodeId.parse("office-a"), "default",
                "modal\u001F\u00241\u001F0\u001F1\u001F2\n",
                "modal\u001F0\u001F@1\u001Feditor\u001F1\n",
                Instant.parse("2026-05-15T19:02:13Z")));

        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.failNode("office-a", ExecResult.failure(255, "", "ssh failed"));

        CapturingConsole console = new CapturingConsole();
        CommandRouter router = new CommandRouter(configWithOneNode(), store, remote, Clock.fixed(Instant.parse("2026-05-15T22:02:13Z"), ZoneOffset.UTC), console);
        int exit = router.run(List.of("ls"));

        check(exit == ExitCodes.SUCCESS, "ls with cached offline state succeeds");
        check(console.out().contains("office-a"), "renders offline node");
        check(console.out().contains("offline"), "renders offline status");
        check(console.out().contains("modal"), "renders cached session");
        check(console.out().contains("3h"), "renders relative last seen");
    }

    private void testTimeoutDoesNotRenderAsNoTmux() throws Exception {
        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.when("office-a", "command -v tmux", ExecResult.failure(124, "", "command timed out after 10s"));

        CapturingConsole console = new CapturingConsole();
        int exit = new CommandRouter(configWithOneNode(), new PropertiesStateStore(tempDir().resolve(".tailmux/state")), remote,
                Clock.fixed(Instant.parse("2026-05-15T19:02:13Z"), ZoneOffset.UTC), console)
                .run(List.of("nodes"));

        check(exit == ExitCodes.SUCCESS, "nodes exits success for timeout");
        check(console.out().contains("offline"), "timeout renders offline");
        check(!console.out().contains("no_tmux"), "timeout does not render no_tmux");
    }

    private void testTimeoutWritesOfflineSnapshot() throws Exception {
        Path home = tempDir();
        PropertiesStateStore store = new PropertiesStateStore(home.resolve(".tailmux/state"));
        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.when("office-a", "command -v tmux", ExecResult.failure(124, "", "command timed out after 10s"));

        int exit = new CommandRouter(configWithOneNode(), store, remote,
                Clock.fixed(Instant.parse("2026-05-15T19:02:13Z"), ZoneOffset.UTC), new CapturingConsole())
                .run(List.of("nodes"));

        check(exit == ExitCodes.SUCCESS, "timeout nodes exits success");
        check(store.loadSnapshot(NodeId.parse("office-a")).orElseThrow().status().name().equals("SSH_FAILED"), "timeout snapshot saved as ssh_failed");
    }

    private void testRecentOfflineSnapshotSkipsProbe() throws Exception {
        Path home = tempDir();
        PropertiesStateStore store = new PropertiesStateStore(home.resolve(".tailmux/state"));
        store.saveSnapshot(new dev.tailmux.core.NodeSnapshot(
                NodeId.parse("office-a"),
                dev.tailmux.core.NodeStatus.SSH_FAILED,
                Instant.parse("2026-05-15T19:01:45Z"),
                List.of()));
        FakeRemoteExecutor remote = new FakeRemoteExecutor();

        int exit = new CommandRouter(configWithOneNode(), store, remote,
                Clock.fixed(Instant.parse("2026-05-15T19:02:13Z"), ZoneOffset.UTC), new CapturingConsole())
                .run(List.of("ls"));

        check(exit == ExitCodes.SUCCESS, "recent offline cache exits success");
        check(remote.commandsFor("office-a").isEmpty(), "recent offline cache skips remote probe");
    }

    private void testListRendersLiveWindowCounts() throws Exception {
        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.when("office-a", "command -v tmux", ExecResult.success("/opt/homebrew/bin/tmux\n"));
        remote.when("office-a", "tmux -L default list-sessions -F '#{session_name}\\037#{session_id}\\037#{session_attached}\\037#{session_created}\\037#{session_activity}'",
                ExecResult.success("work\u001F\u00241\u001F0\u001F1\u001F2\n"));
        remote.when("office-a", "tmux -L default list-windows -a -F '#{session_name}\\037#{window_index}\\037#{window_id}\\037#{window_name}\\037#{window_active}'",
                ExecResult.success("work\u001F0\u001F@1\u001Feditor\u001F1\n"));

        CapturingConsole console = new CapturingConsole();
        int exit = new CommandRouter(configWithOneNode(), new PropertiesStateStore(tempDir().resolve(".tailmux/state")), remote,
                Clock.fixed(Instant.parse("2026-05-15T19:02:13Z"), ZoneOffset.UTC), console)
                .run(List.of("ls"));

        check(exit == ExitCodes.SUCCESS, "ls exits success");
        check(console.out().contains("work"), "ls renders session");
        check(console.out().contains("1         no"), "ls renders live window count");
    }

    private void testListWindowsRendersActivePaneMetadata() throws Exception {
        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.when("office-a", TmuxCommands.listSessions("default"), ExecResult.success("work\u001F\u00241\u001F0\u001F1\u001F2\n"));
        remote.when("office-a", TmuxCommands.listWindows("default"), ExecResult.success("work\u001F0\u001F@1\u001Feditor\u001F1\n"));
        remote.when("office-a", TmuxCommands.listPanes("default"), ExecResult.success("work\u001F0\u001F0\u001F%1\u001F/Users/sungjooyoon/code/tailmux\u001Fnvim\u001F1\n"));

        CapturingConsole console = new CapturingConsole();
        int exit = new CommandRouter(configWithOneNode(), new PropertiesStateStore(tempDir().resolve(".tailmux/state")), remote,
                Clock.fixed(Instant.parse("2026-05-15T19:02:13Z"), ZoneOffset.UTC), console)
                .run(List.of("ls", "--windows"));

        check(exit == ExitCodes.SUCCESS, "ls --windows exits success");
        check(console.out().contains("/Users/sungjooyoon/code/tailmux"), "ls --windows renders active pane cwd");
        check(console.out().contains("nvim"), "ls --windows renders active pane command");
    }

    private void testListPanesRendersPaneRows() throws Exception {
        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.when("office-a", TmuxCommands.listSessions("default"), ExecResult.success("work\u001F\u00241\u001F0\u001F1\u001F2\n"));
        remote.when("office-a", TmuxCommands.listWindows("default"), ExecResult.success("work\u001F0\u001F@1\u001Feditor\u001F1\n"));
        remote.when("office-a", TmuxCommands.listPanes("default"), ExecResult.success("work\u001F0\u001F1\u001F%2\u001F/tmp\u001Fzsh\u001F1\n"));

        CapturingConsole console = new CapturingConsole();
        int exit = new CommandRouter(configWithOneNode(), new PropertiesStateStore(tempDir().resolve(".tailmux/state")), remote,
                Clock.fixed(Instant.parse("2026-05-15T19:02:13Z"), ZoneOffset.UTC), console)
                .run(List.of("ls", "--panes"));

        check(exit == ExitCodes.SUCCESS, "ls --panes exits success");
        check(console.out().contains("office-a:work.0.1"), "ls --panes renders pane selector");
        check(console.out().contains("/tmp"), "ls --panes renders pane cwd");
    }

    private void testAttachPaneSelectsWindowAndPane() throws Exception {
        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.when("office-a", "command -v tmux", ExecResult.success("/opt/homebrew/bin/tmux\n"));
        remote.when("office-a", TmuxCommands.listSessions("default"), ExecResult.success("work\u001F\u00241\u001F0\u001F1\u001F2\n"));
        remote.when("office-a", TmuxCommands.selectWindow("default", "work", 2), ExecResult.success(""));
        remote.when("office-a", TmuxCommands.selectPane("default", "work", 2, 1), ExecResult.success(""));

        int exit = router(configWithOneNode(), remote, tempDir()).run(List.of("attach", "office-a:work.2.1"));

        check(exit == ExitCodes.SUCCESS, "pane attach exits success");
        check(remote.commandsFor("office-a").contains(TmuxCommands.selectPane("default", "work", 2, 1)), "pane attach selects pane");
        check(remote.interactiveCommands().equals(List.of("office-a:tmux -L default attach-session -t work")), "pane attach uses selected session");
    }

    private void testListUsesSingleDiscoveryCommandPerNode() throws Exception {
        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        String output = "work\u001F\u00241\u001F0\u001F1\u001F2\n"
                + TmuxCommands.DISCOVERY_WINDOWS_MARKER + "\n"
                + "work\u001F0\u001F@1\u001Feditor\u001F1\n";
        remote.when("office-a", TmuxCommands.discover("default"), ExecResult.success(output));

        int exit = new CommandRouter(configWithOneNode(), new PropertiesStateStore(tempDir().resolve(".tailmux/state")), remote,
                Clock.fixed(Instant.parse("2026-05-15T19:02:13Z"), ZoneOffset.UTC), new CapturingConsole())
                .run(List.of("ls"));

        check(exit == ExitCodes.SUCCESS, "single discovery exits success");
        check(remote.commandsFor("office-a").equals(List.of(TmuxCommands.discover("default"))), "ls uses one discovery command");
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
        check(remote.commandsFor("office-a").contains(TmuxCommands.discover("default")), "multi-socket ls probes default socket");
        check(remote.commandsFor("office-a").contains(TmuxCommands.discover("work")), "multi-socket ls probes work socket");
    }

    private void testListDiscoversNodesConcurrently() throws Exception {
        Properties p = new Properties();
        p.setProperty("tailmux.home.pool", "office-a,office-b");
        TailmuxConfig config = TailmuxConfig.fromProperties(p);
        DelayingRemoteExecutor remote = new DelayingRemoteExecutor(Duration.ofMillis(500));

        long started = System.nanoTime();
        int exit = new CommandRouter(config, new PropertiesStateStore(tempDir().resolve(".tailmux/state")), remote,
                Clock.fixed(Instant.parse("2026-05-15T19:02:13Z"), ZoneOffset.UTC), new CapturingConsole())
                .run(List.of("ls"));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        check(exit == ExitCodes.SUCCESS, "concurrent discovery exits success");
        check(elapsedMillis < 900, "node discovery runs concurrently");
    }

    private void testDoctorClassification() throws Exception {
        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.when("office-a", "echo ok", ExecResult.success("ok\n"));
        remote.when("office-a", "command -v tmux", ExecResult.success("/opt/homebrew/bin/tmux\n"));
        remote.when("office-a", "tmux -L default list-sessions -F '#{session_name}\\037#{session_id}\\037#{session_attached}\\037#{session_created}\\037#{session_activity}'",
                ExecResult.failure(1, "", "no server running"));

        CapturingConsole console = new CapturingConsole();
        int exit = new CommandRouter(configWithOneNode(), new PropertiesStateStore(tempDir().resolve(".tailmux/state")), remote, Clock.systemUTC(), console)
                .run(List.of("doctor"));

        check(exit == ExitCodes.SUCCESS, "doctor no tmux server is warning not failure");
        check(console.out().contains("WARN  office-a tmux default no server currently running"), "doctor warns for no server");
    }

    private void testDoctorClassifiesMagicDnsFailure() throws Exception {
        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.when("office-a", "echo ok", ExecResult.failure(255, "", "ssh: Could not resolve hostname office-a.tail.ts.net.: nodename nor servname provided, or not known"));

        CapturingConsole console = new CapturingConsole();
        int exit = new CommandRouter(configWithOneNode(), new PropertiesStateStore(tempDir().resolve(".tailmux/state")), remote, Clock.systemUTC(), console)
                .run(List.of("doctor"));

        check(exit == ExitCodes.REMOTE_EXECUTION_ERROR, "doctor magicdns failure exits remote error");
        check(console.out().contains("FAIL  office-a magicdns resolution failed"), "doctor classifies magicdns failure");
        check(console.out().contains("Try:"), "doctor prints next action");
        check(!console.out().contains("tailscale down"), "doctor never recommends tailscale down");
    }

    private void testDoctorNetworkUsesSafeReadOnlyProbes() throws Exception {
        FakeLocalProcess process = new FakeLocalProcess();
        process.when(List.of("tailscale", "status"), ExecResult.success("100.0.0.1 office-a user@ macOS -\n"));
        process.when(List.of("tailscale", "ping", "--c=1", "office-a"), ExecResult.success("pong from office-a (100.0.0.1) in 10ms\n"));
        process.when(List.of("dscacheutil", "-q", "host", "-a", "name", "office-a"), ExecResult.failure(0, "", ""));
        process.when(List.of("dig", "@100.100.100.100", "office-a"), ExecResult.success("office-a. 600 IN A 100.0.0.1\n"));

        CapturingConsole console = new CapturingConsole();
        int exit = new CommandRouter(configWithOneNode(), new PropertiesStateStore(tempDir().resolve(".tailmux/state")), new FakeRemoteExecutor(),
                Clock.systemUTC(), console, process).run(List.of("doctor", "--network"));

        check(exit == ExitCodes.SUCCESS, "network doctor exits success");
        check(console.out().contains("OK    tailscale status"), "network doctor checks tailscale status");
        check(console.out().contains("OK    office-a tailscale ping"), "network doctor pings node");
        check(console.out().contains("WARN  office-a macOS resolver did not resolve host"), "network doctor reports resolver miss");
        check(console.out().contains("OK    office-a tailscale dns resolved host"), "network doctor checks tailscale dns");
        check(!process.commands().toString().contains("down"), "network doctor does not run tailscale down");
        check(!process.commands().toString().contains("up"), "network doctor does not run tailscale up");
        check(!process.commands().toString().contains("set"), "network doctor does not run tailscale set");
    }

    private CommandRouter router(TailmuxConfig config, FakeRemoteExecutor remote, Path home) {
        return new CommandRouter(config, new PropertiesStateStore(home.resolve(".tailmux/state")), remote, Clock.fixed(Instant.parse("2026-05-15T19:02:13Z"), ZoneOffset.UTC), new CapturingConsole());
    }

    private TailmuxConfig configWithPool() {
        Properties p = new Properties();
        p.setProperty("tailmux.home.pool", "office-a,office-b");
        return TailmuxConfig.fromProperties(p);
    }

    private TailmuxConfig configWithOneNode() {
        Properties p = new Properties();
        p.setProperty("tailmux.home.pool", "office-a");
        return TailmuxConfig.fromProperties(p);
    }

    private Path tempDir() throws Exception {
        return Files.createTempDirectory("tailmux-test-");
    }

    void check(boolean condition, String name) {
        if (condition) {
            passed++;
            return;
        }
        failed++;
        System.err.println("FAIL " + name);
    }

    void expectThrows(Class<? extends Throwable> type, ThrowingRunnable runnable, String name) {
        try {
            runnable.run();
            failed++;
            System.err.println("FAIL " + name + " (no exception)");
        } catch (Throwable t) {
            if (type.isInstance(t)) {
                passed++;
            } else {
                failed++;
                System.err.println("FAIL " + name + " (" + t + ")");
            }
        }
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Throwable;
    }

    private static final class DelayingRemoteExecutor implements RemoteExecutor {
        private final Duration delay;

        private DelayingRemoteExecutor(Duration delay) {
            this.delay = delay;
        }

        @Override
        public ExecResult execute(NodeConfig node, String command) throws InterruptedException {
            Thread.sleep(delay.toMillis());
            return ExecResult.success(TmuxCommands.DISCOVERY_WINDOWS_MARKER + "\n");
        }

        @Override
        public int attachInteractive(NodeConfig node, String command) {
            return 0;
        }
    }

    private static final class FakeLocalProcess extends LocalProcess {
        private final java.util.Map<List<String>, ExecResult> responses = new java.util.LinkedHashMap<List<String>, ExecResult> ();
        private final java.util.ArrayList<List<String>> commands = new java.util.ArrayList<List<String>> ();

        private void when(List<String> command, ExecResult result) {
            responses.put(command, result);
        }

        private List<List<String>> commands() {
            return List.copyOf(commands);
        }

        @Override
        public ExecResult capture(List<String> command) {
            commands.add(command);
            return responses.getOrDefault(command, ExecResult.failure(127, "", "missing fake response"));
        }

        @Override
        public boolean commandExists(String command) {
            return true;
        }
    }
}
