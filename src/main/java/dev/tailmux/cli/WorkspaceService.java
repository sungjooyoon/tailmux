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
import dev.tailmux.tmux.TmuxParser;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
                : smartWorkspace(WorkspaceName.parse(target), Optional.empty());
    }

    int startCommand(List<String> args, Optional<String> home) throws IOException, InterruptedException {
        return smartWorkspace(WorkspaceName.parse(firstArg(args, "start: workspace name")), home.map(NodeId::parse));
    }

    int smartWorkspace(WorkspaceName workspaceName, Optional<NodeId> explicitHome) throws IOException, InterruptedException {
        Optional<Workspace> registered = store.loadWorkspace(workspaceName.value());
        if (registered.isPresent()) {
            Workspace workspace = registered.get();
            NodeConfig node = config.node(workspace.home());
            ensureSession(node, workspace.socket(), workspace.session());
            return rememberAndAttach("Found existing workspace:", workspace.name(), node, workspace.session(), workspace.socket(), workspace.createdAt());
        }

        ArrayList<NodeSession> matches = new ArrayList<>();
        ArrayList<NodeConfig> healthy = new ArrayList<>();
        for (DiscoveredNode discovered : discovery.discoverNodes(config.nodeConfigs(), true)) {
            NodeSnapshot snapshot = discovered.snapshot();
            NodeConfig node = discovered.node();
            if (snapshot.status() == NodeStatus.ONLINE) {
                healthy.add(node);
                for (TmuxSession session : snapshot.sessions()) {
                    if (session.name().equals(workspaceName.value())) {
                        matches.add(new NodeSession(node, session));
                    }
                }
            }
        }

        if (matches.size() > 1) {
            throw new TailmuxException(ExitCodes.CONFIG_ERROR,
                    "FAIL workspace " + workspaceName.value() + " exists on multiple nodes. Use tailmux attach node:session.");
        }
        if (matches.size() == 1) {
            NodeSession match = matches.getFirst();
            return rememberAndAttach("Found existing workspace:", workspaceName, match.node(), match.session().name(), match.session().socket(), clock.instant());
        }

        NodeConfig home = chooseHome(explicitHome, healthy);
        ensureSession(home, home.defaultSocket(), workspaceName.value());
        return rememberAndAttach("Created workspace:", workspaceName, home, workspaceName.value(), home.defaultSocket(), clock.instant());
    }

    private int attachSelector(Selector selector) throws IOException, InterruptedException {
        NodeConfig node = config.node(selector.node());
        String socket = resolveSessionSocket(node, selector.session());
        if (selector.pane().isPresent()) {
            ExecResult select = remote.execute(node, TmuxCommands.selectWindowAndPane(socket, selector.session(), selector.window().orElseThrow(), selector.pane().get()));
            if (!select.ok()) {
                throw new TailmuxException(ExitCodes.TMUX_ERROR, "FAIL " + node.id().value() + ": could not select tmux pane: " + select.errorText());
            }
        } else if (selector.window().isPresent()) {
            ExecResult select = remote.execute(node, TmuxCommands.selectWindow(socket, selector.session(), selector.window().get()));
            if (!select.ok()) {
                throw new TailmuxException(ExitCodes.TMUX_ERROR, "FAIL " + node.id().value() + ": could not select tmux window: " + select.errorText());
            }
        }
        return attach(node, socket, selector.session());
    }

    private NodeConfig chooseHome(Optional<NodeId> explicitHome, List<NodeConfig> healthy) {
        if (explicitHome.isPresent()) {
            NodeConfig node = config.node(explicitHome.get());
            if (healthy.stream().anyMatch(candidate -> candidate.id().equals(node.id()))) return node;
            throw new TailmuxException(ExitCodes.NO_HEALTHY_HOME_NODE, "FAIL " + node.id().value() + " is not healthy for workspace creation");
        }
        return healthy.stream()
                .filter(node -> node.id().equals(config.defaultHome()))
                .findFirst()
                .or(() -> healthy.stream().findFirst())
                .orElseThrow(() -> new TailmuxException(ExitCodes.NO_HEALTHY_HOME_NODE, "FAIL no healthy home node is available"));
    }

    private void ensureSession(NodeConfig node, String socket, String session) throws IOException, InterruptedException {
        ExecResult result = remote.execute(node, TmuxCommands.ensureSession(socket, session));
        if (result.ok()) return;
        throw classifyTmuxCommandFailure(node, "could not ensure tmux session " + session, result);
    }

    private int rememberAndAttach(String header, WorkspaceName workspace, NodeConfig node, String session, String socket, Instant createdAt) throws IOException, InterruptedException {
        store.saveWorkspace(workspace.value(), node.id(), session, socket, createdAt, clock.instant());
        store.appendEvent(clock.instant(), "workspace", Map.of(
                "workspace", workspace.value(),
                "node", node.id().value(),
                "socket", socket,
                "session", session,
                "transport", "ssh"
        ));
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
        ArrayList<String> matches = new ArrayList<>();
        for (String socket : node.sockets()) {
            ExecResult result = remote.execute(node, TmuxCommands.listSessions(socket));
            if (TmuxFailure.noServer(result)) continue;
            if (!result.ok()) {
                throw classifyTmuxCommandFailure(node, "could not list tmux sessions", result);
            }
            NodeSnapshot snapshot = TmuxParser.parse(node.id(), socket, result.stdout(), "", clock.instant());
            if (snapshot.sessions().stream().anyMatch(candidate -> candidate.name().equals(session))) {
                matches.add(socket);
            }
        }
        if (matches.isEmpty()) {
            throw new TailmuxException(ExitCodes.TMUX_ERROR, "FAIL " + node.id().value() + ": tmux session not found on configured sockets: " + session);
        }
        if (matches.size() > 1) {
            throw new TailmuxException(ExitCodes.CONFIG_ERROR, "FAIL " + node.id().value() + ": session " + session + " exists on multiple sockets; use a Tailmux workspace or remove the ambiguity");
        }
        return matches.getFirst();
    }

    private TailmuxException classifyTmuxCommandFailure(NodeConfig node, String action, ExecResult result) {
        if (TmuxFailure.remoteExecution(result)) {
            return new TailmuxException(ExitCodes.REMOTE_EXECUTION_ERROR,
                    "FAIL " + node.id().value() + ": tailscale ssh could not execute remote command.\nTry:\n  tailscale ssh " + config.sshTarget(node) + " 'echo ok'");
        }
        if (TmuxFailure.missingBinary(result)) {
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
