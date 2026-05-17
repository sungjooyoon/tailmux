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
import java.util.Map;
import java.util.Optional;

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
            return new ParsedCommand("help", List.of(), Optional.empty());
        }
        String first = args.getFirst();
        return switch (first) {
            case "doctor", "nodes", "ls", "attach", "start", "help" -> parseBuiltin(first, args.subList(1, args.size()));
            default -> new ParsedCommand("workspace", List.of(first), Optional.empty());
        };
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
                case "attach" -> workspace.attachCommand(parsed.args());
                case "start" -> workspace.startCommand(parsed.args(), parsed.home());
                case "workspace" -> workspace.smartWorkspace(WorkspaceName.parse(parsed.args().getFirst()), Optional.empty());
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
        ArrayList<String> args = new ArrayList<>();
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
        Renderers.renderNodes(console, config, discovery.discoverAll(config.nodeConfigs(), false), clock);
        return ExitCodes.SUCCESS;
    }

    private int list(boolean includeWindows, boolean includePanes) {
        Renderers.renderLs(console, discovery.discoverAll(config.nodeConfigs(), includeWindows, includePanes), clock, includeWindows, includePanes);
        return ExitCodes.SUCCESS;
    }
}
