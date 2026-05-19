package dev.tailmux;

import dev.tailmux.config.NodeConfig;
import dev.tailmux.exec.ExecResult;
import dev.tailmux.exec.RemoteExecutor;

final class ThrowingRemoteExecutor implements RemoteExecutor {
    @Override
    public ExecResult execute(NodeConfig node, String command) {
        throw new IllegalStateException("worker exploded");
    }

    @Override
    public int attachInteractive(NodeConfig node, String command) {
        return 0;
    }
}
