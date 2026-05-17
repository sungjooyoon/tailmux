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
        testSessionWindowCountParsesWithoutWindowRows(tests);
        testTmuxPaneParsing(tests);
        testFullPaneParsingDoesNotDuplicateActiveWindowPane(tests);
        testEmptyAndWeirdSessionFixtures(tests);
        testOrphanWindowsAndPanesAreIgnored(tests);
        testBlankPaneFieldsRemainBlank(tests);
        testDiscoveryOutputSplitsMarkers(tests);
        testMalformedRowsFail(tests);
        testMalformedRowsPrintDelimiters(tests);
        testParserAvoidsStreamPipelines(tests);
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

    private static void testFullPaneParsingDoesNotDuplicateActiveWindowPane(TestMain tests) {
        var snapshot = TmuxParser.parse(NodeId.parse("office-a"), "default",
                "work\u001F$1\u001F1\u001F1\u001F2\u001F1\n",
                "work\u001F0\u001F@0\u001Feditor\u001F1\u001F0\u001F%1\u001F/tmp\u001Fzsh\n",
                "work\u001F0\u001F0\u001F%1\u001F/tmp\u001Fzsh\u001F1\n",
                Instant.parse("2026-05-15T19:02:13Z"));

        tests.check(snapshot.sessions().getFirst().windows().getFirst().panes().size() == 1, "full pane parse does not duplicate active pane from window row");
    }

    private static void testSessionWindowCountParsesWithoutWindowRows(TestMain tests) {
        var snapshot = TmuxParser.parse(NodeId.parse("office-a"), "default",
                "work\u001F\u00241\u001F1\u001F1778863519\u001F1778863619\u001F7\n",
                "",
                Instant.parse("2026-05-15T19:02:13Z"));

        tests.check(snapshot.sessions().getFirst().windowCount() == 7, "parses session_windows without window rows");
    }

    private static void testMalformedRowsFail(TestMain tests) {
        tests.expectThrows(IllegalArgumentException.class,
                () -> TmuxParser.parse(NodeId.parse("office-a"), "default", fixture("malformed-session-row.txt"), "", Instant.now()),
                "malformed session row fails");
        tests.expectThrows(IllegalArgumentException.class,
                () -> TmuxParser.parse(NodeId.parse("office-a"), "default", "work\u001F$1\u001F0\u001F1\u001F2\n", fixture("malformed-window-row.txt"), Instant.now()),
                "malformed window row fails");
        tests.expectThrows(IllegalArgumentException.class,
                () -> TmuxParser.parse(NodeId.parse("office-a"), "default", "work\u001F$1\u001F0\u001F1\u001F2\n", "work\u001F0\u001F@0\u001Fzsh\u001F1\n", fixture("malformed-pane-row.txt"), Instant.now()),
                "malformed pane row fails");
    }

    private static void testEmptyAndWeirdSessionFixtures(TestMain tests) throws Exception {
        var empty = TmuxParser.parse(NodeId.parse("office-a"), "default", fixture("list-sessions-empty.txt"), "", Instant.now());
        tests.check(empty.sessions().isEmpty(), "empty session fixture parses as no sessions");

        var snapshot = TmuxParser.parse(NodeId.parse("office-a"), "sock",
                fixture("list-sessions-weird.txt"),
                fixture("list-windows-orphan.txt"),
                Instant.parse("2026-05-15T19:02:13Z"));

        tests.check(snapshot.sessions().size() == 2, "parses punctuation session names");
        tests.check(snapshot.sessions().getFirst().socket().equals("sock"), "parser preserves socket");
        tests.check(snapshot.sessions().getFirst().windows().getFirst().name().equals("main.shell"), "parses punctuation window names");
    }

    private static void testOrphanWindowsAndPanesAreIgnored(TestMain tests) throws Exception {
        var snapshot = TmuxParser.parse(NodeId.parse("office-a"), "default",
                "work\u001F$1\u001F1\u001F1\u001F2\n",
                "missing\u001F0\u001F@9\u001Forphan\u001F1\nwork\u001F0\u001F@1\u001Fmain\u001F1\n",
                "missing\u001F0\u001F0\u001F%9\u001F/tmp\u001Fzsh\u001F1\nwork\u001F0\u001F0\u001F%1\u001F/tmp\u001Fzsh\u001F1\n",
                Instant.parse("2026-05-15T19:02:13Z"));

        tests.check(snapshot.sessions().getFirst().windows().size() == 1, "orphan window is ignored");
        tests.check(snapshot.sessions().getFirst().windows().getFirst().panes().size() == 1, "orphan pane is ignored");
    }

    private static void testBlankPaneFieldsRemainBlank(TestMain tests) throws Exception {
        var snapshot = TmuxParser.parse(NodeId.parse("office-a"), "default",
                "work\u001F$1\u001F1\u001F1\u001F2\n",
                "work\u001F0\u001F@0\u001Feditor\u001F1\n",
                fixture("list-panes-blank-fields.txt"),
                Instant.parse("2026-05-15T19:02:13Z"));
        var pane = snapshot.sessions().getFirst().windows().getFirst().panes().getFirst();

        tests.check(pane.currentPath().isEmpty(), "blank pane cwd stays blank");
        tests.check(pane.currentCommand().isEmpty(), "blank pane command stays blank");
        tests.check(pane.active(), "blank pane row still parses active flag");
    }

    private static void testDiscoveryOutputSplitsMarkers(TestMain tests) {
        String output = "sessions\n" + dev.tailmux.tmux.TmuxCommands.DISCOVERY_WINDOWS_MARKER + "\nwindows\n"
                + dev.tailmux.tmux.TmuxCommands.DISCOVERY_PANES_MARKER + "\npanes\n";

        var split = TmuxParser.splitDiscoveryOutput(output);

        tests.check(split.sessions().equals("sessions"), "discovery split extracts sessions");
        tests.check(split.windows().equals("windows"), "discovery split extracts windows");
        tests.check(split.panes().equals("panes\n"), "discovery split preserves pane payload");
    }

    private static void testMalformedRowsPrintDelimiters(TestMain tests) throws Exception {
        try {
            TmuxParser.parse(NodeId.parse("office-a"), "default", "broken\u001Frow\n", "", Instant.now());
            tests.check(false, "malformed row with delimiter fails");
        } catch (IllegalArgumentException e) {
            tests.check(e.getMessage().contains("broken|row"), "malformed row prints delimiters visibly");
        }
    }

    private static void testParserAvoidsStreamPipelines(TestMain tests) throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/tailmux/tmux/TmuxParser.java"));
        tests.check(!source.contains(".stream()"), "tmux parser avoids stream pipelines");
        tests.check(!source.contains(".strip"), "tmux parser uses protocol-local ascii trim");
        tests.check(source.contains("Ascii.trimRight") && !source.contains("private static String trim"), "tmux parser uses shared ascii trim helpers");
        tests.check(!source.contains("trimRight(trimLeft"), "tmux parser two-sided trim uses one substring");
        tests.check(!source.contains("new ArrayList<>(8)"), "tmux parser avoids list allocation for row fields");
        tests.check(!source.contains("String[]") && !source.contains("new String["), "tmux parser avoids array allocation for row fields");
        tests.check(!source.contains("output.substring(start, end)"), "tmux parser avoids line substring allocation");
    }

    private static String fixture(String name) throws Exception {
        return Files.readString(Path.of("testdata/tmux").resolve(name));
    }
}
