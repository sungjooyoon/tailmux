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
import dev.tailmux.tmux.TmuxParser;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
        return discoverNodes(nodes, includeWindows).stream().map(DiscoveredNode::snapshot).toList();
    }

    List<DiscoveredNode> discoverNodes(List<NodeConfig> nodes, boolean includeWindows) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<DiscoveredNode>> futures = nodes.stream()
                    .map(node -> executor.submit(() -> new DiscoveredNode(node, discoverOrCached(node, includeWindows))))
                    .toList();
            ArrayList<DiscoveredNode> discovered = new ArrayList<>();
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
            return List.copyOf(discovered);
        }
    }

    private NodeSnapshot discoverOrCached(NodeConfig node, boolean includeWindows) {
        Optional<NodeSnapshot> recentOffline = recentOfflineSnapshot(node);
        if (recentOffline.isPresent()) return recentOffline.get().withStatus(NodeStatus.OFFLINE);
        NodeSnapshot snapshot = discover(node, includeWindows);
        if (snapshot.status() == NodeStatus.ONLINE || snapshot.status() == NodeStatus.NO_TMUX) {
            return snapshot;
        }
        return store.loadSnapshot(node.id())
                .map(cached -> cached.withStatus(NodeStatus.OFFLINE))
                .orElse(snapshot.withStatus(NodeStatus.OFFLINE));
    }

    private NodeSnapshot discover(NodeConfig node, boolean includeWindows) {
        Instant now = clock.instant();
        try {
            ArrayList<TmuxSession> sessions = new ArrayList<>();
            for (String socket : node.sockets()) {
                ExecResult discovery = remote.execute(node, includeWindows
                        ? TmuxCommands.discover(socket)
                        : TmuxCommands.listSessions(socket));
                if (isTmuxMissing(discovery)) {
                    return failureSnapshot(node, NodeStatus.NO_TMUX, now);
                }
                if (discovery.exitCode() == 255 || discovery.exitCode() == 124) {
                    return failureSnapshot(node, NodeStatus.SSH_FAILED, now);
                }
                if (TmuxParser.isNoServer(discovery)) {
                    continue;
                }
                if (!discovery.ok()) {
                    return failureSnapshot(node, NodeStatus.SSH_FAILED, now);
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
            return failureSnapshot(node, NodeStatus.SSH_FAILED, now);
        }
    }

    private boolean isTmuxMissing(ExecResult result) {
        String text = (result.stderr() + "\n" + result.stdout()).toLowerCase();
        return result.exitCode() == 127
                || text.contains("tmux: command not found")
                || text.contains("tmux: not found")
                || text.contains("command not found: tmux");
    }

    private NodeSnapshot failureSnapshot(NodeConfig node, NodeStatus status, Instant now) {
        return store.loadSnapshot(node.id())
                .filter(snapshot -> !snapshot.sessions().isEmpty())
                .map(snapshot -> snapshot.withStatus(NodeStatus.OFFLINE))
                .orElseGet(() -> save(new NodeSnapshot(node.id(), status, now, List.of())));
    }

    private NodeSnapshot save(NodeSnapshot snapshot) {
        store.saveSnapshot(snapshot);
        return snapshot;
    }

    private Optional<NodeSnapshot> recentOfflineSnapshot(NodeConfig node) {
        return store.loadSnapshot(node.id())
                .filter(snapshot -> snapshot.status() == NodeStatus.SSH_FAILED || snapshot.status() == NodeStatus.OFFLINE)
                .filter(snapshot -> {
                    Duration age = Duration.between(snapshot.lastSeenAt(), clock.instant());
                    return !age.isNegative() && age.compareTo(RECENT_OFFLINE_TTL) <= 0;
                });
    }
}
