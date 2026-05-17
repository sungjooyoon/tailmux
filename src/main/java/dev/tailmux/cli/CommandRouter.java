package dev.tailmux.cli;

import dev.tailmux.config.TailmuxConfig;
import dev.tailmux.core.TailmuxException;
import dev.tailmux.core.WorkspaceName;
import dev.tailmux.exec.LocalProcess;
import dev.tailmux.exec.RemoteExecutor;
import dev.tailmux.state.PropertiesStateStore;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

public final class CommandRouter {
    private final TailmuxConfig config;
    private final PropertiesStateStore store;
    private final RemoteExecutor remote;
    private final Clock clock;
    private final Console console;
    private final LocalProcess localProcess;
    private final DiscoveryService discovery;
    private final WorkspaceService workspace;

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
        this.discovery = new DiscoveryService(store, remote, clock);
        this.workspace = new WorkspaceService(config, store, remote, clock, console, discovery);
    }

    public ParsedCommand classify(List<String> args) {
        if (args.isEmpty()) {
            return new ParsedCommand("help", List.of(), null);
        }
        String first = args.getFirst();
        return switch (first) {
            case "doctor", "nodes", "ls", "attach", "start", "help" -> parseBuiltin(first, args.subList(1, args.size()));
            default -> new ParsedCommand("workspace", args, null);
        };
    }

    public int run(List<String> args) {
        try {
            store.ensureWritable();
            ParsedCommand parsed = classify(args);
            store.appendEvent(clock.instant(), "command", "command", parsed.command());
            return switch (parsed.command()) {
                case "doctor" -> doctor(parsed.args());
                case "nodes" -> nodes();
                case "ls" -> list(parsed.args());
                case "attach" -> workspace.attachCommand(parsed.args());
                case "start" -> workspace.startCommand(parsed.args(), parsed.home());
                case "workspace" -> workspace.smartWorkspace(WorkspaceName.parse(parsed.args().getFirst()), null);
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
            return new ParsedCommand(command, rest, null);
        }
        ArrayList<String> parsedArgs = null;
        String home = null;
        for (int i = 0; i < rest.size(); i++) {
            String arg = rest.get(i);
            if ("--home".equals(arg)) {
                if (i + 1 >= rest.size()) {
                    throw new IllegalArgumentException("--home requires a node");
                }
                if (parsedArgs == null) parsedArgs = new ArrayList<>(rest.subList(0, i));
                home = rest.get(++i);
            } else {
                if (parsedArgs != null) parsedArgs.add(arg);
            }
        }
        return new ParsedCommand(command, parsedArgs == null ? rest : parsedArgs, home);
    }

    private int usage() {
        console.out("usage: tailmux doctor|nodes|ls|attach|start|<workspace>");
        return ExitCodes.SUCCESS;
    }

    private int nodes() {
        Renderers.renderNodes(console, config, discovery.discoverAll(config.nodeConfigs(), false), clock);
        return ExitCodes.SUCCESS;
    }

    private int doctor(List<String> args) throws IOException, InterruptedException {
        boolean network = false;
        for (String arg : args) {
            if ("--network".equals(arg)) network = true;
        }
        return new DoctorCommand(config, remote, localProcess, console).run(network);
    }

    private int list(List<String> args) {
        boolean windows = false;
        boolean panes = false;
        for (String arg : args) {
            if ("--panes".equals(arg)) {
                panes = true;
                windows = true;
            } else if ("--windows".equals(arg)) {
                windows = true;
            }
        }
        Renderers.renderLs(console, discovery.discoverAll(config.nodeConfigs(), windows, panes), clock, windows, panes);
        return ExitCodes.SUCCESS;
    }
}
