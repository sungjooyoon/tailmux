package dev.tailmux.core;

public final class TailmuxException extends RuntimeException {
    private final int exitCode;

    public TailmuxException(int exitCode, String message) {
        super(message);
        this.exitCode = exitCode;
    }

    public TailmuxException(int exitCode, String message, Throwable cause) {
        super(message, cause);
        this.exitCode = exitCode;
    }

    public int exitCode() {
        return exitCode;
    }
}

