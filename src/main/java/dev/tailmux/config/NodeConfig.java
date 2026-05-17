package dev.tailmux.config;

import dev.tailmux.core.NodeId;
import dev.tailmux.text.Ascii;

import java.util.List;
import java.util.Optional;

public record NodeConfig(NodeId id, String host, Optional<String> user, List<String> sockets) {
    static final List<String> DEFAULT_SOCKETS = List.of("default");

    public NodeConfig {
        host = Ascii.trim(host);
        if (host.isEmpty()) {
            throw new IllegalArgumentException("host is required for node " + id);
        }
        user = user == null ? Optional.empty() : user;
        sockets = sockets == null || sockets.isEmpty() || sockets == DEFAULT_SOCKETS ? DEFAULT_SOCKETS : List.copyOf(sockets);
    }

    public String defaultSocket() {
        return sockets.getFirst();
    }
}
