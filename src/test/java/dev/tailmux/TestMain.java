package dev.tailmux;

import dev.tailmux.cli.CommandRouter;
import dev.tailmux.config.NodeConfig;
import dev.tailmux.config.TailmuxConfig;
import dev.tailmux.exec.ExecResult;
import dev.tailmux.exec.LocalProcess;
import dev.tailmux.exec.FakeRemoteExecutor;
import dev.tailmux.exec.RemoteExecutor;
import dev.tailmux.state.PropertiesStateStore;
import dev.tailmux.tmux.TmuxCommands;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Properties;

public class TestMain {
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

    void run() throws Exception {
    }

    private void runAll() throws Exception {
        runSuite(new CoreTests());
        runSuite(new ConfigTests());
        runSuite(new ExecutionTests());
        TmuxParserTests.run(this);
        runSuite(new StateStoreTests());
        runSuite(new CliWorkflowTests());
        runSuite(new ListRenderingTests());
        runSuite(new DiscoveryTests());
        runSuite(new DoctorTests());
    }

    private void runSuite(TestMain suite) throws Exception {
        suite.run();
        passed += suite.passed;
        failed += suite.failed;
    }

    CommandRouter router(TailmuxConfig config, FakeRemoteExecutor remote, Path home) {
        return new CommandRouter(config, new PropertiesStateStore(home.resolve(".tailmux/state")), remote,
                Clock.fixed(Instant.parse("2026-05-15T19:02:13Z"), ZoneOffset.UTC), new CapturingConsole());
    }

    TailmuxConfig configWithPool() {
        Properties p = new Properties();
        p.setProperty("tailmux.home.pool", "office-a,office-b");
        return TailmuxConfig.fromProperties(p);
    }

    TailmuxConfig configWithOneNode() {
        Properties p = new Properties();
        p.setProperty("tailmux.home.pool", "office-a");
        return TailmuxConfig.fromProperties(p);
    }

    Path tempDir() throws Exception {
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

    static final class DelayingRemoteExecutor implements RemoteExecutor {
        private final Duration delay;

        DelayingRemoteExecutor(Duration delay) {
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

    static final class ThrowingRemoteExecutor implements RemoteExecutor {
        @Override
        public ExecResult execute(NodeConfig node, String command) {
            throw new IllegalStateException("worker exploded");
        }

        @Override
        public int attachInteractive(NodeConfig node, String command) {
            return 0;
        }
    }

    static final class FakeLocalProcess extends LocalProcess {
        private final java.util.Map<List<String>, ExecResult> responses = new LinkedHashMap<>();
        private final java.util.List<List<String>> commands = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        private final java.util.List<String> existsChecks = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        private Duration captureDelay = Duration.ZERO;

        void when(List<String> command, ExecResult result) {
            responses.put(command, result);
        }

        void delayCaptures(Duration delay) {
            captureDelay = delay;
        }

        List<List<String>> commands() {
            return List.copyOf(commands);
        }

        List<String> existsChecks() {
            return List.copyOf(existsChecks);
        }

        private ExecResult captureList(List<String> command) throws InterruptedException {
            if (!captureDelay.isZero()) {
                Thread.sleep(captureDelay.toMillis());
            }
            commands.add(command);
            return responses.getOrDefault(command, ExecResult.failure(127, "", "missing fake response"));
        }

        @Override
        public ExecResult capture(String... command) throws InterruptedException {
            return captureList(List.of(command));
        }

        @Override
        public ExecResult capture(Duration timeout, String... command) throws InterruptedException {
            return captureList(List.of(command));
        }

        @Override
        public int inherit(String... command) {
            commands.add(List.of(command));
            return 0;
        }

        @Override
        public boolean commandExists(String command) {
            existsChecks.add(command);
            return true;
        }
    }
}
