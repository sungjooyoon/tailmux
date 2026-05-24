package dev.tailmux;

import dev.tailmux.cli.CommandRouter;
import dev.tailmux.cli.ExitCodes;
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

final class ListRenderingTests extends TestMain {
    @Override
    void run() throws Exception {
        testListRendersLiveWindowCounts();
        testPlainListUsesSessionWindowCountsOnly();
        testListWindowsRendersActivePaneMetadata();
        testListWindowsAvoidsFullPaneDiscovery();
        testListPanesRendersPaneRows();
        testRendererAvoidsFormatterAndStreams();
        testActivePaneCachesPaneList();
        testLsRendererCachesSnapshotValues();
    }

    private void testListRendersLiveWindowCounts() throws Exception {
        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.when("office-a", TmuxCommands.listSessions("default"), ExecResult.success("work\u001F\u00241\u001F0\u001F1\u001F2\u001F1\n"));

        CapturingConsole console = new CapturingConsole();
        int exit = new CommandRouter(configWithOneNode(), new PropertiesStateStore(tempDir().resolve(".tailmux/state")), remote,
                Clock.fixed(Instant.parse("2026-05-15T19:02:13Z"), ZoneOffset.UTC), console)
                .run(List.of("ls"));

        check(exit == ExitCodes.SUCCESS, "ls exits success");
        check(console.out().contains("work"), "ls renders session");
        check(console.out().contains("1         no"), "ls renders live window count");
    }

    private void testPlainListUsesSessionWindowCountsOnly() throws Exception {
        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.when("office-a", TmuxCommands.listSessions("default"), ExecResult.success("work\u001F\u00241\u001F0\u001F1\u001F2\u001F3\n"));

        CapturingConsole console = new CapturingConsole();
        int exit = new CommandRouter(configWithOneNode(), new PropertiesStateStore(tempDir().resolve(".tailmux/state")), remote,
                Clock.fixed(Instant.parse("2026-05-15T19:02:13Z"), ZoneOffset.UTC), console)
                .run(List.of("ls"));

        check(exit == ExitCodes.SUCCESS, "plain ls exits success");
        check(console.out().contains("3         no"), "plain ls renders session_windows without window discovery");
        check(remote.commandsFor("office-a").equals(List.of(TmuxCommands.listSessions("default"))), "plain ls uses sessions only");
    }

    private void testListWindowsRendersActivePaneMetadata() throws Exception {
        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.when("office-a", TmuxCommands.listSessions("default"), ExecResult.success("work\u001F\u00241\u001F0\u001F1\u001F2\n"));
        remote.when("office-a", TmuxCommands.listWindows("default"), ExecResult.success("work\u001F0\u001F@1\u001Feditor\u001F1\u001F0\u001F%1\u001F/Users/sungjooyoon/code/tailmux\u001Fnvim\n"));

        CapturingConsole console = new CapturingConsole();
        int exit = new CommandRouter(configWithOneNode(), new PropertiesStateStore(tempDir().resolve(".tailmux/state")), remote,
                Clock.fixed(Instant.parse("2026-05-15T19:02:13Z"), ZoneOffset.UTC), console)
                .run(List.of("ls", "--windows"));

        check(exit == ExitCodes.SUCCESS, "ls --windows exits success");
        check(console.out().contains("/Users/sungjooyoon/code/tailmux"), "ls --windows renders active pane cwd");
        check(console.out().contains("nvim"), "ls --windows renders active pane command");
    }

    private void testListWindowsAvoidsFullPaneDiscovery() throws Exception {
        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.when("office-a", TmuxCommands.listSessions("default"), ExecResult.success("work\u001F\u00241\u001F0\u001F1\u001F2\n"));
        remote.when("office-a", TmuxCommands.listWindows("default"), ExecResult.success("work\u001F0\u001F@1\u001Feditor\u001F1\u001F0\u001F%1\u001F/tmp\u001Fzsh\n"));

        int exit = new CommandRouter(configWithOneNode(), new PropertiesStateStore(tempDir().resolve(".tailmux/state")), remote,
                Clock.fixed(Instant.parse("2026-05-15T19:02:13Z"), ZoneOffset.UTC), new CapturingConsole())
                .run(List.of("ls", "--windows"));

        check(exit == ExitCodes.SUCCESS, "ls --windows exits success without full pane discovery");
        check(remote.commandsFor("office-a").equals(List.of(TmuxCommands.discoverWindows("default"))), "ls --windows uses window-only discovery");
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

    private void testRendererAvoidsFormatterAndStreams() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/tailmux/cli/Renderers.java"));
        check(!source.contains("String.format"), "renderer avoids Formatter for fixed-width rows");
        check(!source.contains(".stream()"), "renderer avoids streams in row hot paths");
        check(!source.contains(".isBlank("), "renderer uses ascii text presence checks");
    }

    private void testActivePaneCachesPaneList() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/tailmux/cli/Renderers.java"));
        int method = source.indexOf("private static TmuxPane activePane");
        int next = source.indexOf("private static String valueOrDash", method);
        String body = source.substring(method, next);
        check(count(body, "window.panes()") == 1, "active pane renderer reads window panes once");
    }

    private void testLsRendererCachesSnapshotValues() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/tailmux/cli/Renderers.java"));
        int method = source.indexOf("public static void renderLs");
        int next = source.indexOf("public static void renderNodes", method);
        String body = source.substring(method, next);
        check(count(body, "snapshot.sessions()") == 1, "ls renderer caches snapshot sessions");
        check(count(body, "relative(snapshot.lastSeenAt(), clock)") == 1, "ls renderer computes last-seen text once per node");
    }

    private int count(String source, String needle) {
        int count = 0;
        for (int index = source.indexOf(needle); index >= 0; index = source.indexOf(needle, index + needle.length())) count++;
        return count;
    }
}
