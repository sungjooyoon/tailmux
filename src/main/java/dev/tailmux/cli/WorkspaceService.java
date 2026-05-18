package dev.tailmux.cli;

import dev.tailmux.config.NodeConfig;
import dev.tailmux.config.TailmuxConfig;
import dev.tailmux.core.NodeId;
import dev.tailmux.core.NodeSnapshot;
import dev.tailmux.core.NodeStatus;
import dev.tailmux.core.Selector;
import dev.tailmux.core.TailmuxException;
import dev.tailmux.core.TmuxSession;
import dev.tailmux.core.Workspace;
import dev.tailmux.core.WorkspaceName;
import dev.tailmux.exec.ExecResult;
import dev.tailmux.exec.RemoteExecutor;
import dev.tailmux.state.PropertiesStateStore;
import dev.tailmux.tmux.TmuxCommands;
import dev.tailmux.tmux.TmuxFailure;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class WorkspaceService {
    private final TailmuxConfig config;
    private final PropertiesStateStore store;
    private final RemoteExecutor remote;
    private final Clock clock;
    private final Console console;
    private final DiscoveryService discovery;

    WorkspaceService(TailmuxConfig config, PropertiesStateStore store, RemoteExecutor remote, Clock clock, Console console, DiscoveryService discovery) {
        this.config = config;
        this.store = store;
        this.remote = remote;
        this.clock = clock;
        this.console = console;
        this.discovery = discovery;
    }

    int attachCommand(List<String> args) throws IOException, InterruptedException {
        String target = firstArg(args, "attach: target");
        return target.contains(":")
                ? attachSelector(Selector.parse(target))
                : smartWorkspace(WorkspaceName.parse(target), null);
    }

    int startCommand(List<String> args, String home) throws IOException, InterruptedException {
        return smartWorkspace(WorkspaceName.parse(firstArg(args, "start: workspace name")), home == null ? null : NodeId.parse(home));
    }

    int smartWorkspace(WorkspaceName workspaceName, NodeId explicitHome) throws IOException, InterruptedException {
        Workspace workspace = store.loadWorkspace(workspaceName.value());
        if (workspace != null) {
            NodeConfig node = config.node(workspace.home());
            ensureSession(node, workspace.socket(), workspace.session());
            return rememberAndAttach("Found existing workspace:", workspace.name(), node, workspace.session(), workspace.socket(), workspace.createdAt());
        }

        NodeSession match = null;
        NodeSession offlineMatch = null;
        int matchCount = 0;
        int offlineMatchCount = 0;
        NodeConfig firstHealthy = null;
        NodeConfig defaultHealthy = null;
        NodeConfig explicitHealthy = explicitHome == null ? null : config.node(explicitHome);
        boolean explicitHomeHealthy = false;
        for (DiscoveredNode discovered : discovery.discoverNodes(config.nodeConfigs(), false)) {
            NodeSnapshot snapshot = discovered.snapshot();
            NodeConfig node = discovered.node();
            NodeStatus status = snapshot.status();
            if (status == NodeStatus.ONLINE) {
                if (firstHealthy == null) firstHealthy = node;
                if (node.id().equals(config.defaultHome())) defaultHealthy = node;
                if (explicitHealthy != null && node.id().equals(explicitHealthy.id())) explicitHomeHealthy = true;
            }
            for (TmuxSession session : snapshot.sessions()) {
                if (!session.name().equals(workspaceName.value())) continue;
                if (status == NodeStatus.ONLINE) {
                    matchCount++;
                    if (match == null) match = new NodeSession(node, session);
                } else {
                    offlineMatchCount++;
                    if (offlineMatch == null) offlineMatch = new NodeSession(node, session);
                }
            }
        }

        if (offlineMatchCount > 0) {
            throw new TailmuxException(ExitCodes.CONFIG_ERROR,
                    "FAIL workspace " + workspaceName.value() + " was last seen on offline node " + offlineMatch.node().id().value()
                            + "; refusing to create a duplicate. Reconnect that node or remove stale Tailmux state.");
        }
        if (matchCount > 1) {
            throw new TailmuxException(ExitCodes.CONFIG_ERROR,
                    "FAIL workspace " + workspaceName.value() + " exists on multiple nodes. Use tailmux attach node:session.");
        }
        if (matchCount == 1) {
            return rememberAndAttach("Found existing workspace:", workspaceName, match.node(), match.session().name(), match.session().socket(), clock.instant());
        }

        NodeConfig home = chooseHome(explicitHealthy, explicitHomeHealthy, defaultHealthy, firstHealthy);
        ensureSession(home, home.defaultSocket(), workspaceName.value());
        return rememberAndAttach("Created workspace:", workspaceName, home, workspaceName.value(), home.defaultSocket(), clock.instant());
    }

    private int attachSelector(Selector selector) throws IOException, InterruptedException {
        NodeConfig node = config.node(selector.node());
        String socket = resolveSessionSocket(node, selector.session());
        if (selector.hasPane()) {
            ExecResult select = remote.execute(node, TmuxCommands.selectWindowAndPane(socket, selector.session(), selector.windowIndex(), selector.paneIndex()));
            if (!select.ok()) {
                throw new TailmuxException(ExitCodes.TMUX_ERROR, "FAIL " + node.id().value() + ": could not select tmux pane: " + select.errorText());
            }
        } else if (selector.hasWindow()) {
            ExecResult select = remote.execute(node, TmuxCommands.selectWindow(socket, selector.session(), selector.windowIndex()));
            if (!select.ok()) {
                throw new TailmuxException(ExitCodes.TMUX_ERROR, "FAIL " + node.id().value() + ": could not select tmux window: " + select.errorText());
            }
        }
        return attach(node, socket, selector.session());
    }

    private NodeConfig chooseHome(NodeConfig explicitHome, boolean explicitHomeHealthy, NodeConfig defaultHealthy, NodeConfig firstHealthy) {
        if (explicitHome != null) {
            if (explicitHomeHealthy) return explicitHome;
            throw new TailmuxException(ExitCodes.NO_HEALTHY_HOME_NODE, "FAIL " + explicitHome.id().value() + " is not healthy for workspace creation");
        }
        if (defaultHealthy != null) return defaultHealthy;
        if (firstHealthy != null) return firstHealthy;
        throw new TailmuxException(ExitCodes.NO_HEALTHY_HOME_NODE, "FAIL no healthy home node is available");
    }

    private void ensureSession(NodeConfig node, String socket, String session) throws IOException, InterruptedException {
        ExecResult result = remote.execute(node, TmuxCommands.ensureSession(socket, session));
        if (result.ok()) return;
        throw classifyTmuxCommandFailure(node, "could not ensure tmux session " + session, result);
    }

    private int rememberAndAttach(String header, WorkspaceName workspace, NodeConfig node, String session, String socket, Instant createdAt) throws IOException, InterruptedException {
        store.saveWorkspace(workspace.value(), node.id(), session, socket, createdAt, clock.instant());
        store.appendEvent(clock.instant(), "workspace",
                "workspace", workspace.value(),
                "node", node.id().value(),
                "socket", socket,
                "session", session,
                "transport", "ssh");
        printWorkspace(header, workspace.value(), node.id());
        return attach(node, socket, session);
    }

    private void printWorkspace(String header, String workspace, NodeId node) {
        console.out(header);
        console.out("  " + workspace + " on " + node.value());
        console.out("");
        console.out("Attaching...");
    }

    private int attach(NodeConfig node, String socket, String session) throws IOException, InterruptedException {
        int exit = remote.attachInteractive(node, TmuxCommands.attachSession(socket, session));
        if (exit != 0) {
            throw new TailmuxException(ExitCodes.REMOTE_EXECUTION_ERROR, "FAIL " + node.id().value() + ": attach failed with exit " + exit);
        }
        return ExitCodes.SUCCESS;
    }

    private String resolveSessionSocket(NodeConfig node, String session) throws IOException, InterruptedException {
        if (node.sockets().size() == 1) {
            String socket = node.sockets().getFirst();
            ExecResult result = remote.execute(node, TmuxCommands.hasSession(socket, session));
            if (result.ok()) return socket;
            TmuxFailure.Kind failure = TmuxFailure.classify(result);
            if (failure == TmuxFailure.Kind.REMOTE_EXECUTION || failure == TmuxFailure.Kind.MISSING_BINARY) {
                throw classifyTmuxCommandFailure(node, "could not find tmux session " + session, result, failure);
            }
            throw new TailmuxException(ExitCodes.TMUX_ERROR, "FAIL " + node.id().value() + ": tmux session not found on configured sockets: " + session);
        }

        String match = null;
        int matches = 0;
        for (String socket : node.sockets()) {
            ExecResult result = remote.execute(node, TmuxCommands.hasSession(socket, session));
            if (result.ok()) {
                matches++;
                if (matches > 1) {
                    throw new TailmuxException(ExitCodes.CONFIG_ERROR, "FAIL " + node.id().value() + ": session " + session + " exists on multiple sockets; use a Tailmux workspace or remove the ambiguity");
                }
                match = socket;
            } else {
                TmuxFailure.Kind failure = TmuxFailure.classify(result);
                if (failure == TmuxFailure.Kind.REMOTE_EXECUTION || failure == TmuxFailure.Kind.MISSING_BINARY) {
                    throw classifyTmuxCommandFailure(node, "could not find tmux session " + session, result, failure);
                }
            }
        }
        if (matches == 0) {
            throw new TailmuxException(ExitCodes.TMUX_ERROR, "FAIL " + node.id().value() + ": tmux session not found on configured sockets: " + session);
        }
        return match;
    }

    private TailmuxException classifyTmuxCommandFailure(NodeConfig node, String action, ExecResult result) {
        return classifyTmuxCommandFailure(node, action, result, TmuxFailure.classify(result));
    }

    private TailmuxException classifyTmuxCommandFailure(NodeConfig node, String action, ExecResult result, TmuxFailure.Kind failure) {
        if (failure == TmuxFailure.Kind.REMOTE_EXECUTION) {
            return new TailmuxException(ExitCodes.REMOTE_EXECUTION_ERROR,
                    "FAIL " + node.id().value() + ": tailscale ssh could not execute remote command.\nTry:\n  tailscale ssh " + config.sshTarget(node) + " 'echo ok'");
        }
        if (failure == TmuxFailure.Kind.MISSING_BINARY) {
            return new TailmuxException(ExitCodes.TMUX_ERROR, "FAIL " + node.id().value() + ": remote tmux not found");
        }
        return new TailmuxException(ExitCodes.TMUX_ERROR, "FAIL " + node.id().value() + ": " + action + ": " + result.errorText());
    }

    private String firstArg(List<String> args, String label) {
        if (args.isEmpty()) {
            throw new TailmuxException(ExitCodes.CONFIG_ERROR, "FAIL " + label + " is required");
        }
        return args.getFirst();
    }

    private record NodeSession(NodeConfig node, TmuxSession session) {
    }
}
