package dev.tailmux.exec;

import dev.tailmux.text.Ascii;

public record ExecResult(int exitCode, String stdout, String stderr) {
    public static ExecResult success(String stdout) {
        return new ExecResult(0, stdout, "");
    }

    public static ExecResult failure(int exitCode, String stdout, String stderr) {
        return new ExecResult(exitCode, stdout, stderr);
    }

    public boolean ok() {
        return exitCode == 0;
    }

    public String errorText() {
        String error = Ascii.trim(stderr);
        return error.isEmpty() ? Ascii.trim(stdout) : error;
    }
}
