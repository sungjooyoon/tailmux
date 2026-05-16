package dev.tailmux.exec;

import dev.tailmux.config.NodeConfig;
import dev.tailmux.tmux.TmuxCommands;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FakeRemoteExecutor implements RemoteExecutor {
    private final Map<String, ExecResult> responses = new LinkedHashMap<>();
    private final Map<String, ExecResult> nodeFailures = new LinkedHashMap<>();
    private final Map<String, ArrayList<String>> commands = new LinkedHashMap<>();
    private final ArrayList<String> interactive = new ArrayList<>();

    public synchronized void when(String node, String command, ExecResult result) {
        responses.put(key(node, command), result);
    }

    public synchronized void failNode(String node, ExecResult result) {
        nodeFailures.put(node, result);
    }

    public synchronized List<String> commandsFor(String node) {
        return List.copyOf(commands.getOrDefault(node, new ArrayList<>()));
    }

    public synchronized List<String> interactiveCommands() {
        return List.copyOf(interactive);
    }

    @Override
    public synchronized ExecResult execute(NodeConfig node, String command) {
        commands.computeIfAbsent(node.id().value(), ignored -> new ArrayList<>()).add(command);
        ExecResult nodeFailure = nodeFailures.get(node.id().value());
        if (nodeFailure != null) {
            return nodeFailure;
        }
        for (String socket : node.sockets()) {
            if (command.equals(TmuxCommands.discover(socket))) {
                ExecResult sessions = responses.get(key(node.id().value(), TmuxCommands.listSessions(socket)));
                ExecResult windows = responses.get(key(node.id().value(), TmuxCommands.listWindows(socket)));
                ExecResult panes = responses.get(key(node.id().value(), TmuxCommands.listPanes(socket)));
                if (sessions != null) {
                    if (!sessions.ok()) {
                        return sessions;
                    }
                    String stdout = sessions.stdout()
                            + TmuxCommands.DISCOVERY_WINDOWS_MARKER + "\n"
                            + (windows == null ? "" : windows.stdout())
                            + "\n"
                            + TmuxCommands.DISCOVERY_PANES_MARKER + "\n"
                            + (panes == null ? "" : panes.stdout());
                    int exit = panes != null ? panes.exitCode() : windows == null ? 0 : windows.exitCode();
                    String stderr = panes != null ? panes.stderr() : windows == null ? "" : windows.stderr();
                    return new ExecResult(exit, stdout, stderr);
                }
            }
        }
        return responses.getOrDefault(key(node.id().value(), command), ExecResult.failure(255, "", "ssh failed"));
    }

    @Override
    public synchronized int attachInteractive(NodeConfig node, String command) {
        interactive.add(node.id().value() + ":" + command);
        return 0;
    }

    private static String key(String node, String command) {
        return node + "\n" + command;
    }
}
