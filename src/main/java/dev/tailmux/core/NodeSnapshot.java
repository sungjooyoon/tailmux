package dev.tailmux.core;

import java.time.Instant;
import java.util.List;

public record NodeSnapshot(NodeId node, NodeStatus status, Instant lastSeenAt, List<TmuxSession> sessions) {
    public NodeSnapshot {
        sessions = List.copyOf(sessions);
    }

    public NodeSnapshot withStatus(NodeStatus newStatus) {
        return new NodeSnapshot(node, newStatus, lastSeenAt, sessions);
    }
}

