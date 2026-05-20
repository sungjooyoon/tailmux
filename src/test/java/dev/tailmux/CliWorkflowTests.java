package dev.tailmux;

import dev.tailmux.cli.CommandRouter;
import dev.tailmux.cli.ExitCodes;
import dev.tailmux.config.TailmuxConfig;
import dev.tailmux.core.NodeId;
import dev.tailmux.core.NodeSnapshot;
import dev.tailmux.core.NodeStatus;
import dev.tailmux.core.TmuxSession;
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
        testCommandRoutingAvoidsBuiltinList();
        testParsedCommandAvoidsDefensiveArgCopy();
        testAttachRoutingAvoidsSelectorRescan();
        testCommandRoutingAvoidsHomeOptionalWrappers();
        testWorkspaceShorthandAvoidsSingletonArgCopy();
        testCommandFlagsAvoidRepeatedContainsScans();
        testWorkspaceCreatesOnDefaultHome();
        testWorkspaceSelectionAvoidsTransientMatchLists();
        testWorkspaceSelectionAvoidsOptionalPipelines();
        testWorkspaceSelectionCachesSnapshotValues();
        testWorkspaceDiscoverySkipsWindowAndPaneMetadata();
        testRegistryOwnerUnreachableDoesNotCreateDuplicate();
        testCachedOfflineWorkspaceDoesNotCreateDuplicate();
        testDuplicateDiscoveredWorkspaceFails();
        testDiscoveredWorkspaceRemembersSocket();
        testRegisteredWorkspaceUsesStoredSocket();
        testRegisteredWorkspaceMissingSessionRecreatesOnlyOnOwner();
        testExplicitHomeMustBeHealthy();
        testAttachSelectorSingleSocketUsesHasSession();
        testAttachSelectorFindsNonDefaultSocket();
        testAttachSelectorRejectsAmbiguousSocket();
        testAttachSocketResolutionAvoidsMatchList();
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

    private void testCommandRoutingAvoidsBuiltinList() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/tailmux/cli/CommandRouter.java"));
        check(!source.contains("BUILTINS") && !source.contains(".contains(first)"), "command routing avoids builtin list scan");
    }

    private void testParsedCommandAvoidsDefensiveArgCopy() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/tailmux/cli/ParsedCommand.java"));
        check(!source.contains("List.copyOf(args)"), "parsed command avoids copying owned routing args");
    }

    private void testAttachRoutingAvoidsSelectorRescan() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/tailmux/cli/WorkspaceService.java"));
        check(!source.contains("target.contains(\":\")"), "attach routing avoids a second selector delimiter scan");
    }

    private void testCommandRoutingAvoidsHomeOptionalWrappers() throws Exception {
        String parsed = Files.readString(Path.of("src/main/java/dev/tailmux/cli/ParsedCommand.java"));
        String router = Files.readString(Path.of("src/main/java/dev/tailmux/cli/CommandRouter.java"));
        String workspace = Files.readString(Path.of("src/main/java/dev/tailmux/cli/WorkspaceService.java"));
        check(!parsed.contains("Optional<") && !router.contains("Optional.") && !workspace.contains("Optional<NodeId> explicitHome"), "routing carries explicit home without optional wrappers");
    }

    private void testWorkspaceShorthandAvoidsSingletonArgCopy() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/tailmux/cli/CommandRouter.java"));
        check(!source.contains("List.of(first)"), "workspace shorthand reuses owned argv instead of singleton copy");
    }

    private void testCommandFlagsAvoidRepeatedContainsScans() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/tailmux/cli/CommandRouter.java"));
        check(!source.contains(".contains(\"--"), "command flags avoid repeated contains scans");
        check(!source.contains("ArrayList<String> args = new ArrayList<>()"), "start parser allocates argument copy only when flags are present");
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

    private void testWorkspaceSelectionAvoidsTransientMatchLists() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/tailmux/cli/WorkspaceService.java"));
        check(!source.contains("ArrayList<NodeSession>") && !source.contains("ArrayList<NodeConfig> healthy"), "workspace selection avoids transient match/home lists");
    }

    private void testWorkspaceSelectionAvoidsOptionalPipelines() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/tailmux/cli/WorkspaceService.java"));
        check(!source.contains(".map(") && !source.contains(".orElse("), "workspace selection uses explicit Optional branches");
    }

    private void testWorkspaceSelectionCachesSnapshotValues() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/tailmux/cli/WorkspaceService.java"));
        check(count(source, "snapshot.status()") == 1, "workspace selection caches snapshot status");
        check(count(source, "snapshot.sessions()") == 1, "workspace selection caches snapshot sessions");
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
        store.saveWorkspace("work", NodeId.parse("office-a"), "work", "default", Instant.now(), Instant.now());

        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.failNode("office-a", ExecResult.failure(255, "", "ssh failed"));

        int exit = router(configWithPool(), remote, home).run(List.of("work"));
        check(exit == ExitCodes.REMOTE_EXECUTION_ERROR, "unreachable owner exits remote error");
        check(remote.interactiveCommands().isEmpty(), "does not attach elsewhere");
        check(remote.commandsFor("office-b").isEmpty(), "does not probe alternate node after registered owner failure");
    }

    private void testCachedOfflineWorkspaceDoesNotCreateDuplicate() throws Exception {
        Path home = tempDir();
        PropertiesStateStore store = new PropertiesStateStore(home.resolve(".tailmux/state"));
        store.saveSnapshot(new NodeSnapshot(NodeId.parse("office-a"), NodeStatus.SSH_FAILED,
                Instant.parse("2026-05-15T19:01:55Z"),
                List.of(new TmuxSession("default", "work", "$1", false, 1, 2, List.of(), 1))));

        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.when("office-b", TmuxCommands.listSessions("default"), ExecResult.success(""));
        remote.when("office-b", TmuxCommands.ensureSession("default", "work"), ExecResult.success(""));
        CapturingConsole console = new CapturingConsole();

        int exit = new CommandRouter(configWithPool(), store, remote,
                Clock.fixed(Instant.parse("2026-05-15T19:02:13Z"), ZoneOffset.UTC), console)
                .run(List.of("work"));

        check(exit == ExitCodes.CONFIG_ERROR, "cached offline workspace blocks duplicate creation");
        check(console.err().contains("last seen on offline node office-a"), "cached offline workspace error names owner");
        check(!remote.commandsFor("office-b").contains(TmuxCommands.ensureSession("default", "work")), "cached offline workspace does not create elsewhere");
        check(remote.interactiveCommands().isEmpty(), "cached offline workspace does not attach elsewhere");
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
        check(new PropertiesStateStore(home.resolve(".tailmux/state")).loadWorkspace("modal").socket().equals("work"), "discovered workspace stores socket");
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

    private void testAttachSelectorSingleSocketUsesHasSession() throws Exception {
        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.when("office-a", TmuxCommands.hasSession("default", "work"), ExecResult.success(""));

        int exit = router(configWithOneNode(), remote, tempDir()).run(List.of("attach", "office-a:work"));

        check(exit == ExitCodes.SUCCESS, "single-socket selector attach exits success");
        check(remote.commandsFor("office-a").equals(List.of(TmuxCommands.hasSession("default", "work"))), "single-socket selector uses has-session only");
        check(remote.interactiveCommands().equals(List.of("office-a:tmux -L default attach-session -t work")), "single-socket selector attaches default socket");
    }

    private void testAttachSelectorFindsNonDefaultSocket() throws Exception {
        Properties p = new Properties();
        p.setProperty("tailmux.home.pool", "office-a");
        p.setProperty("tailmux.node.office-a.sockets", "default,work");
        TailmuxConfig config = TailmuxConfig.fromProperties(p);
        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.when("office-a", TmuxCommands.hasSession("default", "modal"), ExecResult.failure(1, "", "can't find session: modal"));
        remote.when("office-a", TmuxCommands.hasSession("work", "modal"), ExecResult.success(""));

        int exit = router(config, remote, tempDir()).run(List.of("attach", "office-a:modal"));

        check(exit == ExitCodes.SUCCESS, "attach selector finds non-default socket");
        check(remote.commandsFor("office-a").equals(List.of(
                TmuxCommands.hasSession("default", "modal"),
                TmuxCommands.hasSession("work", "modal")
        )), "multi-socket selector uses has-session probes only");
        check(remote.interactiveCommands().equals(List.of("office-a:tmux -L work attach-session -t modal")), "attach selector uses resolved socket");
        check(!remote.commandsFor("office-a").contains("command -v tmux"), "attach selector resolves socket without redundant tmux binary probe");
    }

    private void testAttachSelectorRejectsAmbiguousSocket() throws Exception {
        Properties p = new Properties();
        p.setProperty("tailmux.home.pool", "office-a");
        p.setProperty("tailmux.node.office-a.sockets", "default,work");
        TailmuxConfig config = TailmuxConfig.fromProperties(p);
        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.when("office-a", TmuxCommands.hasSession("default", "modal"), ExecResult.success(""));
        remote.when("office-a", TmuxCommands.hasSession("work", "modal"), ExecResult.success(""));

        CapturingConsole console = new CapturingConsole();
        int exit = new CommandRouter(config, new PropertiesStateStore(tempDir().resolve(".tailmux/state")), remote,
                Clock.fixed(Instant.parse("2026-05-15T19:02:13Z"), ZoneOffset.UTC), console)
                .run(List.of("attach", "office-a:modal"));

        check(exit == ExitCodes.CONFIG_ERROR, "attach selector rejects ambiguous socket");
        check(console.err().contains("exists on multiple sockets"), "attach selector explains socket ambiguity");
        check(remote.interactiveCommands().isEmpty(), "attach selector does not attach ambiguous session");
    }

    private void testAttachSocketResolutionAvoidsMatchList() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/tailmux/cli/WorkspaceService.java"));
        check(!source.contains("ArrayList<String> matches"), "attach socket resolution tracks scalar match state");
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
        remote.when("office-a", TmuxCommands.hasSession("default", "work"), ExecResult.success(""));
        remote.when("office-a", TmuxCommands.selectWindowAndPane("default", "work", 2, 1), ExecResult.success(""));

        int exit = router(configWithOneNode(), remote, tempDir()).run(List.of("attach", "office-a:work.2.1"));

        check(exit == ExitCodes.SUCCESS, "pane attach exits success");
        check(remote.commandsFor("office-a").equals(List.of(TmuxCommands.hasSession("default", "work"), TmuxCommands.selectWindowAndPane("default", "work", 2, 1))), "pane attach selects window and pane in one remote command");
        check(remote.interactiveCommands().equals(List.of("office-a:tmux -L default attach-session -t work")), "pane attach uses selected session");
    }

    private void testAttachFailureSuggestsConfiguredSshTarget() throws Exception {
        Properties p = new Properties();
        p.setProperty("tailmux.user", "sungjooyoon");
        p.setProperty("tailmux.home.pool", "sungjoos-mac-studio");
        p.setProperty("tailmux.node.sungjoos-mac-studio.user", "sjy-2");
        TailmuxConfig config = TailmuxConfig.fromProperties(p);

        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.when("sungjoos-mac-studio", TmuxCommands.hasSession("default", "work"), ExecResult.failure(255, "", "ssh failed"));

        CapturingConsole console = new CapturingConsole();
        int exit = new CommandRouter(config, new PropertiesStateStore(tempDir().resolve(".tailmux/state")), remote,
                Clock.fixed(Instant.parse("2026-05-15T19:02:13Z"), ZoneOffset.UTC), console)
                .run(List.of("attach", "sungjoos-mac-studio:work"));

        check(exit == ExitCodes.REMOTE_EXECUTION_ERROR, "attach ssh failure exits remote error");
        check(console.err().contains("tailscale ssh sjy-2@sungjoos-mac-studio 'echo ok'"), "attach ssh failure suggests configured target");
    }

    private int count(String source, String needle) {
        int count = 0;
        for (int index = source.indexOf(needle); index >= 0; index = source.indexOf(needle, index + needle.length())) count++;
        return count;
    }
}
