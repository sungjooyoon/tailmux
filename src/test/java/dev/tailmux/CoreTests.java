package dev.tailmux;

import dev.tailmux.core.Selector;
import dev.tailmux.core.WorkspaceName;
import dev.tailmux.exec.ExecResult;
import dev.tailmux.exec.PosixShell;
import dev.tailmux.tmux.TmuxCommands;
import dev.tailmux.tmux.TmuxFailure;

import java.util.List;

final class CoreTests extends TestMain {
    @Override
    void run() {
        testWorkspaceNameValidation();
        testSelectorParsing();
        testShellQuoting();
        testTmuxEnsureSessionIsRaceTolerant();
        testTmuxDiscoveryShortCircuits();
        testTmuxNoServerClassifierAcceptsTmuxPhrasing();
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

    private void testTmuxEnsureSessionIsRaceTolerant() {
        String command = TmuxCommands.ensureSession("default", "work");
        check(command.indexOf("has-session") != command.lastIndexOf("has-session"), "ensure session retries has-session after create race");
        check(command.contains(" || "), "ensure session stays one shell transaction");
    }

    private void testTmuxNoServerClassifierAcceptsTmuxPhrasing() {
        check(TmuxFailure.noServer(ExecResult.failure(1, "", "failed to connect to server")), "classifies tmux failed-to-connect as no server");
    }

    private void testTmuxDiscoveryShortCircuits() {
        String command = TmuxCommands.discover("default");
        check(command.contains(" && "), "tmux discovery short-circuits between probes");
        check(!command.contains(" ; "), "tmux discovery avoids unconditional probe separators");
    }
}
