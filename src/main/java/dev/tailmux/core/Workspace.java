package dev.tailmux.core;

import java.time.Instant;

public record Workspace(
        WorkspaceName name,
        NodeId home,
        String session,
        String socket,
        Instant createdAt,
        Instant lastSeenAt,
        String transport
) {
    public Workspace(WorkspaceName name, NodeId home, String session, Instant createdAt, Instant lastSeenAt, String transport) {
        this(name, home, session, "default", createdAt, lastSeenAt, transport);
    }
}
