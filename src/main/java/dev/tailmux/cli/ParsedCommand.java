package dev.tailmux.cli;

import java.util.List;

public record ParsedCommand(String command, List<String> args, String home) {
    public ParsedCommand {
        args = args == null ? List.of() : args;
    }
}
