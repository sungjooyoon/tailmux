package dev.tailmux;

import dev.tailmux.exec.ExecResult;
import dev.tailmux.exec.LocalProcess;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class FakeLocalProcess extends LocalProcess {
    private final Map<List<String>, ExecResult> responses = new LinkedHashMap<>();
    private final List<List<String>> commands = Collections.synchronizedList(new ArrayList<>());
    private final List<String> existsChecks = Collections.synchronizedList(new ArrayList<>());
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

    private ExecResult captureList(List<String> command) throws InterruptedException {
        if (!captureDelay.isZero()) {
            Thread.sleep(captureDelay.toMillis());
        }
        commands.add(command);
        return responses.getOrDefault(command, ExecResult.failure(127, "", "missing fake response"));
    }
}
