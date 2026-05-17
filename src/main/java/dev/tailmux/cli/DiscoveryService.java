package dev.tailmux.cli;

import dev.tailmux.config.NodeConfig;
import dev.tailmux.core.NodeSnapshot;
import dev.tailmux.core.NodeStatus;
import dev.tailmux.core.TailmuxException;
import dev.tailmux.core.TmuxSession;
import dev.tailmux.exec.ExecResult;
import dev.tailmux.exec.RemoteExecutor;
import dev.tailmux.state.PropertiesStateStore;
import dev.tailmux.tmux.TmuxCommands;
import dev.tailmux.tmux.TmuxFailure;
import dev.tailmux.tmux.TmuxParser;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class DiscoveryService {
    private static final Duration RECENT_OFFLINE_TTL = Duration.ofSeconds(60);

    private final PropertiesStateStore store;
    private final RemoteExecutor remote;
    private final Clock clock;

    DiscoveryService(PropertiesStateStore store, RemoteExecutor remote, Clock clock) {
        this.store = store;
        this.remote = remote;
        this.clock = clock;
    }

    List<NodeSnapshot> discoverAll(List<NodeConfig> nodes, boolean includeWindows) {
        return snapshots(discoverNodes(nodes, includeWindows));
    }

    List<NodeSnapshot> discoverAll(List<NodeConfig> nodes, boolean includeWindows, boolean includePanes) {
        return snapshots(discoverNodes(nodes, includeWindows, includePanes));
    }

    List<DiscoveredNode> discoverNodes(List<NodeConfig> nodes, boolean includeWindows) {
        return discoverNodes(nodes, includeWindows, includeWindows);
    }

    private List<DiscoveredNode> discoverNodes(List<NodeConfig> nodes, boolean includeWindows, boolean includePanes) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ArrayList<Future<DiscoveredNode>> futures = new ArrayList<>(nodes.size());
            for (NodeConfig node : nodes) {
                futures.add(executor.submit(() -> new DiscoveredNode(node, discoverOrCached(node, includeWindows, includePanes))));
            }
            ArrayList<DiscoveredNode> discovered = new ArrayList<>(futures.size());
            for (Future<DiscoveredNode> future : futures) {
                try {
                    discovered.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new TailmuxException(ExitCodes.GENERAL_FAILURE, "FAIL discovery interrupted", e);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    throw new TailmuxException(ExitCodes.GENERAL_FAILURE, "FAIL discovery: " + cause.getMessage(), cause);
                }
            }
            return discovered;
        }
    }

    private List<NodeSnapshot> snapshots(List<DiscoveredNode> discovered) {
        ArrayList<NodeSnapshot> snapshots = new ArrayList<>(discovered.size());
        for (DiscoveredNode node : discovered) snapshots.add(node.snapshot());
        return snapshots;
    }

    private NodeSnapshot discoverOrCached(NodeConfig node, boolean includeWindows, boolean includePanes) {
        NodeSnapshot cached = store.loadSnapshot(node.id()).orElse(null);
        if (recentOffline(cached)) return cached.withStatus(NodeStatus.OFFLINE);
        NodeSnapshot snapshot = discover(node, includeWindows, includePanes, cached);
        if (snapshot.status() == NodeStatus.ONLINE || snapshot.status() == NodeStatus.NO_TMUX) {
            return snapshot;
        }
        if (cached != null) return cached.withStatus(NodeStatus.OFFLINE);
        return snapshot.withStatus(NodeStatus.OFFLINE);
    }

    private NodeSnapshot discover(NodeConfig node, boolean includeWindows, boolean includePanes, NodeSnapshot cached) {
        Instant now = clock.instant();
        try {
            ArrayList<TmuxSession> sessions = new ArrayList<>();
            for (String socket : node.sockets()) {
                ExecResult discovery = remote.execute(node, discoveryCommand(socket, includeWindows, includePanes));
                TmuxFailure.Kind failure = TmuxFailure.classify(discovery);
                if (failure == TmuxFailure.Kind.MISSING_BINARY) {
                    return failureSnapshot(node, NodeStatus.NO_TMUX, now, cached);
                }
                if (failure == TmuxFailure.Kind.REMOTE_EXECUTION) {
                    return failureSnapshot(node, NodeStatus.SSH_FAILED, now, cached);
                }
                if (failure == TmuxFailure.Kind.NO_SERVER) {
                    continue;
                }
                if (failure != TmuxFailure.Kind.OK) {
                    return failureSnapshot(node, NodeStatus.SSH_FAILED, now, cached);
                }
                TmuxParser.DiscoveryOutput output = includeWindows
                        ? TmuxParser.splitDiscoveryOutput(discovery.stdout())
                        : new TmuxParser.DiscoveryOutput(discovery.stdout(), "", "");
                sessions.addAll(TmuxParser.parse(node.id(), socket, output.sessions(), output.windows(), output.panes(), now).sessions());
            }
            return save(new NodeSnapshot(node.id(), NodeStatus.ONLINE, now, sessions));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return failureSnapshot(node, NodeStatus.SSH_FAILED, now, cached);
        }
    }

    private String discoveryCommand(String socket, boolean includeWindows, boolean includePanes) {
        if (includePanes) return TmuxCommands.discover(socket);
        return includeWindows ? TmuxCommands.discoverWindows(socket) : TmuxCommands.listSessions(socket);
    }

    private NodeSnapshot failureSnapshot(NodeConfig node, NodeStatus status, Instant now, NodeSnapshot cached) {
        if (cached != null && !cached.sessions().isEmpty()) return cached.withStatus(NodeStatus.OFFLINE);
        return save(new NodeSnapshot(node.id(), status, now, List.of()));
    }

    private NodeSnapshot save(NodeSnapshot snapshot) {
        store.saveSnapshot(snapshot);
        return snapshot;
    }

    private boolean recentOffline(NodeSnapshot cached) {
        if (cached == null) return false;
        if (cached.status() != NodeStatus.SSH_FAILED && cached.status() != NodeStatus.OFFLINE) return false;
        Duration age = Duration.between(cached.lastSeenAt(), clock.instant());
        return !age.isNegative() && age.compareTo(RECENT_OFFLINE_TTL) <= 0;
    }
}
