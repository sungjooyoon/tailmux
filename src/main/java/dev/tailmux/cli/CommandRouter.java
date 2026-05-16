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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class CommandRouter {
    private static final Duration RECENT_OFFLINE_TTL = Duration.ofSeconds(60);
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
            return switch (parsed.command()) {
                case "doctor" -> parsed.args().contains("--network") ? doctorNetwork() : doctor();
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

    private int doctor() throws IOException, InterruptedException {
        boolean failed = false;
        boolean tailscaleExists = localProcess.commandExists("tailscale");
        boolean tmuxExists = localProcess.commandExists("tmux");
        console.out("OK    local java " + System.getProperty("java.version"));
        console.out((tailscaleExists ? "OK    " : "FAIL  ") + "local tailscale " + (tailscaleExists ? "found" : "not found"));
        console.out((tmuxExists ? "OK    " : "WARN  ") + "local tmux " + (tmuxExists ? "found" : "not found"));
        failed = failed || !tailscaleExists;
        console.out("OK    config loaded");
        console.out("OK    state dir writable");

        for (NodeConfig node : config.nodeConfigs()) {
            ExecResult ssh = remote.execute(node, "echo ok");
            if (!ssh.ok()) {
                failed = true;
                printSshFailure(node, ssh);
                continue;
            }
            console.out("OK    " + node.id().value() + " tailscale ssh");

            ExecResult tmux = remote.execute(node, "command -v tmux");
            if (!tmux.ok()) {
                failed = true;
                console.out("FAIL  " + node.id().value() + " remote tmux missing");
                continue;
            }
            console.out("OK    " + node.id().value() + " remote tmux found");

            for (String socket : node.sockets()) {
                ExecResult sessions = remote.execute(node, TmuxCommands.listSessions(socket));
                String label = node.id().value() + " tmux " + socket + " list-sessions";
                if (TmuxParser.isNoServer(sessions)) {
                    console.out("WARN  " + node.id().value() + " tmux " + socket + " no server currently running");
                } else if (!sessions.ok()) {
                    failed = true;
                    console.out("FAIL  " + label + " failed: " + sessions.errorText());
                } else {
                    console.out("OK    " + label);
                }
            }
        }
        return failed ? ExitCodes.REMOTE_EXECUTION_ERROR : ExitCodes.SUCCESS;
    }

    private int doctorNetwork() throws IOException, InterruptedException {
        boolean failed = false;
        ExecResult status = localProcess.capture(List.of("tailscale", "status"));
        if (status.ok()) {
            console.out("OK    tailscale status");
        } else {
            failed = true;
            console.out("FAIL  tailscale status failed: " + status.errorText());
            console.out("Try:");
            console.out("  tailscale status");
        }
        for (NodeConfig node : config.nodeConfigs()) {
            String host = node.host();
            ExecResult ping = localProcess.capture(List.of("tailscale", "ping", "--c=1", host));
            if (ping.ok()) {
                console.out("OK    " + node.id().value() + " tailscale ping");
            } else {
                console.out("WARN  " + node.id().value() + " tailscale ping failed: " + ping.errorText());
                console.out("Try:");
                console.out("  tailscale ping --c=1 " + host);
            }
            if (localProcess.commandExists("dscacheutil")) {
                ExecResult resolver = localProcess.capture(List.of("dscacheutil", "-q", "host", "-a", "name", host));
                if (resolver.ok() && !resolver.stdout().isBlank()) {
                    console.out("OK    " + node.id().value() + " macOS resolver resolved host");
                } else {
                    console.out("WARN  " + node.id().value() + " macOS resolver did not resolve host");
                    console.out("Try:");
                    console.out("  dscacheutil -q host -a name " + host);
                }
            }
            if (localProcess.commandExists("dig")) {
                ExecResult dns = localProcess.capture(List.of("dig", "@100.100.100.100", host));
                if (dns.ok() && dns.stdout().contains(" IN A")) {
                    console.out("OK    " + node.id().value() + " tailscale dns resolved host");
                } else {
                    console.out("WARN  " + node.id().value() + " tailscale dns did not resolve host");
                    console.out("Try:");
                    console.out("  dig @100.100.100.100 " + host);
                }
            }
        }
        return failed ? ExitCodes.REMOTE_EXECUTION_ERROR : ExitCodes.SUCCESS;
    }

    private void printSshFailure(NodeConfig node, ExecResult result) {
        String error = result.errorText();
        if (isResolverFailure(error)) {
            console.out("FAIL  " + node.id().value() + " magicdns resolution failed: " + error);
            console.out("Try:");
            console.out("  tailmux doctor --network");
            console.out("  tailscale ssh " + config.sshTarget(node) + " 'echo ok'");
            return;
        }
        if (result.exitCode() == 124 || error.toLowerCase().contains("timed out")) {
            console.out("FAIL  " + node.id().value() + " tailscale ssh timed out: " + error);
            console.out("Try:");
            console.out("  tailscale ping --c=1 " + node.host());
            return;
        }
        console.out("FAIL  " + node.id().value() + " tailscale ssh failed: " + error);
        console.out("Try:");
        console.out("  tailscale ssh " + config.sshTarget(node) + " 'echo ok'");
    }

    private boolean isResolverFailure(String error) {
        String lower = error.toLowerCase();
        return lower.contains("could not resolve hostname") || lower.contains("nodename nor servname provided");
    }

    private int nodes() {
        Renderers.renderNodes(console, config, discoverAll(false), clock);
        return ExitCodes.SUCCESS;
    }

    private int list(boolean includeWindows, boolean includePanes) {
        Renderers.renderLs(console, discoverAll(true), clock, includeWindows, includePanes);
        return ExitCodes.SUCCESS;
    }

    private List<NodeSnapshot> discoverAll(boolean includeWindows) {
        List<NodeConfig> nodes = config.nodeConfigs();
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, nodes.size()));
        try {
            List<Future<NodeSnapshot>> futures = nodes.stream().map(node -> executor.submit(() -> discoverOrCached(node, includeWindows))).toList();
            ArrayList<NodeSnapshot> snapshots = new ArrayList<NodeSnapshot> ();
            for (Future<NodeSnapshot> future : futures) {
                try {
                    snapshots.add(future.get());
                } catch (Exception e) {
                    throw new TailmuxException(ExitCodes.GENERAL_FAILURE, "FAIL discovery: " + e.getMessage(), e);
                }
            }
            return List.copyOf(snapshots);
        } finally {
            executor.shutdownNow();
        }
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
        List<NodeSnapshot> snapshots = discoverAll(true);
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
            ArrayList<TmuxSession> sessions = new ArrayList<TmuxSession> ();
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

    private record NodeSession(NodeConfig node, TmuxSession session) {
    }
}
