package dev.tailmux.tmux;

import dev.tailmux.exec.ExecResult;
import dev.tailmux.text.Ascii;

public final class TmuxFailure {
    private static final String[] MISSING_BINARY = {
            "tmux: command not found",
            "tmux: not found",
            "command not found: tmux"
    };
    private static final String[] NO_SERVER = {
            "no server",
            "no tmux server",
            "failed to connect to server"
    };

    private TmuxFailure() {
    }

    public enum Kind {
        OK,
        NO_SERVER,
        MISSING_BINARY,
        REMOTE_EXECUTION,
        OTHER
    }

    public static Kind classify(ExecResult result) {
        if (result.ok()) return Kind.OK;
        if (remoteExecution(result)) return Kind.REMOTE_EXECUTION;
        if (result.exitCode() == 127) return Kind.MISSING_BINARY;
        if (containsAny(result, MISSING_BINARY)) return Kind.MISSING_BINARY;
        if (containsAny(result, NO_SERVER)) return Kind.NO_SERVER;
        return Kind.OTHER;
    }

    private static boolean containsAny(ExecResult result, String[] needles) {
        return Ascii.containsAnyIgnoreCase(result.stderr(), needles) || Ascii.containsAnyIgnoreCase(result.stdout(), needles);
    }

    public static boolean remoteExecution(ExecResult result) {
        return result.exitCode() == 255 || result.exitCode() == 124;
    }
}
