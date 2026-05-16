package dev.tailmux.cli;

public final class SystemConsole implements Console {
    @Override
    public void out(String line) {
        System.out.println(line);
    }

    @Override
    public void err(String line) {
        System.err.println(line);
    }
}

