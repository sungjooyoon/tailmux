package dev.tailmux;

import dev.tailmux.core.Selector;
import dev.tailmux.core.WorkspaceName;
import dev.tailmux.exec.ExecResult;
import dev.tailmux.exec.PosixShell;
import dev.tailmux.tmux.TmuxCommands;
import dev.tailmux.tmux.TmuxFailure;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class CoreTests extends TestMain {
    @Override
    void run() throws Exception {
        testWorkspaceNameValidation();
        testSelectorParsing();
        testShellQuoting();
        testShellQuotingAvoidsReplaceHelper();
        testTmuxEnsureSessionIsRaceTolerant();
        testTmuxDiscoveryShortCircuits();
        testTmuxNoServerClassifierAcceptsTmuxPhrasing();
        testTmuxFailureClassifiesWithOneTextFold();
        testProductParsingAvoidsRegexHelpers();
        testControlPathAvoidsStreamPipelines();
        testTmuxCommandsAvoidListWrappers();
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

    private void testShellQuotingAvoidsReplaceHelper() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/tailmux/exec/PosixShell.java"));
        check(!source.contains(".replace("), "shell quoting avoids replace helper");
    }

    private void testTmuxEnsureSessionIsRaceTolerant() {
        String command = TmuxCommands.ensureSession("default", "work");
        check(command.indexOf("has-session") != command.lastIndexOf("has-session"), "ensure session retries has-session after create race");
        check(command.contains(" || "), "ensure session stays one shell transaction");
    }

    private void testTmuxNoServerClassifierAcceptsTmuxPhrasing() {
        check(TmuxFailure.noServer(ExecResult.failure(1, "", "failed to connect to server")), "classifies tmux failed-to-connect as no server");
    }

    private void testTmuxFailureClassifiesWithOneTextFold() throws Exception {
        check(TmuxFailure.classify(ExecResult.failure(127, "", "tmux: command not found")) == TmuxFailure.Kind.MISSING_BINARY, "classifies missing tmux");
        check(TmuxFailure.classify(ExecResult.failure(255, "", "ssh failed")) == TmuxFailure.Kind.REMOTE_EXECUTION, "classifies remote execution failure");
        String source = Files.readString(Path.of("src/main/java/dev/tailmux/tmux/TmuxFailure.java"));
        check(source.indexOf("toLowerCase") == source.lastIndexOf("toLowerCase"), "tmux failure classification folds text once");
    }

    private void testTmuxDiscoveryShortCircuits() {
        String command = TmuxCommands.discover("default");
        check(command.contains(" && "), "tmux discovery short-circuits between probes");
        check(!command.contains(" ; "), "tmux discovery avoids unconditional probe separators");
    }

    private void testProductParsingAvoidsRegexHelpers() throws Exception {
        boolean regexHelper = Files.walk(Path.of("src/main/java"))
                .filter(path -> path.toString().endsWith(".java"))
                .map(path -> {
                    try {
                        return Files.readString(path);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .anyMatch(source -> source.contains(".matches(") || source.contains(".split(") || source.contains(".replaceAll("));

        check(!regexHelper, "product parsing avoids regex helpers");
    }

    private void testControlPathAvoidsStreamPipelines() throws Exception {
        for (String file : List.of(
                "src/main/java/dev/tailmux/config/TailmuxConfig.java",
                "src/main/java/dev/tailmux/cli/DiscoveryService.java",
                "src/main/java/dev/tailmux/cli/DoctorCommand.java",
                "src/main/java/dev/tailmux/cli/WorkspaceService.java")) {
            check(!Files.readString(Path.of(file)).contains(".stream()"), file + " avoids stream pipelines");
        }
    }

    private void testTmuxCommandsAvoidListWrappers() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/tailmux/tmux/TmuxCommands.java"));
        check(!source.contains("List.of("), "tmux command construction avoids List.of wrappers");
    }
}
