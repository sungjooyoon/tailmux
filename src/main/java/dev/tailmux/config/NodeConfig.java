package dev.tailmux.config;

import dev.tailmux.core.NodeId;

import java.util.List;
import java.util.Optional;

public record NodeConfig(NodeId id, String host, Optional<String> user, List<String> sockets) {
    public NodeConfig {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host is required for node " + id);
        }
        user = user == null ? Optional.empty() : user;
        sockets = sockets == null || sockets.isEmpty() ? List.of("default") : List.copyOf(sockets);
    }

    public String defaultSocket() {
        return sockets.getFirst();
    }
}
