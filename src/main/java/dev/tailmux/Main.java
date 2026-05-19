package dev.tailmux;

import dev.tailmux.cli.CommandRouter;
import dev.tailmux.cli.ExitCodes;
import dev.tailmux.cli.SystemConsole;
import dev.tailmux.config.TailmuxConfig;
import dev.tailmux.core.TailmuxException;
import dev.tailmux.exec.LocalProcess;
import dev.tailmux.state.PropertiesStateStore;
import dev.tailmux.tailscale.TailscaleSshExecutor;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        SystemConsole console = new SystemConsole();
        try {
            Path home = Path.of(System.getProperty("user.home"));
            TailmuxConfig config = TailmuxConfig.load(home);
            LocalProcess process = new LocalProcess();
            PropertiesStateStore store = new PropertiesStateStore(home.resolve(".tailmux/state"));
            CommandRouter router = new CommandRouter(
                    config,
                    store,
                    new TailscaleSshExecutor(process),
                    Clock.systemUTC(),
                    console,
                    process
            );
            System.exit(router.run(stripLauncherScriptArg(args)));
        } catch (TailmuxException e) {
            console.err(e.getMessage());
            System.exit(e.exitCode());
        } catch (RuntimeException e) {
            console.err("FAIL " + e.getMessage());
            System.exit(ExitCodes.GENERAL_FAILURE);
        }
    }

    private static List<String> stripLauncherScriptArg(String[] args) {
        if (args.length == 0) {
            return List.of();
        }
        if (isLauncherScript(args[0])) {
            return Arrays.asList(args).subList(1, args.length);
        }
        return Arrays.asList(args);
    }

    private static boolean isLauncherScript(String value) {
        return value.endsWith("scripts/tailmux")
                || value.endsWith("./scripts/tailmux")
                || value.endsWith("scripts\\tailmux")
                || value.endsWith(".\\scripts\\tailmux");
    }
}
