package dev.tailmux.tmux;

import dev.tailmux.exec.ExecResult;

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
        String text = (result.stderr() + "\n" + result.stdout()).toLowerCase();
        if (result.exitCode() == 127
                || text.contains("tmux: command not found")
                || text.contains("tmux: not found")
                || text.contains("command not found: tmux")) {
            return Kind.MISSING_BINARY;
        }
        if (text.contains("no server")
                || text.contains("no tmux server")
                || text.contains("failed to connect to server")) {
            return Kind.NO_SERVER;
        }
        return Kind.OTHER;
    }

    public static boolean noServer(ExecResult result) {
        return classify(result) == Kind.NO_SERVER;
    }

    public static boolean missingBinary(ExecResult result) {
        return classify(result) == Kind.MISSING_BINARY;
    }

    public static boolean remoteExecution(ExecResult result) {
        return result.exitCode() == 255 || result.exitCode() == 124;
    }
}
