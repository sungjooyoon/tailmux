package dev.tailmux;

import dev.tailmux.cli.CommandRouter;
import dev.tailmux.cli.ExitCodes;
import dev.tailmux.config.TailmuxConfig;
import dev.tailmux.exec.ExecResult;
import dev.tailmux.exec.FakeRemoteExecutor;
import dev.tailmux.state.PropertiesStateStore;
import dev.tailmux.tmux.TmuxCommands;

import java.time.Clock;
import java.util.List;
import java.util.Properties;

final class DoctorTests extends TestMain {
    @Override
    void run() throws Exception {
        testDoctorClassification();
        testDoctorClassifiesMagicDnsFailure();
        testDoctorNetworkUsesSafeReadOnlyProbes();
        testDoctorNetworkTreatsDerpPongAsReachable();
        testDoctorNetworkChecksTailscaleDnsForFqdnOnly();
    }

    private void testDoctorClassification() throws Exception {
        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.when("office-a", "echo ok", ExecResult.success("ok\n"));
        remote.when("office-a", "command -v tmux", ExecResult.success("/opt/homebrew/bin/tmux\n"));
        remote.when("office-a", TmuxCommands.listSessions("default"), ExecResult.failure(1, "", "no server running"));

        CapturingConsole console = new CapturingConsole();
        int exit = new CommandRouter(configWithOneNode(), new PropertiesStateStore(tempDir().resolve(".tailmux/state")), remote, Clock.systemUTC(), console)
                .run(List.of("doctor"));

        check(exit == ExitCodes.SUCCESS, "doctor no tmux server is warning not failure");
        check(console.out().contains("WARN  office-a tmux default no server currently running"), "doctor warns for no server");
    }

    private void testDoctorClassifiesMagicDnsFailure() throws Exception {
        FakeRemoteExecutor remote = new FakeRemoteExecutor();
        remote.when("office-a", "echo ok", ExecResult.failure(255, "", "ssh: Could not resolve hostname office-a.tail.ts.net.: nodename nor servname provided, or not known"));

        CapturingConsole console = new CapturingConsole();
        int exit = new CommandRouter(configWithOneNode(), new PropertiesStateStore(tempDir().resolve(".tailmux/state")), remote, Clock.systemUTC(), console)
                .run(List.of("doctor"));

        check(exit == ExitCodes.REMOTE_EXECUTION_ERROR, "doctor magicdns failure exits remote error");
        check(console.out().contains("FAIL  office-a magicdns resolution failed"), "doctor classifies magicdns failure");
        check(console.out().contains("Try:"), "doctor prints next action");
        check(!console.out().contains("tailscale down"), "doctor never recommends tailscale down");
    }

    private void testDoctorNetworkUsesSafeReadOnlyProbes() throws Exception {
        FakeLocalProcess process = new FakeLocalProcess();
        process.when(List.of("tailscale", "status"), ExecResult.success("100.0.0.1 office-a user@ macOS -\n"));
        process.when(List.of("tailscale", "ping", "--c=1", "office-a"), ExecResult.success("pong from office-a (100.0.0.1) in 10ms\n"));
        process.when(List.of("dscacheutil", "-q", "host", "-a", "name", "office-a"), ExecResult.failure(0, "", ""));

        CapturingConsole console = new CapturingConsole();
        int exit = new CommandRouter(configWithOneNode(), new PropertiesStateStore(tempDir().resolve(".tailmux/state")), new FakeRemoteExecutor(),
                Clock.systemUTC(), console, process).run(List.of("doctor", "--network"));

        check(exit == ExitCodes.SUCCESS, "network doctor exits success");
        check(console.out().contains("OK    tailscale status"), "network doctor checks tailscale status");
        check(console.out().contains("OK    office-a tailscale ping"), "network doctor pings node");
        check(console.out().contains("WARN  office-a macOS resolver did not resolve host"), "network doctor reports resolver miss");
        check(!console.out().contains("tailscale dns did not resolve host"), "network doctor skips short-name dig noise");
        check(!process.commands().toString().contains("down"), "network doctor does not run tailscale down");
        check(!process.commands().toString().contains("up"), "network doctor does not run tailscale up");
        check(!process.commands().toString().contains("set"), "network doctor does not run tailscale set");
    }

    private void testDoctorNetworkTreatsDerpPongAsReachable() throws Exception {
        FakeLocalProcess process = new FakeLocalProcess();
        process.when(List.of("tailscale", "status"), ExecResult.success("ok\n"));
        process.when(List.of("tailscale", "ping", "--c=1", "office-a"), ExecResult.failure(1, "pong from office-a (100.0.0.1) via DERP(sfo) in 20ms\ndirect connection not established\n", ""));
        process.when(List.of("dscacheutil", "-q", "host", "-a", "name", "office-a"), ExecResult.success("name: office-a\nip_address: 100.0.0.1\n"));

        CapturingConsole console = new CapturingConsole();
        int exit = new CommandRouter(configWithOneNode(), new PropertiesStateStore(tempDir().resolve(".tailmux/state")), new FakeRemoteExecutor(),
                Clock.systemUTC(), console, process).run(List.of("doctor", "--network"));

        check(exit == ExitCodes.SUCCESS, "network doctor derp pong exits success");
        check(console.out().contains("OK    office-a tailscale ping"), "network doctor treats derp pong as reachable");
    }

    private void testDoctorNetworkChecksTailscaleDnsForFqdnOnly() throws Exception {
        Properties p = new Properties();
        p.setProperty("tailmux.home.pool", "office-a");
        p.setProperty("tailmux.node.office-a.host", "office-a.tail.ts.net");
        TailmuxConfig config = TailmuxConfig.fromProperties(p);
        FakeLocalProcess process = new FakeLocalProcess();
        process.when(List.of("tailscale", "status"), ExecResult.success("ok\n"));
        process.when(List.of("tailscale", "ping", "--c=1", "office-a.tail.ts.net"), ExecResult.success("pong\n"));
        process.when(List.of("dscacheutil", "-q", "host", "-a", "name", "office-a.tail.ts.net"), ExecResult.failure(0, "", ""));
        process.when(List.of("dig", "@100.100.100.100", "office-a.tail.ts.net"), ExecResult.success("office-a.tail.ts.net. 600 IN A 100.0.0.1\n"));

        CapturingConsole console = new CapturingConsole();
        int exit = new CommandRouter(config, new PropertiesStateStore(tempDir().resolve(".tailmux/state")), new FakeRemoteExecutor(),
                Clock.systemUTC(), console, process).run(List.of("doctor", "--network"));

        check(exit == ExitCodes.SUCCESS, "network doctor fqdn exits success");
        check(console.out().contains("OK    office-a tailscale dns resolved host"), "network doctor checks fqdn tailscale dns");
    }
}
