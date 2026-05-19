package dev.tailmux.tailscale;

import dev.tailmux.config.NodeConfig;
import dev.tailmux.exec.ExecResult;
import dev.tailmux.exec.LocalProcess;
import dev.tailmux.exec.RemoteExecutor;

import java.io.IOException;

public final class TailscaleSshExecutor implements RemoteExecutor {
    private final LocalProcess process;

    public TailscaleSshExecutor(LocalProcess process) {
        this.process = process;
    }

    @Override
    public ExecResult execute(NodeConfig node, String command) throws IOException, InterruptedException {
        return process.capture("tailscale", "ssh", node.sshTarget(), command);
    }

    @Override
    public int attachInteractive(NodeConfig node, String command) throws IOException, InterruptedException {
        return process.inherit("tailscale", "ssh", node.sshTarget(), command);
    }
}
