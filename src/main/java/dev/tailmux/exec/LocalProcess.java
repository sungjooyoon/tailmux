package dev.tailmux.exec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class LocalProcess {
    private static final Duration DEFAULT_CAPTURE_TIMEOUT = Duration.ofSeconds(3);

    public ExecResult capture(List<String> command) throws IOException, InterruptedException {
        return capture(command, DEFAULT_CAPTURE_TIMEOUT);
    }

    public ExecResult capture(List<String> command, Duration timeout) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).start();
        CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(() -> readAll(process.getInputStream()));
        CompletableFuture<byte[]> stderr = CompletableFuture.supplyAsync(() -> readAll(process.getErrorStream()));
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor();
            return ExecResult.failure(124, "", "command timed out after " + timeout.toSeconds() + "s");
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

    private static byte[] join(CompletableFuture<byte[]> future) throws InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException e) {
            return ("could not read process stream: " + e.getCause().getMessage()).getBytes(StandardCharsets.UTF_8);
        }
    }
}
