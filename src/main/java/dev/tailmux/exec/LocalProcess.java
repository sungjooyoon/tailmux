package dev.tailmux.exec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

public class LocalProcess {
    private static final Duration DEFAULT_CAPTURE_TIMEOUT = Duration.ofSeconds(3);

    public ExecResult capture(List<String> command) throws IOException, InterruptedException {
        return capture(command, DEFAULT_CAPTURE_TIMEOUT);
    }

    public ExecResult capture(List<String> command, Duration timeout) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).start();
        FutureTask<byte[]> stdout = readAsync(process.getInputStream());
        FutureTask<byte[]> stderr = readAsync(process.getErrorStream());
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor();
            String out = new String(join(stdout), StandardCharsets.UTF_8);
            String err = new String(join(stderr), StandardCharsets.UTF_8);
            return ExecResult.failure(124, out, ("command timed out after " + durationText(timeout) + "\n" + err).stripTrailing());
        }
        int exit = process.exitValue();
        return new ExecResult(exit, new String(join(stdout), StandardCharsets.UTF_8), new String(join(stderr), StandardCharsets.UTF_8));
    }

    public int inherit(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).inheritIO().start();
        return process.waitFor();
    }

    public boolean commandExists(String command) {
        try {
            return capture(List.of("sh", "-lc", "command -v " + PosixShell.quote(command))).ok();
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static byte[] readAll(InputStream input) {
        try {
            return input.readAllBytes();
        } catch (IOException e) {
            return ("could not read process stream: " + e.getMessage()).getBytes(StandardCharsets.UTF_8);
        }
    }

    private static FutureTask<byte[]> readAsync(InputStream input) {
        FutureTask<byte[]> task = new FutureTask<>(() -> readAll(input));
        Thread.ofVirtual().name("tailmux-process-stream-", 0).start(task);
        return task;
    }

    private static byte[] join(FutureTask<byte[]> future) throws InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException e) {
            return ("could not read process stream: " + e.getCause().getMessage()).getBytes(StandardCharsets.UTF_8);
        }
    }

    private static String durationText(Duration duration) {
        long millis = duration.toMillis();
        return millis % 1000 == 0 ? (millis / 1000) + "s" : millis + "ms";
    }
}
