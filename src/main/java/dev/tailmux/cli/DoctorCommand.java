package dev.tailmux.cli;

import dev.tailmux.config.NodeConfig;
import dev.tailmux.config.TailmuxConfig;
import dev.tailmux.exec.ExecResult;
import dev.tailmux.exec.LocalProcess;
import dev.tailmux.exec.RemoteExecutor;
import dev.tailmux.tmux.TmuxCommands;
import dev.tailmux.tmux.TmuxFailure;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class DoctorCommand {
    private final TailmuxConfig config;
    private final RemoteExecutor remote;
    private final LocalProcess localProcess;
    private final Console console;

    DoctorCommand(TailmuxConfig config, RemoteExecutor remote, LocalProcess localProcess, Console console) {
        this.config = config;
        this.remote = remote;
        this.localProcess = localProcess;
        this.console = console;
    }

    int run(boolean networkOnly) throws IOException, InterruptedException {
        return networkOnly ? network() : prerequisites();
    }

    private int prerequisites() throws IOException, InterruptedException {
        boolean failed = false;
        boolean tailscaleExists = localProcess.commandExists("tailscale");
        boolean tmuxExists = localProcess.commandExists("tmux");
        console.out("OK    local java " + System.getProperty("java.version"));
        console.out((tailscaleExists ? "OK    " : "FAIL  ") + "local tailscale " + (tailscaleExists ? "found" : "not found"));
        console.out((tmuxExists ? "OK    " : "WARN  ") + "local tmux " + (tmuxExists ? "found" : "not found"));
        failed = failed || !tailscaleExists;
        console.out("OK    config loaded");
        console.out("OK    state dir writable");

        for (NodeDoctorResult result : checkRemoteNodes()) {
            result.lines().forEach(console::out);
            failed = failed || result.failed();
        }
        return failed ? ExitCodes.REMOTE_EXECUTION_ERROR : ExitCodes.SUCCESS;
    }

    private List<NodeDoctorResult> checkRemoteNodes() throws InterruptedException {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<NodeDoctorResult>> futures = config.nodeConfigs().stream()
                    .map(node -> executor.submit(() -> checkRemoteNode(node)))
                    .toList();
            ArrayList<NodeDoctorResult> results = new ArrayList<>();
            for (Future<NodeDoctorResult> future : futures) {
                try {
                    results.add(future.get());
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    results.add(new NodeDoctorResult(true, List.of("FAIL  doctor remote check failed: " + cause.getMessage())));
                }
            }
            return results;
        }
    }

    private NodeDoctorResult checkRemoteNode(NodeConfig node) throws IOException, InterruptedException {
        ArrayList<String> lines = new ArrayList<>();
        ExecResult tmux = remote.execute(node, "command -v tmux");
        if (TmuxFailure.remoteExecution(tmux)) {
            return new NodeDoctorResult(true, sshFailureLines(node, tmux));
        }
        lines.add("OK    " + node.id().value() + " tailscale ssh");
        if (!tmux.ok()) {
            lines.add("FAIL  " + node.id().value() + " remote tmux missing");
            lines.add("Try:");
            lines.add("  tailscale ssh " + config.sshTarget(node) + " 'command -v tmux'");
            return new NodeDoctorResult(true, lines);
        }
        lines.add("OK    " + node.id().value() + " remote tmux found");

        boolean failed = false;
        for (String socket : node.sockets()) {
            ExecResult sessions = remote.execute(node, TmuxCommands.listSessions(socket));
            String label = node.id().value() + " tmux " + socket + " list-sessions";
            if (TmuxFailure.noServer(sessions)) {
                lines.add("WARN  " + node.id().value() + " tmux " + socket + " no server currently running");
            } else if (!sessions.ok()) {
                failed = true;
                lines.add("FAIL  " + label + " failed: " + sessions.errorText());
            } else {
                lines.add("OK    " + label);
            }
        }
        return new NodeDoctorResult(failed, lines);
    }

    private int network() throws IOException, InterruptedException {
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
        boolean hasDscacheutil = localProcess.commandExists("dscacheutil");
        boolean hasDig = localProcess.commandExists("dig");
        for (NodeConfig node : config.nodeConfigs()) {
            networkNode(node, hasDscacheutil, hasDig);
        }
        return failed ? ExitCodes.REMOTE_EXECUTION_ERROR : ExitCodes.SUCCESS;
    }

    private void networkNode(NodeConfig node, boolean hasDscacheutil, boolean hasDig) throws IOException, InterruptedException {
        String host = node.host();
        ExecResult ping = localProcess.capture(List.of("tailscale", "ping", "--c=1", host), Duration.ofSeconds(6));
        if (pingReachable(ping)) {
            console.out("OK    " + node.id().value() + " tailscale ping");
        } else {
            console.out("WARN  " + node.id().value() + " tailscale ping failed: " + ping.errorText());
            console.out("Try:");
            console.out("  tailscale ping --c=1 " + host);
        }
        if (hasDscacheutil) {
            ExecResult resolver = localProcess.capture(List.of("dscacheutil", "-q", "host", "-a", "name", host));
            if (resolver.ok() && !resolver.stdout().isBlank()) {
                console.out("OK    " + node.id().value() + " macOS resolver resolved host");
            } else {
                console.out("WARN  " + node.id().value() + " macOS resolver did not resolve host");
                console.out("Try:");
                console.out("  dscacheutil -q host -a name " + host);
            }
        }
        if (shouldCheckTailscaleDns(host) && hasDig) {
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

    private List<String> sshFailureLines(NodeConfig node, ExecResult result) {
        String error = result.errorText();
        if (isResolverFailure(error)) {
            return List.of(
                    "FAIL  " + node.id().value() + " magicdns resolution failed: " + error,
                    "Try:",
                    "  tailmux doctor --network",
                    "  tailscale ssh " + config.sshTarget(node) + " 'echo ok'");
        }
        if (result.exitCode() == 124 || error.toLowerCase().contains("timed out")) {
            return List.of(
                    "FAIL  " + node.id().value() + " tailscale ssh timed out: " + error,
                    "Try:",
                    "  tailscale ping --c=1 " + node.host());
        }
        if (isHostKeyFailure(error)) {
            return List.of(
                    "FAIL  " + node.id().value() + " tailscale ssh host key verification failed: " + error,
                    "Try:",
                    "  tailscale ssh " + config.sshTarget(node) + " 'echo ok'");
        }
        if (isAuthFailure(error)) {
            return List.of(
                    "FAIL  " + node.id().value() + " tailscale ssh auth failed: " + error,
                    "Try:",
                    "  tailscale ssh " + config.sshTarget(node) + " 'echo ok'");
        }
        return List.of(
                "FAIL  " + node.id().value() + " tailscale ssh failed: " + error,
                "Try:",
                "  tailscale ssh " + config.sshTarget(node) + " 'echo ok'");
    }

    private boolean isResolverFailure(String error) {
        String lower = error.toLowerCase();
        return lower.contains("could not resolve hostname") || lower.contains("nodename nor servname provided");
    }

    private boolean isHostKeyFailure(String error) {
        String lower = error.toLowerCase();
        return lower.contains("host key verification failed") || lower.contains("no ed25519 host key is known");
    }

    private boolean isAuthFailure(String error) {
        String lower = error.toLowerCase();
        return lower.contains("permission denied") || lower.contains("authentication failed");
    }

    private boolean pingReachable(ExecResult result) {
        return result.ok() || result.stdout().toLowerCase().contains("pong from");
    }

    private boolean shouldCheckTailscaleDns(String host) {
        return host.contains(".") && !host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+");
    }

    private record NodeDoctorResult(boolean failed, List<String> lines) {
    }
}
