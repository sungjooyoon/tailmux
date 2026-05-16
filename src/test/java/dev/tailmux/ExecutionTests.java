package dev.tailmux;

import dev.tailmux.exec.ExecResult;
import dev.tailmux.exec.LocalProcess;

import java.time.Duration;
import java.util.List;

final class ExecutionTests extends TestMain {
    @Override
    void run() throws Exception {
        testLocalProcessTimeout();
        testLocalProcessDefaultTimeoutFailsFast();
        testLocalProcessLargeOutputDoesNotDeadlock();
    }

    private void testLocalProcessTimeout() throws Exception {
        ExecResult result = new LocalProcess().capture(List.of("sh", "-lc", "sleep 2"), Duration.ofMillis(50));
        check(result.exitCode() == 124, "timeout exit code");
        check(result.stderr().contains("timed out"), "timeout error text");
    }

    private void testLocalProcessDefaultTimeoutFailsFast() throws Exception {
        ExecResult result = new LocalProcess().capture(List.of("sh", "-lc", "sleep 4"));
        check(result.exitCode() == 124, "default timeout exit code");
    }

    private void testLocalProcessLargeOutputDoesNotDeadlock() throws Exception {
        ExecResult result = new LocalProcess().capture(List.of("sh", "-lc", "yes x | head -n 200000"), Duration.ofSeconds(5));
        check(result.exitCode() == 0, "large output command exits successfully");
        check(result.stdout().length() > 200000, "large output is captured");
    }
}
