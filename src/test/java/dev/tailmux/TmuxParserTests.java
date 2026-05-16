package dev.tailmux;

import dev.tailmux.core.NodeId;
import dev.tailmux.tmux.TmuxParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

final class TmuxParserTests {
    private TmuxParserTests() {
    }

    static void run(TestMain tests) throws Exception {
        testTmuxParsing(tests);
        testTmuxPaneParsing(tests);
        testMalformedRowsFail(tests);
    }

    private static void testTmuxParsing(TestMain tests) throws Exception {
        var snapshot = TmuxParser.parse(NodeId.parse("office-a"), "default",
                fixture("list-sessions-basic.txt"),
                fixture("list-windows-basic.txt"),
                Instant.parse("2026-05-15T19:02:13Z"));

        tests.check(snapshot.sessions().size() == 2, "parses sessions");
        tests.check(snapshot.sessions().getFirst().attached(), "parses attached flag");
        tests.check(snapshot.sessions().getFirst().windows().size() == 2, "joins windows to sessions");
        tests.check(snapshot.sessions().getFirst().windows().getFirst().name().equals("editor"), "parses window name");
    }

    private static void testTmuxPaneParsing(TestMain tests) throws Exception {
        var snapshot = TmuxParser.parse(NodeId.parse("office-a"), "default",
                "work\u001F\u00241\u001F1\u001F1778863519\u001F1778863619\n",
                "work\u001F0\u001F@0\u001Feditor\u001F1\n",
                fixture("list-panes-basic.txt"),
                Instant.parse("2026-05-15T19:02:13Z"));
        var pane = snapshot.sessions().getFirst().windows().getFirst().panes().getFirst();

        tests.check(pane.currentPath().equals("/Users/sungjooyoon/code/tailmux"), "parses pane cwd");
        tests.check(pane.currentCommand().equals("nvim"), "parses pane command");
        tests.check(pane.active(), "parses pane active");
    }

    private static void testMalformedRowsFail(TestMain tests) {
        tests.expectThrows(IllegalArgumentException.class,
                () -> TmuxParser.parse(NodeId.parse("office-a"), "default", "broken\n", "", Instant.now()),
                "malformed session row fails");
        tests.expectThrows(IllegalArgumentException.class,
                () -> TmuxParser.parse(NodeId.parse("office-a"), "default", "work\u001F$1\u001F0\u001F1\u001F2\n", "broken\n", Instant.now()),
                "malformed window row fails");
        tests.expectThrows(IllegalArgumentException.class,
                () -> TmuxParser.parse(NodeId.parse("office-a"), "default", "work\u001F$1\u001F0\u001F1\u001F2\n", "work\u001F0\u001F@0\u001Fzsh\u001F1\n", "broken\n", Instant.now()),
                "malformed pane row fails");
    }

    private static String fixture(String name) throws Exception {
        return Files.readString(Path.of("testdata/tmux").resolve(name));
    }
}
