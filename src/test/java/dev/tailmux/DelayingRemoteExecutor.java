package dev.tailmux;

import dev.tailmux.config.NodeConfig;
import dev.tailmux.exec.ExecResult;
import dev.tailmux.exec.RemoteExecutor;
import dev.tailmux.tmux.TmuxCommands;

import java.time.Duration;

final class DelayingRemoteExecutor implements RemoteExecutor {
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
