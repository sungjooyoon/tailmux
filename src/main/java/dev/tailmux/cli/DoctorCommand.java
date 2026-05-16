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
import java.util.List;

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
                console.out("Try:");
                console.out("  tailscale ssh " + config.sshTarget(node) + " 'command -v tmux'");
                continue;
            }
            console.out("OK    " + node.id().value() + " remote tmux found");

            for (String socket : node.sockets()) {
                ExecResult sessions = remote.execute(node, TmuxCommands.listSessions(socket));
                String label = node.id().value() + " tmux " + socket + " list-sessions";
                if (TmuxFailure.noServer(sessions)) {
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
        for (NodeConfig node : config.nodeConfigs()) {
            networkNode(node);
        }
        return failed ? ExitCodes.REMOTE_EXECUTION_ERROR : ExitCodes.SUCCESS;
    }

    private void networkNode(NodeConfig node) throws IOException, InterruptedException {
        String host = node.host();
        ExecResult ping = localProcess.capture(List.of("tailscale", "ping", "--c=1", host), Duration.ofSeconds(6));
        if (pingReachable(ping)) {
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
        if (shouldCheckTailscaleDns(host) && localProcess.commandExists("dig")) {
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
        if (isHostKeyFailure(error)) {
            console.out("FAIL  " + node.id().value() + " tailscale ssh host key verification failed: " + error);
            console.out("Try:");
            console.out("  tailscale ssh " + config.sshTarget(node) + " 'echo ok'");
            return;
        }
        if (isAuthFailure(error)) {
            console.out("FAIL  " + node.id().value() + " tailscale ssh auth failed: " + error);
            console.out("Try:");
            console.out("  tailscale ssh " + config.sshTarget(node) + " 'echo ok'");
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
}
