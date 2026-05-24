package dev.tailmux.config;

import dev.tailmux.core.NodeId;
import dev.tailmux.text.Ascii;

import java.util.List;

public record NodeConfig(NodeId id, String host, List<String> sockets, String sshTarget) {
    static final List<String> DEFAULT_SOCKETS = List.of("default");

    public NodeConfig {
        host = Ascii.trim(host);
        if (host.isEmpty()) {
            throw new IllegalArgumentException("host is required for node " + id);
        }
        sockets = sockets == null || sockets.isEmpty() || sockets == DEFAULT_SOCKETS ? DEFAULT_SOCKETS : List.copyOf(sockets);
        sshTarget = Ascii.trim(sshTarget);
        if (sshTarget.isEmpty()) {
            throw new IllegalArgumentException("ssh target is required for node " + id);
        }
    }

    public String defaultSocket() {
        return sockets.getFirst();
    }
}
