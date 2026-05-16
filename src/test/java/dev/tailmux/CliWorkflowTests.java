package dev.tailmux;

import dev.tailmux.cli.CommandRouter;
import dev.tailmux.cli.ExitCodes;
import dev.tailmux.config.TailmuxConfig;
import dev.tailmux.core.NodeId;
import dev.tailmux.exec.ExecResult;
import dev.tailmux.exec.FakeRemoteExecutor;
import dev.tailmux.state.PropertiesStateStore;
import dev.tailmux.tmux.TmuxCommands;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Properties;

final class CliWorkflowTests extends TestMain {
    @Override
    void run() throws Exception {
        testCommandRouting();
        testWorkspaceCreatesOnDefaultHome();
        testWorkspaceDiscoverySkipsWindowAndPaneMetadata();
        testRegistryOwnerUnreachableDoesNotCreateDuplicate();
        testDuplicateDiscoveredWorkspaceFails();
        testDiscoveredWorkspaceRemembersSocket();
        testRegisteredWorkspaceUsesStoredSocket();
        testRegisteredWorkspaceMissingSessionRecreatesOnlyOnOwner();
        testExplicitHomeMustBeHealthy();
        testAttachSelectorFindsNonDefaultSocket();
        testAttachSelectorRejectsAmbiguousSocket();
        testWorkspaceAttachWritesEventMetadata();
        testAttachPaneSelectsWindowAndPane();
        testAttachFailureSuggestsConfiguredSshTarget();
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
        remote.when("office-a", TmuxCommands.listSessions("default"), ExecResult.failure(1, "", "no server running on /tmp/tmux"));
        remote.when("office-a", TmuxCommands.listWindows("default"), ExecResult.success(""));
        remote.when("office-a", TmuxCommands.ensureSession("default", "work"), ExecResult.success(""));

        Path home = tempDir();
        CommandRouter router = router(configWithPool(), remote, home);
        int exit = router.run(List.of("start", "work"));

        check(exit == ExitCodes.SUCCESS, "start exits success");
        check(remote.interactiveCommands().equals(List.of("office-a:tmux -L default attach-session -t work")), "interactive attach command");
        check(Files.exists(home.resolve(".tailmux/state/workspaces/work.properties")), "workspace registered");
        check(!remote.commandsFor("office-a").contains("command -v tmux"), "start avoids redundant tmux binary probe after discovery");
    }

    private void testWorkspaceDiscoverySkipsWindowAndPaneMetadata() throws Exception {
        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.when("office-a", TmuxCommands.listSessions("default"), ExecResult.success(""));
        remote.when("office-a", TmuxCommands.ensureSession("default", "work"), ExecResult.success(""));

        int exit = router(configWithOneNode(), remote, tempDir()).run(List.of("work"));

        check(exit == ExitCodes.SUCCESS, "workspace shorthand exits success");
        check(remote.commandsFor("office-a").equals(List.of(
                TmuxCommands.listSessions("default"),
                TmuxCommands.ensureSession("default", "work")
        )), "workspace shorthand scans sessions only before create");
    }

    private void testRegistryOwnerUnreachableDoesNotCreateDuplicate() throws Exception {
        Path home = tempDir();
        PropertiesStateStore store = new PropertiesStateStore(home.resolve(".tailmux/state"));
        store.saveWorkspace("work", NodeId.parse("office-a"), "work", Instant.now(), Instant.now());

        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.failNode("office-a", ExecResult.failure(255, "", "ssh failed"));

        int exit = router(configWithPool(), remote, home).run(List.of("work"));
        check(exit == ExitCodes.REMOTE_EXECUTION_ERROR, "unreachable owner exits remote error");
        check(remote.interactiveCommands().isEmpty(), "does not attach elsewhere");
        check(remote.commandsFor("office-b").isEmpty(), "does not probe alternate node after registered owner failure");
    }

    private void testDuplicateDiscoveredWorkspaceFails() throws Exception {
        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        for (String node : List.of("office-a", "office-b")) {
            remote.when(node, TmuxCommands.listSessions("default"), ExecResult.success("work\u001F\u00241\u001F0\u001F1\u001F2\n"));
            remote.when(node, TmuxCommands.listWindows("default"), ExecResult.success(""));
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
        remote.when("office-a", TmuxCommands.ensureSession("work", "modal"), ExecResult.success(""));

        int exit = router(configWithOneNode(), remote, home).run(List.of("modal"));

        check(exit == ExitCodes.SUCCESS, "registered socket workspace exits success");
        check(remote.interactiveCommands().equals(List.of("office-a:tmux -L work attach-session -t modal")), "registered workspace uses stored socket");
        check(remote.commandsFor("office-a").equals(List.of(TmuxCommands.ensureSession("work", "modal"))), "registered workspace uses one remote session transaction before attach");
    }

    private void testRegisteredWorkspaceMissingSessionRecreatesOnlyOnOwner() throws Exception {
        Path home = tempDir();
        PropertiesStateStore store = new PropertiesStateStore(home.resolve(".tailmux/state"));
        store.saveWorkspace("work", NodeId.parse("office-a"), "work", "default", Instant.now(), Instant.now());

        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.when("office-a", TmuxCommands.ensureSession("default", "work"), ExecResult.success(""));

        int exit = router(configWithPool(), remote, home).run(List.of("work"));

        check(exit == ExitCodes.SUCCESS, "registered missing session recreates on owner");
        check(remote.commandsFor("office-a").equals(List.of(TmuxCommands.ensureSession("default", "work"))), "registered missing session uses one create-if-missing transaction");
        check(remote.commandsFor("office-b").isEmpty(), "registered missing session does not scan alternate node");
    }

    private void testExplicitHomeMustBeHealthy() throws Exception {
        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.failNode("office-b", ExecResult.failure(255, "", "ssh failed"));
        remote.when("office-a", TmuxCommands.discover("default"), ExecResult.success(""));

        int exit = router(configWithPool(), remote, tempDir()).run(List.of("start", "work", "--home", "office-b"));

        check(exit == ExitCodes.NO_HEALTHY_HOME_NODE, "explicit unhealthy home fails");
        check(remote.interactiveCommands().isEmpty(), "explicit unhealthy home does not attach");
        check(!remote.commandsFor("office-a").contains(TmuxCommands.newSession("default", "work")), "explicit unhealthy home does not create on fallback");
    }

    private void testAttachSelectorFindsNonDefaultSocket() throws Exception {
        Properties p = new Properties();
        p.setProperty("tailmux.home.pool", "office-a");
        p.setProperty("tailmux.node.office-a.sockets", "default,work");
        TailmuxConfig config = TailmuxConfig.fromProperties(p);
        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.when("office-a", TmuxCommands.listSessions("default"), ExecResult.failure(1, "", "no server running"));
        remote.when("office-a", TmuxCommands.listSessions("work"), ExecResult.success("modal\u001F\u00242\u001F0\u001F1\u001F2\n"));

        int exit = router(config, remote, tempDir()).run(List.of("attach", "office-a:modal"));

        check(exit == ExitCodes.SUCCESS, "attach selector finds non-default socket");
        check(remote.interactiveCommands().equals(List.of("office-a:tmux -L work attach-session -t modal")), "attach selector uses resolved socket");
        check(!remote.commandsFor("office-a").contains("command -v tmux"), "attach selector resolves socket without redundant tmux binary probe");
    }

    private void testAttachSelectorRejectsAmbiguousSocket() throws Exception {
        Properties p = new Properties();
        p.setProperty("tailmux.home.pool", "office-a");
        p.setProperty("tailmux.node.office-a.sockets", "default,work");
        TailmuxConfig config = TailmuxConfig.fromProperties(p);
        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.when("office-a", TmuxCommands.listSessions("default"), ExecResult.success("modal\u001F\u00241\u001F0\u001F1\u001F2\n"));
        remote.when("office-a", TmuxCommands.listSessions("work"), ExecResult.success("modal\u001F\u00242\u001F0\u001F1\u001F2\n"));

        CapturingConsole console = new CapturingConsole();
        int exit = new CommandRouter(config, new PropertiesStateStore(tempDir().resolve(".tailmux/state")), remote,
                Clock.fixed(Instant.parse("2026-05-15T19:02:13Z"), ZoneOffset.UTC), console)
                .run(List.of("attach", "office-a:modal"));

        check(exit == ExitCodes.CONFIG_ERROR, "attach selector rejects ambiguous socket");
        check(console.err().contains("exists on multiple sockets"), "attach selector explains socket ambiguity");
        check(remote.interactiveCommands().isEmpty(), "attach selector does not attach ambiguous session");
    }

    private void testWorkspaceAttachWritesEventMetadata() throws Exception {
        Path home = tempDir();
        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.when("office-a", TmuxCommands.listSessions("default"), ExecResult.failure(1, "", "no server running"));
        remote.when("office-a", TmuxCommands.listWindows("default"), ExecResult.success(""));
        remote.when("office-a", TmuxCommands.ensureSession("default", "work"), ExecResult.success(""));

        int exit = router(configWithOneNode(), remote, home).run(List.of("work"));
        String log = Files.readString(home.resolve(".tailmux/state/events/2026-05-15.log"));

        check(exit == ExitCodes.SUCCESS, "workspace event attach exits success");
        check(log.contains("event=workspace"), "workspace event logged");
        check(log.contains("workspace=work"), "workspace event logs workspace");
        check(log.contains("node=office-a"), "workspace event logs node");
        check(log.contains("socket=default"), "workspace event logs socket");
        check(log.contains("session=work"), "workspace event logs session");
    }

    private void testAttachPaneSelectsWindowAndPane() throws Exception {
        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.when("office-a", TmuxCommands.listSessions("default"), ExecResult.success("work\u001F\u00241\u001F0\u001F1\u001F2\n"));
        remote.when("office-a", TmuxCommands.selectWindowAndPane("default", "work", 2, 1), ExecResult.success(""));

        int exit = router(configWithOneNode(), remote, tempDir()).run(List.of("attach", "office-a:work.2.1"));

        check(exit == ExitCodes.SUCCESS, "pane attach exits success");
        check(remote.commandsFor("office-a").equals(List.of(TmuxCommands.listSessions("default"), TmuxCommands.selectWindowAndPane("default", "work", 2, 1))), "pane attach selects window and pane in one remote command");
        check(remote.interactiveCommands().equals(List.of("office-a:tmux -L default attach-session -t work")), "pane attach uses selected session");
    }

    private void testAttachFailureSuggestsConfiguredSshTarget() throws Exception {
        Properties p = new Properties();
        p.setProperty("tailmux.user", "sungjooyoon");
        p.setProperty("tailmux.home.pool", "sungjoos-mac-studio");
        p.setProperty("tailmux.node.sungjoos-mac-studio.user", "sjy-2");
        TailmuxConfig config = TailmuxConfig.fromProperties(p);

        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.when("sungjoos-mac-studio", TmuxCommands.listSessions("default"), ExecResult.failure(255, "", "ssh failed"));

        CapturingConsole console = new CapturingConsole();
        int exit = new CommandRouter(config, new PropertiesStateStore(tempDir().resolve(".tailmux/state")), remote,
                Clock.fixed(Instant.parse("2026-05-15T19:02:13Z"), ZoneOffset.UTC), console)
                .run(List.of("attach", "sungjoos-mac-studio:work"));

        check(exit == ExitCodes.REMOTE_EXECUTION_ERROR, "attach ssh failure exits remote error");
        check(console.err().contains("tailscale ssh sjy-2@sungjoos-mac-studio 'echo ok'"), "attach ssh failure suggests configured target");
    }
}
