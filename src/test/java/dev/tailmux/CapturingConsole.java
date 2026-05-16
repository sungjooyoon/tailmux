package dev.tailmux;

import dev.tailmux.cli.Console;

public final class CapturingConsole implements Console {
    private final StringBuilder out = new StringBuilder();
    private final StringBuilder err = new StringBuilder();

    @Override
    public void out(String line) {
        out.append(line).append('\n');
    }

    @Override
    public void err(String line) {
        err.append(line).append('\n');
    }

    public String out() {
        return out.toString();
    }

    public String err() {
        return err.toString();
    }
}

