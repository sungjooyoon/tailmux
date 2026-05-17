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
        return contains(result.stderr(), needle) || contains(result.stdout(), needle);
    }

    private static boolean contains(String text, String needle) {
        int end = text.length() - needle.length();
        for (int i = 0; i <= end; i++) {
            if (text.regionMatches(true, i, needle, 0, needle.length())) return true;
        }
        return false;
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
