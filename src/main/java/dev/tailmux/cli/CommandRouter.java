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
import dev.tailmux.exec.LocalProcess;
import dev.tailmux.exec.RemoteExecutor;
import dev.tailmux.state.PropertiesStateStore;
import dev.tailmux.tmux.TmuxCommands;
import dev.tailmux.tmux.TmuxParser;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class CommandRouter {
    private static final List<String> BUILTINS = List.of("doctor", "nodes", "ls", "attach", "start", "help");

    private final TailmuxConfig config;
    private final PropertiesStateStore store;
    private final RemoteExecutor remote;
    private final Clock clock;
    private final Console console;
    private final LocalProcess localProcess;

    public CommandRouter(TailmuxConfig config, PropertiesStateStore store, RemoteExecutor remote, Clock clock, Console console) {
        this(config, store, remote, clock, console, new LocalProcess());
    }

    public CommandRouter(TailmuxConfig config, PropertiesStateStore store, RemoteExecutor remote, Clock clock, Console console, LocalProcess localProcess) {
        this.config = config;
        this.store = store;
        this.remote = remote;
        this.clock = clock;
        this.console = console;
        this.localProcess = localProcess;
    }

    public ParsedCommand classify(List<String> args) {
        if (args.isEmpty()) {
            return new ParsedCommand("help", List.of(), Optional.empty());
        }
        String first = args.getFirst();
        if (BUILTINS.contains(first)) {
            return parseBuiltin(first, args.subList(1, args.size()));
        }
        return new ParsedCommand("workspace", List.of(first), Optional.empty());
    }

    public int run(List<String> args) {
        try {
            store.ensureWritable();
            ParsedCommand parsed = classify(args);
            store.appendEvent(clock.instant(), "command", Map.of("command", parsed.command()));
            return switch (parsed.command()) {
                case "doctor" -> new DoctorCommand(config, remote, localProcess, console).run(parsed.args().contains("--network"));
                case "nodes" -> nodes();
                case "ls" -> list(parsed.args().contains("--windows") || parsed.args().contains("--panes"), parsed.args().contains("--panes"));
                case "attach" -> attachCommand(parsed.args());
                case "start" -> startCommand(parsed.args(), parsed.home());
                case "workspace" -> smartWorkspace(WorkspaceName.parse(parsed.args().getFirst()), Optional.empty());
                default -> usage();
            };
        } catch (TailmuxException e) {
            console.err(e.getMessage());
            return e.exitCode();
        } catch (IllegalArgumentException e) {
            console.err("FAIL " + e.getMessage());
            return ExitCodes.CONFIG_ERROR;
        } catch (IOException e) {
            console.err("FAIL io: " + e.getMessage());
            return ExitCodes.GENERAL_FAILURE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            console.err("FAIL interrupted");
            return ExitCodes.GENERAL_FAILURE;
        }
    }

    private ParsedCommand parseBuiltin(String command, List<String> rest) {
        if (!"start".equals(command)) {
            return new ParsedCommand(command, rest, Optional.empty());
        }
        ArrayList<String> args = new ArrayList<String> ();
        Optional<String> home = Optional.empty();
        for (int i = 0; i < rest.size(); i++) {
            String arg = rest.get(i);
            if ("--home".equals(arg)) {
                if (i + 1 >= rest.size()) {
                    throw new IllegalArgumentException("--home requires a node");
                }
                home = Optional.of(rest.get(++i));
            } else {
                args.add(arg);
            }
        }
        return new ParsedCommand(command, args, home);
    }

    private int usage() {
        console.out("usage: tailmux doctor|nodes|ls|attach|start|<workspace>");
        return ExitCodes.SUCCESS;
    }

    private int nodes() {
        Renderers.renderNodes(console, config, discovery().discoverAll(config.nodeConfigs(), false), clock);
        return ExitCodes.SUCCESS;
    }

    private int list(boolean includeWindows, boolean includePanes) {
        Renderers.renderLs(console, discovery().discoverAll(config.nodeConfigs(), true), clock, includeWindows, includePanes);
        return ExitCodes.SUCCESS;
    }

    private int attachCommand(List<String> args) throws IOException, InterruptedException {
        String target = firstArg(args, "attach: target");
        if (target.contains(":")) {
            return attachSelector(Selector.parse(target));
        }
        return smartWorkspace(WorkspaceName.parse(target), Optional.empty());
    }

    private int startCommand(List<String> args, Optional<String> home) throws IOException, InterruptedException {
        return smartWorkspace(WorkspaceName.parse(firstArg(args, "start: workspace name")), home.map(NodeId::parse));
    }

    private int smartWorkspace(WorkspaceName workspaceName, Optional<NodeId> explicitHome) throws IOException, InterruptedException {
        Optional<Workspace> registered = store.loadWorkspace(workspaceName.value());
        if (registered.isPresent()) {
            Workspace workspace = registered.get();
            NodeConfig node = config.node(workspace.home());
            ensureTmuxAvailable(node);
            ensureSession(node, workspace.socket(), workspace.session());
            return rememberAndAttach("Found existing workspace:", workspace.name(), node, workspace.session(), workspace.socket(), workspace.createdAt());
        }

        ArrayList<NodeSession> matches = new ArrayList<NodeSession> ();
        ArrayList<NodeConfig> healthy = new ArrayList<NodeConfig> ();
        List<NodeConfig> nodes = config.nodeConfigs();
        List<NodeSnapshot> snapshots = discovery().discoverAll(config.nodeConfigs(), true);
        for (int i = 0; i < snapshots.size(); i++) {
            NodeSnapshot snapshot = snapshots.get(i);
            NodeConfig node = nodes.get(i);
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

    private String firstArg(List<String> args, String label) {
        if (args.isEmpty()) {
            throw new TailmuxException(ExitCodes.CONFIG_ERROR, "FAIL " + label + " is required");
        }
        return args.getFirst();
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

    private NodeConfig chooseHome(Optional<NodeId> explicitHome, List<NodeConfig> healthy) {
        if (explicitHome.isPresent()) {
            NodeConfig node = config.node(explicitHome.get());
            if (healthy.stream().anyMatch(candidate -> candidate.id().equals(node.id()))) return node;
            throw new TailmuxException(ExitCodes.NO_HEALTHY_HOME_NODE, "FAIL " + node.id().value() + " is not healthy for workspace creation");
        }
        Optional<NodeConfig> chosen = healthy.stream().filter(node -> node.id().equals(config.defaultHome())).findFirst().or(() -> healthy.stream().findFirst());
        return chosen.orElseThrow(() -> new TailmuxException(ExitCodes.NO_HEALTHY_HOME_NODE, "FAIL no healthy home node is available"));
    }

    private void ensureTmuxAvailable(NodeConfig node) throws IOException, InterruptedException {
        ExecResult result = remote.execute(node, "command -v tmux");
        if (result.ok()) {
            return;
        }
        if (result.exitCode() == 255) {
            throw new TailmuxException(ExitCodes.REMOTE_EXECUTION_ERROR,
                    "FAIL " + node.id().value() + ": tailscale ssh could not execute remote command.\nTry:\n  tailscale ssh " + node.host() + " 'echo ok'");
        }
        throw new TailmuxException(ExitCodes.TMUX_ERROR, "FAIL " + node.id().value() + ": remote tmux not found");
    }

    private void ensureSession(NodeConfig node, String socket, String session) throws IOException, InterruptedException {
        ExecResult has = remote.execute(node, TmuxCommands.hasSession(socket, session));
        if (has.ok()) {
            return;
        }
        ExecResult created = remote.execute(node, TmuxCommands.newSession(socket, session));
        if (!created.ok()) {
            throw new TailmuxException(ExitCodes.TMUX_ERROR, "FAIL " + node.id().value() + ": could not create tmux session " + session + ": " + created.errorText());
        }
    }

    private int attachSelector(Selector selector) throws IOException, InterruptedException {
        NodeConfig node = config.node(selector.node());
        ensureTmuxAvailable(node);
        String socket = resolveSessionSocket(node, selector.session());
        if (selector.window().isPresent()) {
            ExecResult select = remote.execute(node, TmuxCommands.selectWindow(socket, selector.session(), selector.window().get()));
            if (!select.ok()) {
                throw new TailmuxException(ExitCodes.TMUX_ERROR, "FAIL " + node.id().value() + ": could not select tmux window: " + select.errorText());
            }
        }
        if (selector.pane().isPresent()) {
            ExecResult select = remote.execute(node, TmuxCommands.selectPane(socket, selector.session(), selector.window().orElseThrow(), selector.pane().get()));
            if (!select.ok()) {
                throw new TailmuxException(ExitCodes.TMUX_ERROR, "FAIL " + node.id().value() + ": could not select tmux pane: " + select.errorText());
            }
        }
        return attach(node, socket, selector.session());
    }

    private int attach(NodeConfig node, String socket, String session) throws IOException, InterruptedException {
        int exit = remote.attachInteractive(node, TmuxCommands.attachSession(socket, session));
        if (exit != 0) {
            throw new TailmuxException(ExitCodes.REMOTE_EXECUTION_ERROR, "FAIL " + node.id().value() + ": attach failed with exit " + exit);
        }
        return ExitCodes.SUCCESS;
    }

    private String resolveSessionSocket(NodeConfig node, String session) throws IOException, InterruptedException {
        ArrayList<String> matches = new ArrayList<String> ();
        for (String socket : node.sockets()) {
            ExecResult result = remote.execute(node, TmuxCommands.listSessions(socket));
            if (TmuxParser.isNoServer(result)) {
                continue;
            }
            if (!result.ok()) {
                throw new TailmuxException(ExitCodes.TMUX_ERROR, "FAIL " + node.id().value() + ": could not list tmux sessions: " + result.errorText());
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

    private DiscoveryService discovery() {
        return new DiscoveryService(store, remote, clock);
    }

    private record NodeSession(NodeConfig node, TmuxSession session) {
    }
}
