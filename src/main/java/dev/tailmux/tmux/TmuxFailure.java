package dev.tailmux.tmux;

import dev.tailmux.exec.ExecResult;

public final class TmuxFailure {
    private TmuxFailure() {
    }

    public static boolean noServer(ExecResult result) {
        String text = (result.stderr() + "\n" + result.stdout()).toLowerCase();
        return result.exitCode() != 0 && (text.contains("no server")
                || text.contains("no tmux server")
                || text.contains("failed to connect to server"));
    }

    public static boolean missingBinary(ExecResult result) {
        String text = (result.stderr() + "\n" + result.stdout()).toLowerCase();
        return result.exitCode() == 127
                || text.contains("tmux: command not found")
                || text.contains("tmux: not found")
                || text.contains("command not found: tmux");
    }

    public static boolean remoteExecution(ExecResult result) {
        return result.exitCode() == 255 || result.exitCode() == 124;
    }
}
