package dev.tailmux;

import dev.tailmux.config.TailmuxConfig;
import dev.tailmux.exec.ExecResult;
import dev.tailmux.exec.LocalProcess;
import dev.tailmux.tailscale.TailscaleSshExecutor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;

final class ExecutionTests extends TestMain {
    @Override
    void run() throws Exception {
        testLocalProcessTimeout();
        testLocalProcessTimeoutPreservesPartialOutput();
        testLocalProcessDefaultTimeoutFailsFast();
        testLocalProcessLargeOutputDoesNotDeadlock();
        testTailscaleSshExecutorUsesTailscaleSshOnly();
        testExecResultUsesAsciiTrim();
        testProcessCallersAvoidListWrappers();
        testProductCodeDoesNotInvokePlainSsh();
    }

    private void testLocalProcessTimeout() throws Exception {
        ExecResult result = new LocalProcess().capture(List.of("sh", "-lc", "sleep 2"), Duration.ofMillis(50));
        check(result.exitCode() == 124, "timeout exit code");
        check(result.stderr().contains("timed out after 50ms"), "timeout error text includes useful duration");
    }

    private void testLocalProcessTimeoutPreservesPartialOutput() throws Exception {
        ExecResult result = new LocalProcess().capture(List.of("sh", "-lc", "printf out; printf err >&2; sleep 2"), Duration.ofMillis(250));
        check(result.exitCode() == 124, "partial timeout exit code");
        check(result.stdout().contains("out"), "timeout preserves partial stdout");
        check(result.stderr().contains("err"), "timeout preserves partial stderr");
        check(result.stderr().contains("timed out"), "timeout preserves timeout summary");
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

    private void testTailscaleSshExecutorUsesTailscaleSshOnly() throws Exception {
        Properties p = new Properties();
        p.setProperty("tailmux.user", "sungjooyoon");
        p.setProperty("tailmux.home.pool", "office-a");
        TailmuxConfig config = TailmuxConfig.fromProperties(p);
        FakeLocalProcess process = new FakeLocalProcess();
        TailscaleSshExecutor executor = new TailscaleSshExecutor(config, process);

        executor.execute(config.node(dev.tailmux.core.NodeId.parse("office-a")), "echo ok");
        executor.attachInteractive(config.node(dev.tailmux.core.NodeId.parse("office-a")), "tmux attach");

        check(process.commands().equals(List.of(
                List.of("tailscale", "ssh", "sungjooyoon@office-a", "echo ok"),
                List.of("tailscale", "ssh", "sungjooyoon@office-a", "tmux attach")
        )), "remote executor uses tailscale ssh for execute and attach");
    }

    private void testExecResultUsesAsciiTrim() throws Exception {
        check(ExecResult.failure(1, " out \n", " \t\r\n").errorText().equals("out"), "error text falls back to trimmed stdout");
        check(ExecResult.failure(1, "", " err \n").errorText().equals("err"), "error text trims stderr");
        String source = Files.readString(Path.of("src/main/java/dev/tailmux/exec/ExecResult.java"));
        check(!source.contains(".strip(") && !source.contains(".isBlank("), "exec result avoids unicode strip/isBlank scans");
    }

    private void testProcessCallersAvoidListWrappers() throws Exception {
        for (String file : List.of(
                "src/main/java/dev/tailmux/tailscale/TailscaleSshExecutor.java",
                "src/main/java/dev/tailmux/cli/DoctorCommand.java")) {
            String source = Files.readString(Path.of(file));
            check(!source.contains("capture(List.of(") && !source.contains("inherit(List.of("), file + " avoids process List.of wrappers");
        }
    }

    private void testProductCodeDoesNotInvokePlainSsh() throws Exception {
        boolean plainSsh = Files.walk(Path.of("src/main/java"))
                .filter(path -> path.toString().endsWith(".java"))
                .map(path -> {
                    try {
                        return Files.readString(path);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .anyMatch(source -> source.contains("List.of(\"ssh\"") || source.contains("new ProcessBuilder(\"ssh\""));

        check(!plainSsh, "product code does not invoke plain ssh");
    }
}
