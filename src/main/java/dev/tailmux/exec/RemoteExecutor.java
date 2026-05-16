package dev.tailmux.exec;

import dev.tailmux.config.NodeConfig;

import java.io.IOException;

public interface RemoteExecutor {
    ExecResult execute(NodeConfig node, String command) throws IOException, InterruptedException;

    int attachInteractive(NodeConfig node, String command) throws IOException, InterruptedException;
}

