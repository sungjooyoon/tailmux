package dev.tailmux;

import dev.tailmux.cli.CommandRouter;
import dev.tailmux.config.TailmuxConfig;
import dev.tailmux.exec.FakeRemoteExecutor;
import dev.tailmux.state.PropertiesStateStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

}
