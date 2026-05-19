package dev.tailmux.tmux;

import dev.tailmux.exec.ExecResult;
import dev.tailmux.text.Ascii;

public final class TmuxFailure {
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
        if (contains(result, "tmux: command not found")
                || contains(result, "tmux: not found")
                || contains(result, "command not found: tmux")) {
            return Kind.MISSING_BINARY;
        }
        if (contains(result, "no server")
                || contains(result, "no tmux server")
                || contains(result, "failed to connect to server")) {
            return Kind.NO_SERVER;
        }
        return Kind.OTHER;
    }

    private static boolean contains(ExecResult result, String needle) {
        return Ascii.containsIgnoreCase(result.stderr(), needle) || Ascii.containsIgnoreCase(result.stdout(), needle);
    }

    public static boolean remoteExecution(ExecResult result) {
        return result.exitCode() == 255 || result.exitCode() == 124;
    }
}
