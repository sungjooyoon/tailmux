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
        testCoreParsingUsesAsciiTextHelpers();
        testSelectorIndexParsingAvoidsSubstrings();
        testShellQuoting();
        testShellQuotingAvoidsReplaceHelper();
        testShellJoinKeepsOnlyVarargsApi();
        testLauncherArgStripAvoidsPathReplace();
        testTmuxEnsureSessionIsRaceTolerant();
        testTmuxDiscoveryShortCircuits();
        testTmuxNoServerClassifierAcceptsTmuxPhrasing();
        testTmuxFailureClassifiesWithoutTextFold();
        testProductParsingAvoidsRegexHelpers();
        testControlPathAvoidsStreamPipelines();
        testTmuxCommandsAvoidListWrappers();
        testClassifiersShareAsciiScanner();
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
        check(!selector.hasWindow(), "selector without window");

        Selector window = Selector.parse("office-a:work.2");
        check(window.windowIndex() == 2, "selector window");

        Selector pane = Selector.parse("office-a:work.2.1");
        check(pane.windowIndex() == 2, "selector pane window");
        check(pane.paneIndex() == 1, "selector pane");

        expectThrows(IllegalArgumentException.class, () -> Selector.parse("work"), "selector requires node");
        expectThrows(IllegalArgumentException.class, () -> Selector.parse("office-a:work.two"), "selector rejects nonnumeric window");
        expectThrows(IllegalArgumentException.class, () -> Selector.parse("office-a:work.999999999999"), "selector rejects overflowing window");
    }

    private void testCoreParsingUsesAsciiTextHelpers() throws Exception {
        for (String file : List.of(
                "src/main/java/dev/tailmux/core/NodeId.java",
                "src/main/java/dev/tailmux/core/WorkspaceName.java",
                "src/main/java/dev/tailmux/core/Selector.java")) {
            String source = Files.readString(Path.of(file));
            check(!source.contains("value.trim()") && !source.contains(".isBlank("), file + " uses ascii text helpers");
        }
    }

    private void testSelectorIndexParsingAvoidsSubstrings() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/tailmux/core/Selector.java"));
        check(!source.contains("parseIndex(target.substring"), "selector parses numeric ranges without substring allocation");
        check(!source.contains("Optional<") && !source.contains("Optional.of"), "selector stores primitive indexes");
    }

    private void testShellQuoting() {
        check(PosixShell.quote("plain").equals("plain"), "plain word unquoted");
        check(PosixShell.quote("two words").equals("'two words'"), "spaces quoted");
        check(PosixShell.quote("a'b").equals("'a'\"'\"'b'"), "single quote escaped");
        check(PosixShell.join("tmux", "new-session", "-d", "-s", "work space")
                .equals("tmux new-session -d -s 'work space'"), "join quotes only needed args");
    }

    private void testShellQuotingAvoidsReplaceHelper() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/tailmux/exec/PosixShell.java"));
        check(!source.contains(".replace("), "shell quoting avoids replace helper");
    }

    private void testShellJoinKeepsOnlyVarargsApi() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/tailmux/exec/PosixShell.java"));
        check(!source.contains("join(List<String>"), "shell join exposes only varargs API used by production");
        check(!source.contains("new StringBuilder()"), "shell join pre-sizes command builder");
    }

    private void testLauncherArgStripAvoidsPathReplace() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/tailmux/Main.java"));
        check(!source.contains(".replace("), "launcher arg strip checks slash variants without allocating replacement strings");
    }

    private void testTmuxEnsureSessionIsRaceTolerant() {
        String command = TmuxCommands.ensureSession("default", "work");
        check(command.indexOf("has-session") != command.lastIndexOf("has-session"), "ensure session retries has-session after create race");
        check(command.contains(" || "), "ensure session stays one shell transaction");
    }

    private void testTmuxNoServerClassifierAcceptsTmuxPhrasing() {
        check(TmuxFailure.noServer(ExecResult.failure(1, "", "failed to connect to server")), "classifies tmux failed-to-connect as no server");
    }

    private void testTmuxFailureClassifiesWithoutTextFold() throws Exception {
        check(TmuxFailure.classify(ExecResult.failure(127, "", "tmux: command not found")) == TmuxFailure.Kind.MISSING_BINARY, "classifies missing tmux");
        check(TmuxFailure.classify(ExecResult.failure(1, "NO SERVER RUNNING\n", "")) == TmuxFailure.Kind.NO_SERVER, "classifies stdout tmux errors case-insensitively");
        check(TmuxFailure.classify(ExecResult.failure(255, "", "ssh failed")) == TmuxFailure.Kind.REMOTE_EXECUTION, "classifies remote execution failure");
        String source = Files.readString(Path.of("src/main/java/dev/tailmux/tmux/TmuxFailure.java"));
        check(!source.contains("toLowerCase"), "tmux failure classification avoids folded string allocation");
        check(!source.contains("stderr() +"), "tmux failure classification avoids joining stdout and stderr");
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

    private void testClassifiersShareAsciiScanner() throws Exception {
        String tmux = Files.readString(Path.of("src/main/java/dev/tailmux/tmux/TmuxFailure.java"));
        String doctor = Files.readString(Path.of("src/main/java/dev/tailmux/cli/DoctorCommand.java"));
        check(tmux.contains("Ascii.containsIgnoreCase") && doctor.contains("Ascii.containsIgnoreCase"), "diagnostic classifiers share ascii scanner");
        check(!tmux.contains("regionMatches(true") && !doctor.contains("regionMatches(true"), "diagnostic classifiers do not duplicate scanner loops");
    }
}
